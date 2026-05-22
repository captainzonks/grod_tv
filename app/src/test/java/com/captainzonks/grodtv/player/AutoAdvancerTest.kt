package com.captainzonks.grodtv.player

import com.captainzonks.grodtv.piped.Quality
import com.captainzonks.grodtv.piped.ResolvedVideo
import com.captainzonks.grodtv.queue.QueueItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdvancerTest {

    private fun resolved(id: String, title: String = id) = ResolvedVideo(
        id = id,
        title = title,
        videoUrl = "https://video/$id",
        audioUrl = "https://audio/$id",
        qualityLabel = "1080p",
        streamUrl = null,
        durationSecs = 30L,
    )

    @Test
    fun `empty queue calls clearNowPlaying and stop, never resolves`() = runTest {
        val log = mutableListOf<String>()
        val advancer = AutoAdvancer(
            popHead = { null },
            clearNowPlaying = { log += "clear" },
            setNowPlaying = { _, _ -> log += "setNP" },
            resolve = { _, _ -> error("must not resolve when queue is empty") },
            load = { log += "load" },
            stop = { log += "stop" },
            currentQuality = { Quality.P1080 },
        )

        advancer.advance()

        assertEquals(listOf("clear", "stop"), log)
    }

    @Test
    fun `single item happy path resolves, loads, sets now-playing`() = runTest {
        val items = ArrayDeque(listOf(QueueItem(pos = 1, videoId = "abc", title = "Track A")))
        val log = mutableListOf<String>()
        val advancer = AutoAdvancer(
            popHead = { items.removeFirstOrNull() },
            clearNowPlaying = { log += "clear" },
            setNowPlaying = { id, title -> log += "setNP:$id:$title" },
            resolve = { id, _ -> Result.success(resolved(id, "Resolved $id")) },
            load = { v -> log += "load:${v.id}" },
            stop = { log += "stop" },
            currentQuality = { Quality.P1080 },
        )

        advancer.advance()

        assertEquals(listOf("load:abc", "setNP:abc:Resolved abc"), log)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `resolve failure skips to next track`() = runTest {
        val items = ArrayDeque(listOf(
            QueueItem(pos = 1, videoId = "bad", title = "Bad"),
            QueueItem(pos = 2, videoId = "good", title = "Good"),
        ))
        val log = mutableListOf<String>()
        val advancer = AutoAdvancer(
            popHead = { items.removeFirstOrNull() },
            clearNowPlaying = { log += "clear" },
            setNowPlaying = { id, _ -> log += "setNP:$id" },
            resolve = { id, _ ->
                if (id == "bad") Result.failure(RuntimeException("nope"))
                else Result.success(resolved(id))
            },
            load = { v -> log += "load:${v.id}" },
            stop = { log += "stop" },
            currentQuality = { Quality.P1080 },
        )

        advancer.advance()

        assertEquals(listOf("load:good", "setNP:good"), log)
        assertTrue("queue drained", items.isEmpty())
    }

    @Test
    fun `all resolves fail and queue drains then clears + stops`() = runTest {
        val items = ArrayDeque(listOf(
            QueueItem(pos = 1, videoId = "x", title = "X"),
            QueueItem(pos = 2, videoId = "y", title = "Y"),
        ))
        val log = mutableListOf<String>()
        val advancer = AutoAdvancer(
            popHead = { items.removeFirstOrNull() },
            clearNowPlaying = { log += "clear" },
            setNowPlaying = { _, _ -> log += "setNP" },
            resolve = { _, _ -> Result.failure(RuntimeException("fail")) },
            load = { log += "load" },
            stop = { log += "stop" },
            currentQuality = { Quality.P1080 },
        )

        advancer.advance()

        assertEquals(listOf("clear", "stop"), log)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `currentQuality is read each advance to honour live settings change`() = runTest {
        var quality = Quality.P360
        val seen = mutableListOf<Quality>()
        val items = ArrayDeque(listOf(QueueItem(pos = 1, videoId = "id", title = "T")))
        val advancer = AutoAdvancer(
            popHead = { items.removeFirstOrNull() },
            clearNowPlaying = {},
            setNowPlaying = { _, _ -> },
            resolve = { _, q -> seen += q; Result.success(resolved("id")) },
            load = { },
            stop = { },
            currentQuality = { quality },
        )

        advancer.advance()
        assertEquals(listOf(Quality.P360), seen)

        // Refill, change quality, advance again.
        items.addLast(QueueItem(pos = 1, videoId = "id2", title = "T2"))
        quality = Quality.Best
        advancer.advance()
        assertEquals(listOf(Quality.P360, Quality.Best), seen)
    }

    @Test
    fun `popHead is called once per item, no extra polls`() = runTest {
        var pops = 0
        val items = ArrayDeque(listOf(QueueItem(pos = 1, videoId = "id", title = "T")))
        val advancer = AutoAdvancer(
            popHead = { pops++; items.removeFirstOrNull() },
            clearNowPlaying = { },
            setNowPlaying = { _, _ -> },
            resolve = { _, _ -> Result.success(resolved("id")) },
            load = { },
            stop = { },
            currentQuality = { Quality.P1080 },
        )

        advancer.advance()

        assertEquals(1, pops)
        assertNull(items.firstOrNull())
    }
}
