package com.captainzonks.grodtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.captainzonks.grodtv.appContainer
import com.captainzonks.grodtv.player.PlaybackPhase

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }

    val playbackState by container.playerController.state.collectAsState()
    val queue by container.queueRepository.items.collectAsState(initial = emptyList())
    val nowPlaying by container.queueRepository.current.collectAsState(initial = null)

    var overlayVisible by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { overlayVisible = true; true }
                    Key.Back, Key.Escape -> {
                        if (overlayVisible) { overlayVisible = false; true } else false
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = container.playerController.player
                    useController = true
                }
            },
        )

        val idle = playbackState.phase == PlaybackPhase.Idle && nowPlaying == null
        if (idle) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("grod_tv ready", color = Color.White, fontSize = 32.sp)
                Text("POST /cast {url=…} on port 7878 to start", color = Color.LightGray)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOpenSettings) { Text("Settings") }
            }
        }

        if (overlayVisible) {
            QueueOverlay(
                nowPlayingTitle = nowPlaying?.title,
                queue = queue.map { it.pos to it.title },
                onOpenSettings = onOpenSettings,
                onDismiss = { overlayVisible = false },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QueueOverlay(
    nowPlayingTitle: String?,
    queue: List<Pair<Int, String>>,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .padding(32.dp),
    ) {
        Column(Modifier.fillMaxHeight().width(560.dp)) {
            Text("Now playing", color = Color.LightGray)
            Text(nowPlayingTitle ?: "—", color = Color.White, fontSize = 22.sp)
            Spacer(Modifier.height(16.dp))
            Text("Queue (${queue.size})", color = Color.LightGray)
            if (queue.isEmpty()) {
                Text("(empty)", color = Color.Gray)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(queue, key = { it.first }) { (pos, title) ->
                        Text("$pos. $title", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("Settings") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss) { Text("Close") }
        }
    }
}
