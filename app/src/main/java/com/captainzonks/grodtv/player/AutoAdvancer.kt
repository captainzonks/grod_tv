package com.captainzonks.grodtv.player

import com.captainzonks.grodtv.piped.Quality
import com.captainzonks.grodtv.piped.ResolvedVideo
import com.captainzonks.grodtv.queue.QueueItem

/**
 * Pure state-machine for queue auto-advance. Lives outside PlayerService so it
 * can be exercised by a JVM unit test against fakes.
 *
 * Contract: caller invokes [advance] when current track ends. We pop the head;
 * if the queue is empty we clear now-playing and stop the player. Otherwise we
 * resolve the popped video and load it; on resolve failure we skip and recurse.
 */
class AutoAdvancer(
    private val popHead: suspend () -> QueueItem?,
    private val clearNowPlaying: suspend () -> Unit,
    private val setNowPlaying: suspend (videoId: String, title: String) -> Unit,
    private val resolve: suspend (videoId: String, quality: Quality) -> Result<ResolvedVideo>,
    private val load: suspend (ResolvedVideo) -> Unit,
    private val stop: suspend () -> Unit,
    private val currentQuality: () -> Quality,
) {
    suspend fun advance() {
        val head = popHead() ?: run {
            clearNowPlaying()
            stop()
            return
        }
        resolve(head.videoId, currentQuality())
            .onSuccess { video ->
                load(video)
                setNowPlaying(video.id, video.title)
            }
            .onFailure {
                advance()
            }
    }
}
