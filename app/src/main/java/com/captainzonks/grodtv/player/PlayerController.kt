package com.captainzonks.grodtv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.captainzonks.grodtv.piped.ResolvedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

enum class PlaybackPhase { Idle, Buffering, Playing, Paused, Ended, Error }

data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val nowPlayingVideoId: String? = null,
    val nowPlayingTitle: String? = null,
    val qualityLabel: String? = null,
    val durationSecs: Long? = null,
    val positionSecs: Long? = null,
)

interface OnEndedCallback {
    fun onEnded()
}

class PlayerController(
    context: Context,
    okHttpClient: OkHttpClient,
) {
    private val httpFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent("grod_tv/0.0.1")

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var onEnded: OnEndedCallback? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                refreshState(playbackState = state)
                if (state == Player.STATE_ENDED) onEnded?.onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.value = _state.value.copy(phase = PlaybackPhase.Error)
            }
        })
    }

    fun setOnEnded(cb: OnEndedCallback?) {
        onEnded = cb
    }

    suspend fun load(video: ResolvedVideo) = withContext(Dispatchers.Main) {
        val source: MediaSource = if (video.hasMergingPair()) {
            val v = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(video.videoUrl!!))
            val a = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(video.audioUrl!!))
            MergingMediaSource(v, a)
        } else {
            val url = video.streamUrl ?: error("ResolvedVideo has no playable URL")
            ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true

        _state.value = _state.value.copy(
            nowPlayingVideoId = video.id,
            nowPlayingTitle = video.title,
            qualityLabel = video.qualityLabel,
            durationSecs = video.durationSecs.takeIf { it > 0L },
            positionSecs = 0L,
        )
    }

    suspend fun togglePlayPause() = withContext(Dispatchers.Main) {
        if (player.isPlaying) player.pause() else player.play()
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
    }

    suspend fun seekForward(secs: Long) = withContext(Dispatchers.Main) {
        player.seekTo((player.currentPosition + secs * 1000L).coerceAtLeast(0L))
    }

    suspend fun seekBack(secs: Long) = withContext(Dispatchers.Main) {
        player.seekTo((player.currentPosition - secs * 1000L).coerceAtLeast(0L))
    }

    private suspend fun setVolume(v: Float) = withContext(Dispatchers.Main) {
        player.volume = v.coerceIn(0f, 1f)
    }
    suspend fun volumeUp(step: Float = 0.05f) = withContext(Dispatchers.Main) {
        player.volume = (player.volume + step).coerceIn(0f, 1f)
    }
    suspend fun volumeDown(step: Float = 0.05f) = withContext(Dispatchers.Main) {
        player.volume = (player.volume - step).coerceIn(0f, 1f)
    }
    suspend fun mute() = withContext(Dispatchers.Main) { player.volume = 0f }
    suspend fun unmute() = withContext(Dispatchers.Main) { player.volume = 1f }

    /** Snapshot of position in seconds, or null when no media loaded. Must run on main thread. */
    suspend fun currentPositionSecs(): Long? = withContext(Dispatchers.Main) {
        val pos = player.currentPosition
        if (pos < 0) null else pos / 1000L
    }

    fun isOccupied(): Boolean = when (_state.value.phase) {
        PlaybackPhase.Playing, PlaybackPhase.Paused, PlaybackPhase.Buffering -> true
        else -> false
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        player.release()
    }

    private fun refreshState(playbackState: Int = player.playbackState) {
        val phase = when {
            playbackState == Player.STATE_BUFFERING -> PlaybackPhase.Buffering
            playbackState == Player.STATE_ENDED -> PlaybackPhase.Ended
            playbackState == Player.STATE_IDLE -> PlaybackPhase.Idle
            player.isPlaying -> PlaybackPhase.Playing
            else -> PlaybackPhase.Paused
        }
        val pos = player.currentPosition.let { if (it < 0L) null else it / 1000L }
        _state.value = _state.value.copy(phase = phase, positionSecs = pos)
    }
}
