package com.captainzonks.grodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.captainzonks.grodtv.piped.PipedClient
import com.captainzonks.grodtv.piped.Quality
import com.captainzonks.grodtv.piped.ResolvedVideo
import com.captainzonks.grodtv.settings.Settings
import com.captainzonks.grodtv.settings.settingsStore

// Hardcoded test video. Real flow comes in Phase 3+ (queue + remote API).
private const val TEST_VIDEO_ID = "dQw4w9WgXcQ"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                PlaybackRoot(TEST_VIDEO_ID)
            }
        }
    }
}

private sealed interface PlayerUiState {
    object Loading : PlayerUiState
    data class Error(val message: String) : PlayerUiState
    data class Ready(val resolved: ResolvedVideo) : PlayerUiState
}

@Composable
private fun PlaybackRoot(videoId: String) {
    val context = LocalContext.current
    val settings by context.settingsStore().flow.collectAsState(initial = Settings.Default)
    var state by remember { mutableStateOf<PlayerUiState>(PlayerUiState.Loading) }

    LaunchedEffect(videoId, settings.pipedApiUrl, settings.defaultQuality) {
        state = PlayerUiState.Loading
        val client = PipedClient(settings.pipedApiUrl)
        client.resolve(videoId, settings.defaultQuality)
            .onSuccess { state = PlayerUiState.Ready(it) }
            .onFailure { state = PlayerUiState.Error(it.message ?: it::class.simpleName ?: "unknown") }
    }

    when (val s = state) {
        is PlayerUiState.Loading -> CenteredText("Resolving…")
        is PlayerUiState.Error -> CenteredText("Resolve failed: ${s.message}")
        is PlayerUiState.Ready -> PlayerSurface(s.resolved)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

@Composable
private fun PlayerSurface(resolved: ResolvedVideo) {
    val context = LocalContext.current

    val exoPlayer = remember(resolved.id) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("grod_tv/0.0.1")
            .setAllowCrossProtocolRedirects(true)

        val player = ExoPlayer.Builder(context).build()

        val source = if (resolved.hasMergingPair()) {
            val v = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(resolved.videoUrl!!))
            val a = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(resolved.audioUrl!!))
            MergingMediaSource(v, a)
        } else {
            ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(resolved.streamUrl!!))
        }

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player
    }

    DisposableEffect(resolved.id) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
    )
}
