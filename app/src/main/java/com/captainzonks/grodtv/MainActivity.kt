package com.captainzonks.grodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.captainzonks.grodtv.api.ApiService
import com.captainzonks.grodtv.player.PlaybackPhase
import com.captainzonks.grodtv.player.PlayerService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Touch the player controller so ExoPlayer is created on the main thread
        // before any service binds to it.
        appContainer.playerController

        PlayerService.start(this)
        ApiService.start(this)

        setContent {
            HomeScreen()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val container = remember { context.appContainer }

    val playbackState by container.playerController.state.collectAsState()
    val queue by container.queueRepository.items.collectAsState(initial = emptyList())
    val nowPlaying by container.queueRepository.current.collectAsState(initial = null)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Player surface fills the screen.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = container.playerController.player
                    useController = true
                }
            },
        )

        // Overlay status when nothing is playing.
        if (playbackState.phase == PlaybackPhase.Idle && nowPlaying == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("grod_tv ready", color = Color.White)
                Text(
                    "POST /cast {url=…} on port 7878 to start",
                    color = Color.White,
                )
            }
        }

        // Tiny queue overlay top-right when queue is non-empty.
        if (queue.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(16.dp),
            ) {
                Text("Queue (${queue.size})", color = Color.White)
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(queue, key = { it.pos }) { item ->
                        Text("${item.pos}. ${item.title}", color = Color.White)
                    }
                }
            }
        }
    }
}
