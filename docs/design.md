# grod-tv — Android TV Design

**Status:** Draft / not implemented
**Target:** Android TV 9+ (API 28+), tested on Nvidia Shield TV
**Goal:** Replace the laptop daemon. TV runs the queue, fetches from Piped, plays directly. Phone is remote-only.

---

## 1. Problem

Today's grod stack needs three machines:

```
[Phone Flutter app] ──HTTP──> [Laptop daemon (Rust)] ──ffmpeg+HLS──> [Chromecast]
```

The laptop is the painful link:
- Must be on, awake, on the same LAN.
- ffmpeg eats CPU, lid-close suspends playback.
- Firewall, port-forwarding, sudo prompts on first setup.
- The Chromecast is a dumb media client — won't play arbitrary mp4 reliably.

Most Android TV devices (Shield, Chromecast with Google TV, Fire TV Stick) are full Android — perfectly capable of doing everything the daemon does *on the TV itself*. ExoPlayer (Media3) handles HLS, DASH, and crucially the separate-video + separate-audio case via `MergingMediaSource` — no muxing step needed.

## 2. Goal

A grod-tv Android TV app that:

1. Plays videos directly on the TV via ExoPlayer (no Cast protocol, no Chromecast SDK).
2. Hosts the same HTTP API as today's daemon, so the existing Flutter phone app is a drop-in remote with zero protocol changes.
3. Advertises itself on the LAN via mDNS (`_grod._tcp.local.`) — phone discovers it the same way it discovers the laptop today.
4. Persists the queue across reboots.
5. Runs as a leanback-friendly Android TV app (D-pad navigation, large fonts, no touch dependency for the "now playing" surface).

The laptop daemon stays around (different device target, useful when you don't have a smart TV) but stops being the recommended path for Shield/Chromecast-w/-Google-TV/Fire users.

## 3. Non-goals (for the first cut)

- **Cross-device sync.** One TV = one queue. No multi-room.
- **Account login / Piped sponsor-block / DRM.** Out of scope.
- **Local file playback / DLNA / library scanning.** YouTube/Piped only.
- **iOS remote.** Phone app stays Android Flutter-only initially.
- **Tablet/phone-mode UI for grod-tv.** It's a leanback app — phone install is a fallback at best.

## 4. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ Android TV device (the only piece of "infrastructure" needed)    │
│                                                                  │
│  ┌────────────────────────┐    ┌─────────────────────────────┐   │
│  │ HTTP API service       │    │ Player service              │   │
│  │  - Ktor or NanoHTTPD   │    │  - ExoPlayer (Media3)       │   │
│  │  - same routes as Rust │    │  - MergingMediaSource for   │   │
│  │    daemon (drop-in)    │    │    video-only + audio-only  │   │
│  │  - PIN middleware      │    │  - foreground service so    │   │
│  └──────────┬─────────────┘    │    play survives backgrnd   │   │
│             │                  └────────────┬────────────────┘   │
│             │                               │                    │
│  ┌──────────▼─────────────────────────────▼─┐                    │
│  │ QueueRepository (Room) + PipedClient     │                    │
│  └──────────┬───────────────────────────────┘                    │
│             │                                                    │
│             ▼                                                    │
│  ┌────────────────────────┐    ┌─────────────────────────────┐   │
│  │ mDNS advertiser        │    │ Leanback UI                 │   │
│  │  - NsdManager          │    │  - Browse fragment for queue│   │
│  │  - _grod._tcp.local.   │    │  - PlaybackOverlayFragment  │   │
│  └────────────────────────┘    │  - D-pad first              │   │
│                                └─────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
              ▲
              │ HTTP (with optional X-Grod-Pin header)
              │ mDNS PTR/SRV/TXT for discovery
              │
┌──────────────────────────────────────────────────────────────────┐
│ Flutter phone app (unchanged from today)                         │
│  - already speaks /cast, /queue, /skip, /quality, ...            │
│  - already browses _grod._tcp.local.                             │
└──────────────────────────────────────────────────────────────────┘
```

### 4.1 Why ExoPlayer's `MergingMediaSource` replaces ffmpeg

Piped returns muxed mp4 only up to ~360p. For 1080p, the API returns
separate video-only and audio-only streams. Today's daemon hands both
URLs to ffmpeg, which mux-copies them into HLS and serves it locally
because the Chromecast media receiver is too dumb to merge two streams
itself.

Android's ExoPlayer is not dumb. It takes:

```kotlin
val videoSource = ProgressiveMediaSource.Factory(httpFactory)
    .createMediaSource(MediaItem.fromUri(video.videoUrl))
val audioSource = ProgressiveMediaSource.Factory(httpFactory)
    .createMediaSource(MediaItem.fromUri(video.audioUrl))
val merged = MergingMediaSource(videoSource, audioSource)
player.setMediaSource(merged)
```

…and merges them in the decoder at playback time. **No transcode, no
HLS, no segments, no disk. No ffmpeg.** This is the single biggest
win — it cuts out the most fragile component in today's stack.

If a video resolves to a single muxed URL (≤360p, shorts, livestreams),
fall back to a plain `ProgressiveMediaSource`. Quality preference logic
lives unchanged in the Piped client.

### 4.2 HTTP API surface

Identical to the Rust daemon, byte-for-byte where possible:

```
GET  /status         → { state, now_playing, queue, daemon, quality }
POST /cast           ← { url }
POST /queue          ← { url }
DELETE /queue/{pos}
DELETE /queue
POST /skip
POST /play-pause
POST /volume-up
POST /volume-down
POST /mute
POST /unmute
POST /forward        ← { seconds }
POST /back           ← { seconds }
GET  /search?q=
POST /quality        ← { quality }
```

PIN header `X-Grod-Pin` honored. No new endpoints initially.

State mapping for `/status` differs slightly: instead of polling
`go-chromecast`, the API service queries the local `Player` directly via
shared state. `BUFFERING` maps from ExoPlayer's `Player.STATE_BUFFERING`.

### 4.3 mDNS

`NsdManager.registerService()` with:
- Service type `_grod._tcp.`
- Port = the API service's port (configurable, default 7878)
- TXT records:
  - `version` = grod-tv build version
  - `pin` = "1" or "0"
  - `device` = "grod-tv" (NEW — lets the phone distinguish laptop daemons from TV apps when both exist; the laptop daemon should add this too)

### 4.4 Process / service model

Two Android `Service` components:

1. **`PlayerService`** — `MediaSessionService` so playback survives
   when the user pops to the home screen. Owns the `ExoPlayer`
   instance. Foreground service with notification = required for
   uninterrupted background audio on Android 14+.

2. **`ApiService`** — long-lived foreground service hosting Ktor on
   the configured port. Holds a `WifiManager.MulticastLock` for the
   mDNS advertiser. Exposes a bound `IBinder` to the UI activity for
   queue inspection.

Both services share a single `AppContainer` (manual DI; Hilt is
overkill at this size) holding:

- `QueueRepository` (Room DB at `queue.db`)
- `PipedClient` (OkHttp + kotlinx.serialization)
- `PlayerController` (wraps ExoPlayer, exposes `Flow<PlayerState>`)
- `SettingsStore` (DataStore for PIN, default quality, Piped API URL)

### 4.5 Persistence

| What                    | Where                                       |
|-------------------------|---------------------------------------------|
| Queue                   | Room DB at internal storage                 |
| Now-playing pointer     | Room DB row, replaces today's `now_playing` |
| Settings (PIN, quality) | Jetpack DataStore (Preferences)             |

No SD card writes, no scoped-storage juggling — TV apps run as a single
user.

### 4.6 Leanback UI

Minimal screens, D-pad navigable:

| Screen        | Purpose                                                    |
|---------------|-------------------------------------------------------------|
| Browse        | Queue list + now-playing card + "Open Settings" row         |
| Playback      | PlaybackOverlayFragment when a video is playing             |
| Settings      | Piped API URL, PIN, default quality, mDNS-broadcast-name    |
| First-run     | Shows the IP + PIN ("Open the grod remote on your phone and find this TV") |

The first-run screen is critical UX. The phone discovers via mDNS, so
the user *should* never need to type the IP — but the TV still shows it
in big text in case mDNS is broken on their network.

## 5. Tech stack

| Concern              | Pick                                | Why                                                              |
|----------------------|--------------------------------------|------------------------------------------------------------------|
| Language             | Kotlin 2.x                           | Idiomatic for Android, coroutines for IO                         |
| UI                   | Jetpack Compose + Compose-for-TV     | Modern, leanback support added 2024                              |
| Player               | Media3 ExoPlayer ≥ 1.4               | `MergingMediaSource`, HLS/DASH/Progressive, no surprises         |
| HTTP server          | Ktor server-cio                      | Native Kotlin, small, no Netty bloat                             |
| HTTP client          | OkHttp + kotlinx-serialization-json  | Standard, stable                                                 |
| Persistence          | Room + DataStore Preferences         | Standard Android persistence                                     |
| mDNS                 | `android.net.nsd.NsdManager`         | Built-in, no extra deps                                          |
| Build                | Gradle Kotlin DSL                    | …                                                                |
| Min SDK              | 28 (Android 9)                       | Covers Shield TV (Android 9+); Compose-for-TV requires ≥ 21 anyway |
| Target SDK           | 34 (Android 14)                      | Required by Play Store as of 2024                                |

Explicitly **rejected**:
- `ffmpeg-kit` — abandoned upstream Jan 2025, GPL gotchas.
- Rust core via JNI — interesting but doubles build complexity for no clear win over a Kotlin port. The "Rust gives us speed" argument doesn't apply when ExoPlayer is doing the heavy lifting.
- React Native / Flutter on TV — TV remote UX in cross-platform stacks is consistently bad in 2025; native Compose-for-TV avoids that bucket of problems.

## 6. Phone-side changes

The phone app already speaks the daemon's HTTP API and already browses
`_grod._tcp.local.`. Surface-level changes only:

1. **Discovery dialog** — when multiple `_grod._tcp` services are found,
   show TXT-record `device` field (laptop / grod-tv) so the user can
   pick. Default to grod-tv when present.
2. **Quality dropdown** — TV reports its own supported list via TXT or
   a new `/capabilities` endpoint; phone reads it. (Not strictly needed
   for v1: the existing fixed list still works.)
3. Nothing else. The endpoints are identical.

## 7. Migration / coexistence

The laptop daemon doesn't go away. Both can coexist on the same LAN.
The mDNS `device` TXT field distinguishes them. A user can run both
and the phone shows both in the discovery dialog.

Recommended user paths:

| Setup                            | Recommendation                       |
|----------------------------------|--------------------------------------|
| Smart TV (Shield, etc.)          | grod-tv only                         |
| Chromecast-only / older TV       | Laptop daemon (today's stack)        |
| Both, multiple TVs               | Run both, pick from phone app        |

## 8. Open questions

1. **DRM / SponsorBlock parity.** Piped supports SponsorBlock skips. ExoPlayer doesn't natively. Implement client-side: poll `/sponsorblock` from Piped, schedule `player.seekTo()` at segment boundaries. Worth it but deferred.
2. **Casting from grod-tv back to a Chromecast.** Probably not — if the user has a TV running this app, casting is redundant. Document the boundary.
3. **Should the laptop daemon also advertise `device=laptop` in its TXT?** Yes — small change, big UX gain. Add now to the Rust daemon while we're touching mDNS code.
4. **Battery / always-on.** TV devices vary on background-service rules. Need to verify on Shield specifically that the API + Player services survive when the TV goes to "ambient mode."
5. **Audio-only playback.** Some Piped resolutions are audio-only by design. Already supported by `MergingMediaSource` (drop the video source). Nice-to-have, not blocker.
6. **Subtitles.** Piped exposes caption tracks. ExoPlayer handles `TextTrackGroup` natively. Easy add-on, deferred.

## 9. Implementation phases

Cut into independently-shippable slices:

### Phase 0 — design + scaffolding
- This doc
- Empty Gradle project at `~/repos/grod_tv`
- Basic Compose-for-TV "Hello, grod-tv" Activity
- CI: build APK on push, lint, Compose preview check

### Phase 1 — playback core (no networking)
- ExoPlayer integration
- PlayerService as a `MediaSessionService`
- Hardcoded test playlist URLs (3 known-good Piped video-only / audio-only pairs)
- Validate `MergingMediaSource` works end-to-end on Shield TV

### Phase 2 — Piped client + queue
- `PipedClient` mirroring the Rust `piped.rs` (search, resolve, quality picker)
- Room schema + DAO for queue
- Glue: queue.push() → PipedClient.resolve() → ExoPlayer plays
- Leanback Browse UI for the queue, basic D-pad operability

### Phase 3 — HTTP API + mDNS
- Ktor server with all routes from §4.2
- PIN middleware
- mDNS advertiser via NsdManager
- Drop the existing Flutter phone app onto an Android TV running grod-tv. End-to-end smoke test: discover, queue from phone, play on TV.

### Phase 4 — UX polish
- First-run screen (IP + PIN)
- Settings screen (Piped URL, PIN, quality)
- Buffering indicator overlay
- Error states (Piped down, video region-locked, etc.)
- Optional: SponsorBlock

### Phase 5 — Ship
- Sideload APK from GitHub releases (CI builds it)
- F-Droid metadata
- (Stretch) Play Store TV listing — high effort, low marginal user value if F-Droid + sideload covers it

Each phase is a separate branch + PR. Phase 1 is the riskiest (validating that the merging-source approach actually works on Shield); everything after is plumbing.

## 10. Risks

| Risk                                                  | Likelihood | Mitigation                                                       |
|-------------------------------------------------------|------------|------------------------------------------------------------------|
| ExoPlayer chokes on Piped's video-only / audio-only pair | Low        | Validate in Phase 1 before doing anything else                   |
| Foreground-service rules tighten further on Android 16+ | Medium     | Use `MediaSessionService` — Google's blessed playback path        |
| YouTube changes Piped's reachable endpoints              | Ongoing    | Same risk as today's Rust daemon; mitigation lives in Piped, not here |
| TV remote UX without touch is hard                       | Medium     | Compose-for-TV + leanback patterns; prototype in Phase 0          |
| Network discovery flakiness on enterprise / mesh wifi    | Medium     | Manual IP fallback always available; document, don't fight it    |

---

## Appendix: minimal player wiring

For reference — the merging-source piece that makes this whole design viable:

```kotlin
class PlayerController(context: Context) {
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("grod-tv/${BuildConfig.VERSION_NAME}")

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    fun play(video: ResolvedVideo) {
        val source = when {
            video.videoUrl != null && video.audioUrl != null -> {
                val v = ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(video.videoUrl))
                val a = ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(video.audioUrl))
                MergingMediaSource(v, a)
            }
            video.streamUrl != null -> {
                ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(video.streamUrl))
            }
            else -> error("no playable stream")
        }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
}
```

That's the heart of grod-tv. Everything else is configuration, UI, and
HTTP endpoints we've already written once.
