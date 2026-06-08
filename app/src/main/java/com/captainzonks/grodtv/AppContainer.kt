package com.captainzonks.grodtv

import android.content.Context
import com.captainzonks.grodtv.piped.PipedClient
import com.captainzonks.grodtv.piped.VideoCodecSupport
import com.captainzonks.grodtv.player.PlayerController
import com.captainzonks.grodtv.queue.GrodTvDatabase
import com.captainzonks.grodtv.queue.QueueRepository
import com.captainzonks.grodtv.settings.Settings
import com.captainzonks.grodtv.settings.SettingsStore
import com.captainzonks.grodtv.settings.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient

class AppContainer(private val appContext: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    val settingsStore: SettingsStore = appContext.settingsStore()

    val settings: StateFlow<Settings> = settingsStore.flow
        .stateIn(appScope, SharingStarted.Eagerly, Settings.Default)

    val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    /** Video codecs this device's hardware/software can decode. Detected once;
     *  passed to every PipedClient so the stream picker skips codecs with no
     *  decoder (e.g. AV1 on a Tegra X1 Shield). */
    private val decodableCodecs = VideoCodecSupport.detect()

    val pipedClient: StateFlow<PipedClient> = settings
        .map { it.pipedApiUrl }
        .distinctUntilChanged()
        .map { url -> PipedClient(baseUrl = url, http = httpClient, decodableCodecs = decodableCodecs) }
        .stateIn(
            appScope,
            SharingStarted.Eagerly,
            PipedClient(Settings.Default.pipedApiUrl, httpClient, decodableCodecs),
        )

    private val database: GrodTvDatabase = GrodTvDatabase.build(appContext)

    val queueRepository: QueueRepository = QueueRepository(
        queue = database.queueDao(),
        nowPlaying = database.nowPlayingDao(),
    )

    /** Lazily-created singleton — main-thread ExoPlayer creation requires Looper. */
    val playerController: PlayerController by lazy {
        PlayerController(appContext, httpClient)
    }
}

class GrodTvApp : android.app.Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as GrodTvApp).container
