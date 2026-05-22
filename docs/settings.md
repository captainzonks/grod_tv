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
| `default_quality`    | string  | `1080p`                       | One of `best`, `1080p`, `720p`, `480p`, `360p`   |
| `api_pin`            | string  | `""`                          | Empty disables `X-Grod-Pin` auth                 |
| `first_run_seen`     | boolean | `false`                       | Set to `true` when the user dismisses first-run  |

---

## Editing from the UI

The Settings screen is reachable from Home → D-pad UP → Settings (or directly via nav if first-run is incomplete). Each field persists on change:

- **Piped API URL** — `OutlinedTextField` with URI keyboard, `singleLine=true`. The trailing slash is trimmed by `SettingsStore.setPipedApiUrl`.
- **Default quality** — `Button` that opens a `DropdownMenu` enumerating `Quality.entries`. The selected value is written immediately.
- **API PIN** — `OutlinedTextField` with `NumberPassword` keyboard and `PasswordVisualTransformation`. An empty value clears the PIN (and disables auth).
- **Test connection** — instantiates a one-shot `PipedClient(typedUrl, container.httpClient)` and calls `search("test")`. Surfaces:
  - `Testing…` (yellow) while in flight
  - `OK (N results)` (green) on `Result.success`
  - `FAIL: <error message>` (red) on `Result.failure`

The test does **not** use the persisted URL — it tests whatever is in the text field right now, so you can validate a new server before committing to it.

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
6. Add a control in `SettingsScreen.kt` and persist on change with a `LaunchedEffect(fieldState)`.

---

## Quality picker semantics

The `Quality` enum maps to a target pixel height:

| Variant    | Target height | Notes                                                    |
| ---------- | ------------- | -------------------------------------------------------- |
| `Best`     | `Int.MAX_VALUE` | Picks the highest available height per Piped response  |
| `P1080`    | 1080          | Falls back to next-lower available if 1080 absent        |
| `P720`     | 720           | Same                                                     |
| `P480`     | 480           | Same                                                     |
| `P360`     | 360           | Same                                                     |

`PipedClient.pickStreamsForQuality` further filters video streams to H.264 codecs (`avc1*`, `avc3*`, `h264*`). AV1 (`av01.*`) and VP9 (`vp09.*`) are dropped because we don't transcode and most TV decoders aren't reliable on AV1.

Audio-track selection always prefers `audioTrackType == "ORIGINAL"` to skip auto-dubs.
