package com.captainzonks.grodtv

import android.content.Context
import com.captainzonks.grodtv.piped.PipedClient
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

class AppContainer(context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    val settingsStore: SettingsStore = context.settingsStore()

    val settings: StateFlow<Settings> = settingsStore.flow
        .stateIn(appScope, SharingStarted.Eagerly, Settings.Default)

    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    val pipedClient: StateFlow<PipedClient> = settings
        .map { it.pipedApiUrl }
        .distinctUntilChanged()
        .map { url -> PipedClient(baseUrl = url, http = httpClient) }
        .stateIn(appScope, SharingStarted.Eagerly, PipedClient(Settings.Default.pipedApiUrl, httpClient))

    private val database: GrodTvDatabase = GrodTvDatabase.build(context)

    val queueRepository: QueueRepository = QueueRepository(
        queue = database.queueDao(),
        nowPlaying = database.nowPlayingDao(),
    )
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
