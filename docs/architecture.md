# grod_tv Architecture

Companion to [`docs/design.md`](design.md) — that doc captures the *rationale*; this one captures the *map* of the implementation as it stands today.

---

## Module layout

```
app/src/main/java/com/captainzonks/grodtv/
├── MainActivity.kt              # ComponentActivity, hosts Compose nav
├── AppContainer.kt              # Manual DI singleton, Application subclass
├── piped/
│   ├── PipedClient.kt           # /streams + /search resolver, stream picker
│   ├── PipedDtos.kt             # @Serializable DTOs + ResolvedVideo domain type
│   ├── Quality.kt               # Best/1080p/720p/480p/360p enum
│   └── VideoId.kt               # extractVideoId() for URLs and bare IDs
├── settings/
│   └── SettingsStore.kt         # DataStore Preferences wrapper + Settings data class
├── queue/
│   ├── QueueEntity.kt           # @Entity rows (queue + singleton now-playing)
│   ├── QueueDao.kt              # @Dao + @Transaction pushAtEnd/popHead/removeAt
│   ├── GrodTvDatabase.kt        # @Database with on-disk + in-memory builders
│   └── QueueRepository.kt       # Flow-returning facade; 0-based ↔ 1-based shim
├── player/
│   ├── PlayerController.kt      # ExoPlayer wrapper, suspend mutators on Main
│   ├── PlayerService.kt         # MediaSessionService foreground + advance wiring
│   └── AutoAdvancer.kt          # Pure state machine for queue auto-advance
├── api/
│   ├── ApiServer.kt             # Ktor CIO routes (byte-compat with Rust daemon)
│   ├── ApiDtos.kt               # Request/response @Serializable DTOs
│   └── ApiService.kt            # LifecycleService foreground hosting Ktor + mDNS
├── net/
│   └── LanAddresses.kt          # Non-loopback IPv4 enumeration for first-run UI
└── ui/
    ├── NavGraph.kt              # navigation-compose routes
    └── screens/
        ├── FirstRunScreen.kt    # LAN IP/port + PIN status + OK
        ├── HomeScreen.kt        # PlayerView + D-pad-UP overlay
        └── SettingsScreen.kt    # Piped URL / Quality / PIN / Test
```

JVM unit tests in `app/src/test/`, Room instrumentation tests in `app/src/androidTest/`.

---

## Dependency injection — `AppContainer`

There is no Hilt. The dependency graph is small enough to wire by hand; `AppContainer` is a `class` constructed in `GrodTvApp.onCreate()` and reachable from any `Context` via the `Context.appContainer` extension property.

```kotlin
class AppContainer(private val appContext: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())
    val settingsStore: SettingsStore = appContext.settingsStore()
    val settings: StateFlow<Settings>      // stateIn(appScope, Eagerly, Default)
    val httpClient: OkHttpClient
    val pipedClient: StateFlow<PipedClient>  // re-derived when pipedApiUrl changes
    val queueRepository: QueueRepository
    val playerController: PlayerController by lazy { … }  // forces main-thread creation
}
```

Two things to know:

1. **`playerController` is `by lazy`.** ExoPlayer must be constructed from the main thread (it captures the current `Looper`). `MainActivity.onCreate` touches the property before starting any service so the lazy initialisation lands on the UI thread, not on the foreground service's worker.
2. **`pipedClient` is a `StateFlow<PipedClient>`.** When the user changes the Piped API URL in Settings, the upstream `settings` flow emits, the `map { url -> PipedClient(url) }` derives a fresh client, and `pipedClient.value` snaps to the new instance. Existing in-flight requests on the old client are not cancelled — they complete against the old URL. This is fine because URL changes are user-initiated and rare.

---

## Player pipeline

```
ResolvedVideo (videoUrl?, audioUrl?, streamUrl?)
   │
   ▼
PlayerController.load(video)         # withContext(Dispatchers.Main)
   │
   ├── if hasMergingPair()
   │     ┌─ ProgressiveMediaSource(videoUrl) ──┐
   │     └─ ProgressiveMediaSource(audioUrl) ──┴─► MergingMediaSource ──► ExoPlayer
   │
   └── else
         └─ ProgressiveMediaSource(streamUrl) ──► ExoPlayer
```

All `ProgressiveMediaSource.Factory` instances share a single `OkHttpDataSource.Factory(httpClient)`. **`DefaultHttpDataSource` is forbidden** — see "Critical gotchas" below.

`Player.Listener` callbacks are registered in `PlayerController.init`. They translate `STATE_*` codes + `isPlaying` into a `PlaybackPhase` enum and update a `MutableStateFlow<PlaybackState>` that the UI and API both observe. The flow is the single source of truth for "what's the player doing right now"; the `playerController.state.value` snapshot is what `/status` reads.

---

## Auto-advance

`AutoAdvancer` is a pure suspend state machine that depends only on functional seams (no `Context`, no `PlayerService`):

```kotlin
class AutoAdvancer(
    val popHead: suspend () -> QueueItem?,
    val clearNowPlaying: suspend () -> Unit,
    val setNowPlaying: suspend (videoId, title) -> Unit,
    val resolve: suspend (videoId, quality) -> Result<ResolvedVideo>,
    val load: suspend (ResolvedVideo) -> Unit,
    val stop: suspend () -> Unit,
    val currentQuality: () -> Quality,
)
```

`PlayerService.onCreate` constructs an `AutoAdvancer` with the real lambdas, and registers `setOnEnded { scope.launch { advancer.advance() } }`. `Player.STATE_ENDED` fires when `MergingMediaSource` runs to the end of either constituent source, which is what we want.

Contract:

1. `popHead()` returns the next queue item, or `null`.
2. If `null`: `clearNowPlaying() ; stop() ; return` (this is the fix that resolves the "position lingers at last frame" bug).
3. If non-null: `resolve(videoId, currentQuality())`. On success: `load(video) ; setNowPlaying(id, title)`. On failure: recurse to skip the bad item.

`AutoAdvancerTest` exercises all branches with fakes, including the live-quality-read invariant and the popHead-called-once guarantee.

---

## HTTP API

Ktor's `embeddedServer(CIO, port = 7878, host = "0.0.0.0")` is started by `ApiService.onCreate` and stopped in `onDestroy`. CIO is chosen over Netty for the smaller dex footprint; CORS + ContentNegotiation(JSON) are installed.

The PIN check is a small extension on `ApplicationCall`:

```kotlin
private fun ApplicationCall.authOk(pin: String): Boolean {
    if (pin.isEmpty()) return true
    return request.headers["X-Grod-Pin"].orEmpty() == pin
}
```

Each handler short-circuits with `401` before doing real work if `!call.authOk(pin)`. `/ping` is the only public endpoint.

`ApiService` also holds the `NsdManager` registration and the `WifiManager.MulticastLock` for mDNS — Android requires the multicast lock on Wi-Fi for non-system apps to receive (and reliably announce) multicast packets.

---

## Critical gotchas

These cost real debugging time during Phases 1–4. Memorize them.

### 1. `OkHttpDataSource`, not `DefaultHttpDataSource`

`DefaultHttpDataSource` drops Cloudflare-fronted googlevideo connections during `FragmentedMp4Extractor.sniff()` with `ProtocolException: unexpected end of stream`. Reproduces every time on the API 36 TV emulator against any Cloudflare-fronted Piped proxy serving itag 137 URLs that return HTTP 206 fine from `curl` on the host. Always use:

```kotlin
val httpFactory = OkHttpDataSource.Factory(okHttpClient).setUserAgent("grod_tv/0.0.1")
```

### 2. ExoPlayer mutators on `Dispatchers.Main`

ExoPlayer methods called off the player's Looper throw `IllegalStateException`. Worse: Ktor's CIO uses a worker pool, so handler coroutines never run on Main by default. The exception is **silently swallowed** because SLF4J no-op is the only logger on the classpath — you get a 500 with an empty body and nothing in logcat.

Every `PlayerController` mutator wraps its body in `withContext(Dispatchers.Main)`:

```kotlin
suspend fun togglePlayPause() = withContext(Dispatchers.Main) {
    if (player.isPlaying) player.pause() else player.play()
}
```

If you add a new mutator, wrap it. If you call `player.*` directly from somewhere else, you are about to get a 500.

### 3. AV1 codec filter

Piped sometimes serves AV1-only (`itag 401`, codec `av01.*`) as the only high-quality video stream. Most TV decoders can't be relied on for AV1, and there is no transcode step here, so `PipedClient.pickStreamsForQuality` filters to `codec.startsWith("avc1") || startsWith("avc3") || startsWith("h264")`. **This is a deliberate divergence from the Rust daemon**, which is encoder-agnostic because libx264 transcodes downstream.

### 4. mDNS unverifiable on emulator

The Android TV emulator runs on QEMU NAT, which blocks LAN multicast. You cannot test `_grod._tcp.local.` discovery from the host with `dns-sd` or `avahi-browse` against the emulator. Use a real device (or two emulators on a bridged network) for mDNS verification.

### 5. 1-based ↔ 0-based positions

Wire protocol (mirroring Rust `queue.push() -> pos`) uses **1-based** positions. Room schema uses **0-based** `position` columns. The translation happens once at the `QueueRepository` boundary; never let a 0-based position leak into a DTO or vice versa.

---

## Testing

| Layer                       | Where                                                 | Notes                                                |
| --------------------------- | ----------------------------------------------------- | ---------------------------------------------------- |
| `PipedClient` JSON parsing  | `app/src/test/.../piped/PipedClientTest.kt`           | 15 cases against captured fixtures in `resources/`   |
| `AutoAdvancer` state machine | `app/src/test/.../player/AutoAdvancerTest.kt`        | 6 cases with functional fakes                        |
| `QueueDao` Room queries     | `app/src/androidTest/.../queue/QueueDaoTest.kt`       | 8 cases against `buildInMemory()`                    |

The repo's testing policy: integration tests must hit a real database, not mocks. The in-memory Room variant still exercises codegen + SQLite; only durability differs. (See [memory `[[feedback-grod-hls-chromecast]]`](#) for context on why mocks were banned for the Rust side; the same logic applies to Room.)

Run:

```bash
./gradlew :app:testDebugUnitTest                # JVM
./gradlew :app:connectedDebugAndroidTest        # needs adb device/emulator
```

The instrumentation runner is `androidx.test.runner.AndroidJUnitRunner` (the Instrumentation class), **not** `AndroidJUnit4` (the JUnit runner annotation). Mixing them produces `InstantiationException: has no zero argument constructor` at test start.

---

## Foreground services

Two services run for the app's lifetime:

| Service          | Class                                    | Purpose                                                              |
| ---------------- | ---------------------------------------- | -------------------------------------------------------------------- |
| `PlayerService`  | extends `MediaSessionService`            | Hosts the `MediaSession`, owns the `AutoAdvancer`, survives backgrounding |
| `ApiService`     | extends `LifecycleService`               | Hosts the Ktor server + `NsdManager` registration + `MulticastLock`  |

Both declare `android:foregroundServiceType="mediaPlayback"` in the manifest and call `startForeground` with a `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` notification. The dual `mediaPlayback` type lets the API service share the same notification category — Android doesn't grant a non-media foreground type to a Ktor server, so we ride the media coat-tails. This is **OK** because the API service exists specifically to control media playback; it's not a separate background-fetcher.

`PlayerService.onTaskRemoved` deliberately does **not** stop the service — leaving the launcher must not kill audio playback (the podcast use case).

---

## References

- High-level rationale: [`design.md`](design.md)
- Wire protocol: [`api.md`](api.md)
- Build setup: [`build.md`](build.md)
- Emulator quirks: [`emulator.md`](emulator.md)
- Settings reference: [`settings.md`](settings.md)
