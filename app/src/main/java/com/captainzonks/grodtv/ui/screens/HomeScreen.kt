package com.captainzonks.grodtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.captainzonks.grodtv.appContainer
import com.captainzonks.grodtv.player.PlaybackPhase
import com.captainzonks.grodtv.ui.GrodColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun grodButtonColors() = ButtonDefaults.colors(
    containerColor = GrodColors.Slate,
    contentColor = Color.White,
    focusedContainerColor = GrodColors.BrandPurpleFocused,
    focusedContentColor = Color.White,
    pressedContainerColor = GrodColors.BrandPurplePressed,
    pressedContentColor = Color.White,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }

    val playbackState by container.playerController.state.collectAsState()
    val queue by container.queueRepository.items.collectAsState(initial = emptyList())
    val nowPlaying by container.queueRepository.current.collectAsState(initial = null)

    var overlayVisible by remember { mutableStateOf(false) }

    // Focus requesters for the parts of the screen that should claim focus
    // when shown. Compose for TV does not propagate focus into AndroidView
    // backed by Media3 PlayerView on Shield, so we anchor it on our own
    // composables.
    val settingsButtonFocus = remember { FocusRequester() }
    val overlayCloseFocus = remember { FocusRequester() }

    // Idle UI = player not currently engaged. nowPlaying can linger in Room
    // after a /skip or across cold starts; gating on it would hide the
    // welcome screen and leave a black PlayerView with no Settings button.
    val idle = when (playbackState.phase) {
        PlaybackPhase.Idle, PlaybackPhase.Ended -> true
        else -> false
    }

    // Auto-focus the right element when state changes. The Compose Button is
    // not in the tree until its parent `if (idle)` / `if (overlayVisible)`
    // branch composes, so we yield one frame before requesting focus to let
    // the FocusRequester attach to the Button's node.
    LaunchedEffect(idle, overlayVisible) {
        kotlinx.coroutines.delay(50L)
        runCatching {
            when {
                overlayVisible -> overlayCloseFocus.requestFocus()
                idle -> settingsButtonFocus.requestFocus()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Capture D-pad UP before PlayerView can swallow it so overlay
            // can be opened from any focus state. Back closes overlay; if
            // the overlay is already closed, propagate so the activity can
            // fall through to its default Back handler.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> {
                        if (!overlayVisible) {
                            overlayVisible = true
                            true
                        } else {
                            false
                        }
                    }
                    Key.Back, Key.Escape -> {
                        if (overlayVisible) {
                            overlayVisible = false
                            true
                        } else {
                            false
                        }
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
                    // Disable PlayerView's own controller so it does not
                    // steal D-pad center/back from our Compose layer.
                    // Remotes drive the player through grod_remote / the
                    // HTTP API, not the on-screen PlayerView controls.
                    useController = false
                }
            },
        )

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
                Button(
                    onClick = onOpenSettings,
                    colors = grodButtonColors(),
                    modifier = Modifier.focusRequester(settingsButtonFocus),
                ) { Text("Settings") }
            }
        }

        if (overlayVisible) {
            QueueOverlay(
                nowPlayingTitle = nowPlaying?.title,
                queue = queue.map { it.pos to it.title },
                onOpenSettings = onOpenSettings,
                onDismiss = { overlayVisible = false },
                closeFocus = overlayCloseFocus,
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
    closeFocus: FocusRequester,
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
            Button(
                onClick = onOpenSettings,
                colors = grodButtonColors(),
                modifier = Modifier.focusable(),
            ) { Text("Settings") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                colors = grodButtonColors(),
                modifier = Modifier
                    .focusRequester(closeFocus)
                    .focusable(),
            ) { Text("Close") }
        }
    }
}
