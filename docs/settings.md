# Settings reference

`grod_tv` persists user-facing configuration in Android DataStore Preferences (file: `/data/data/com.captainzonks.grodtv/files/datastore/grod_tv_settings.preferences_pb`). All values can be edited from the in-app **Settings** screen and are read by `AppContainer.settings` as a hot `StateFlow<Settings>`.

```kotlin
data class Settings(
    val pipedApiUrl: String,
    val defaultQuality: Quality,
    val apiPin: String,
    val firstRunSeen: Boolean,
)
```

---

## Keys

| Preference key       | Type    | Default                       | Notes                                            |
| -------------------- | ------- | ----------------------------- | ------------------------------------------------ |
| `piped_api_url`      | string  | `https://pipedapi.kavin.rocks`   | Trailing slash is stripped on write              |
| `default_quality`    | string  | `1080p`                       | One of `best`, `2160p`, `1440p`, `1080p`, `720p`, `480p`, `360p` |
| `api_pin`            | string  | `""`                          | Empty disables `X-Grod-Pin` auth                 |
| `first_run_seen`     | boolean | `false`                       | Set to `true` when the user dismisses first-run  |

---

## Editing from the UI

The Settings screen is reachable from Home → D-pad UP → Settings (or directly via nav if first-run is incomplete). Each field persists on change:

- **Piped API URL** — a focusable read-only row (`EditableSettingRow`) that opens a modal `TextEntryDialog` on D-pad CENTER. The dialog hosts an `OutlinedTextField` (URI keyboard, `singleLine=true`) and is the *only* place the soft IME pops; this keeps D-pad traversal across rows from accidentally summoning the on-screen keyboard. The trailing slash is trimmed by `SettingsStore.setPipedApiUrl`.
- **Default quality** — `Button` (`fillMaxWidth`) that opens a `DropdownMenu` enumerating `Quality.entries`. The selected value is written immediately. Focused state is highlighted with a 3.dp `BrandPurpleFocused` ring (`Modifier.focusRing(focused)`).
- **API PIN** — same `EditableSettingRow` pattern; the dialog uses `NumberPassword` keyboard with `PasswordVisualTransformation`. The row displays `•` characters when set, `—` when empty. An empty submitted value clears the PIN (and disables auth).
- **Test connection** — full-width Button with the same focus ring treatment. Instantiates a one-shot `PipedClient(settings.pipedApiUrl, container.httpClient)` and calls `search("test")`. Status renders on its own line beneath the button:
  - `Testing…` (yellow) while in flight
  - `OK (N results)` (green) on `Result.success`
  - `FAIL: <error message>` (red) on `Result.failure`

The test always uses the **currently persisted** URL (the row layout has no in-flight typed value to consult — the dialog commits on Save before closing).

---

## Editing from the HTTP API

Only the default quality is exposed over HTTP:

```bash
curl -X POST http://<tv-ip>:7878/quality \
     -H 'Content-Type: application/json' \
     -d '{"quality":"720p"}'
```

There is no `/piped-api`, `/pin`, or generic `/settings` endpoint by design — changing the Piped backend or the PIN remotely would create a footgun (you could lock yourself out of the API mid-request).

---

## Editing via ADB / file inspection

DataStore Preferences are protobuf-encoded; you cannot grep them. To dump the current settings programmatically:

```bash
adb shell run-as com.captainzonks.grodtv \
    cat /data/data/com.captainzonks.grodtv/files/datastore/grod_tv_settings.preferences_pb \
    | strings
```

To reset all settings (re-trigger first-run):

```bash
adb shell pm clear com.captainzonks.grodtv
```

This nukes the queue (Room DB), the Settings (DataStore), and any cached HTTP credentials. Use sparingly.

---

## Adding a new setting

1. Add the field to the `Settings` data class in `app/src/main/java/com/captainzonks/grodtv/settings/SettingsStore.kt`.
2. Declare a `Preferences.Key<T>` of the appropriate type (`stringPreferencesKey`, `booleanPreferencesKey`, `intPreferencesKey`, …).
3. Add a `suspend fun setX(value: T)` mutator. DataStore handles thread-safety; no need to wrap in `withContext`.
4. Update the default in `Settings.Default` so existing installs migrate cleanly (DataStore returns `null` for unset keys; the read site provides the default).
5. If the setting affects an `AppContainer`-managed singleton (like `pipedApiUrl` does for `PipedClient`), wire a `settings.map { ... }.stateIn(...)` so it re-derives.
6. Add a control in `SettingsScreen.kt`. For free-text values, add a new `EditTarget` variant and an `EditableSettingRow` so the on-screen IME only appears inside the modal dialog. For one-shot picks (enums / booleans), a `Button` + `DropdownMenu` is the convention.

---

## Quality picker semantics

The `Quality` enum maps to a target pixel height:

| Variant    | Target height | Notes                                                    |
| ---------- | ------------- | -------------------------------------------------------- |
| `Best`     | `Int.MAX_VALUE` | Picks the highest available height per Piped response  |
| `P2160`    | 2160          | Falls back to next-lower available if 2160 absent (VP9/AV1 only) |
| `P1440`    | 1440          | Same (VP9/AV1 only — no H.264 above 1080p)               |
| `P1080`    | 1080          | Falls back to next-lower available if 1080 absent        |
| `P720`     | 720           | Same                                                     |
| `P480`     | 480           | Same                                                     |
| `P360`     | 360           | Same                                                     |

`PipedClient.pickStreamsForQuality` accepts H.264 (`avc1*`/`avc3*`/`h264*`), VP9 (`vp9*`/`vp09*`) and AV1 (`av01*`/`av1*`) video-only streams — YouTube serves nothing above 1080p as H.264, so 1440p/2160p depend on VP9/AV1. At equal height the most bandwidth-efficient codec wins (AV1 > VP9 > H.264).

Selection is **device-aware**: `VideoCodecSupport.detect()` queries `MediaCodecList` at startup for the codecs this device actually has a decoder for, and the picker never selects a codec outside that set. A Tegra X1 Shield reports `{AVC, VP9}` (no AV1 decoder), so it picks VP9 rather than an AV1 stream that would yield a black screen with audio. A newer AV1-capable Google TV reports `{AVC, VP9, AV1}` and keeps the efficient-codec preference. H.264 is always included as a universal floor. No transcode is involved in any case.

Audio-track selection always prefers `audioTrackType == "ORIGINAL"` to skip auto-dubs.
