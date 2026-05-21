package com.captainzonks.grodtv.queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class QueueItem(
    val pos: Int,
    val videoId: String,
    val title: String,
)

data class NowPlaying(
    val videoId: String,
    val title: String,
)

class QueueRepository(
    private val queue: QueueDao,
    private val nowPlaying: NowPlayingDao,
) {
    val items: Flow<List<QueueItem>> = queue.observeAll().map { rows ->
        rows.map { QueueItem(pos = it.position + 1, videoId = it.videoId, title = it.title) }
    }

    val current: Flow<NowPlaying?> = nowPlaying.observe().map { row ->
        row?.let { NowPlaying(videoId = it.videoId, title = it.title) }
    }

    suspend fun list(): List<QueueItem> = queue.list().map {
        QueueItem(pos = it.position + 1, videoId = it.videoId, title = it.title)
    }

    suspend fun size(): Int = queue.size()

    /** Append video; returns 1-based position. */
    suspend fun push(videoId: String, title: String): Int =
        queue.pushAtEnd(videoId = videoId, title = title)

    /** Pop head (FIFO). Null when empty. */
    suspend fun popHead(): QueueItem? = queue.popHead()?.let {
        QueueItem(pos = it.position + 1, videoId = it.videoId, title = it.title)
    }

    /** Remove by 1-based position. Null when out of range. */
    suspend fun removeAt(pos1: Int): QueueItem? = queue.removeAt1Based(pos1)?.let {
        QueueItem(pos = it.position + 1, videoId = it.videoId, title = it.title)
    }

    suspend fun clear() = queue.clear()

    suspend fun setNowPlaying(videoId: String, title: String) =
        nowPlaying.set(NowPlayingEntity(videoId = videoId, title = title))

    suspend fun clearNowPlaying() = nowPlaying.clear()

    suspend fun getNowPlaying(): NowPlaying? = nowPlaying.get()?.let {
        NowPlaying(videoId = it.videoId, title = it.title)
    }
}
