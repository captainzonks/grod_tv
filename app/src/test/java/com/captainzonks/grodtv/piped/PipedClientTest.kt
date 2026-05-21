package com.captainzonks.grodtv.piped

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipedClientTest {

    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture $name not found on classpath"
        }.bufferedReader().use { it.readText() }

    private fun parseStreams(name: String): StreamsResponse =
        PipedJson.decodeFromString(loadFixture(name))

    private fun parseSearch(name: String): SearchResponse =
        PipedJson.decodeFromString(loadFixture(name))

    // ---------- parsing ----------

    @Test
    fun `streams response parses`() {
        val r = parseStreams("streams_rickroll.json")
        assertEquals("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", r.title)
        assertEquals(213L, r.duration)
        assertTrue(r.videoStreams.isNotEmpty())
        assertTrue(r.audioStreams.isNotEmpty())
    }

    @Test
    fun `search response parses and filters non-stream items`() {
        val r = parseSearch("search_rick.json")
        val streams = r.items.filter { it.type == "stream" }
        // At least one stream-typed item expected
        assertTrue("expected stream items in fixture, got ${r.items.size}", streams.isNotEmpty())
        streams.forEach {
            assertTrue("uploaderName present", it.uploaderName.isNotBlank())
        }
    }

    // ---------- picker: quality ----------

    @Test
    fun `pick at Best caps at 1080p H264 not AV1`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.Best)
        assertNotNull("expected a pair", pair)
        assertEquals("1080p", pair!!.label)
        assertTrue("video URL set", pair.videoUrl.isNotBlank())
        assertTrue("audio URL set", pair.audioUrl.isNotBlank())
    }

    @Test
    fun `pick at 720p picks 720p`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P720)!!
        assertEquals("720p", pair.label)
    }

    @Test
    fun `pick at 480p picks 480p`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P480)!!
        assertEquals("480p", pair.label)
    }

    @Test
    fun `pick at 360p picks 360p`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P360)!!
        assertEquals("360p", pair.label)
    }

    // ---------- picker: audio track preference ----------

    @Test
    fun `ORIGINAL audio track preferred even when dubbed is higher bitrate`() {
        val r = parseStreams("streams_multitrack.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P1080)!!
        // German dub is 130 kbps; English ORIGINAL is 48 kbps. ORIGINAL must win.
        assertEquals("https://example.invalid/a_en_original.m4a", pair.audioUrl)
    }

    // ---------- fallback ----------

    @Test
    fun `muxed fallback returns itag 18-ish url when video-only filter fails`() {
        val r = parseStreams("streams_rickroll.json")
        val fb = pickMuxedFallback(r.videoStreams)
        assertNotNull("expected muxed fallback", fb)
    }

    // ---------- extractVideoId ----------

    @Test
    fun `extractVideoId raw 11 char`() {
        assertEquals("dQw4w9WgXcQ", extractVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId watch url`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `extractVideoId watch url with extra params`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"),
        )
    }

    @Test
    fun `extractVideoId short url`() {
        assertEquals("dQw4w9WgXcQ", extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId rejects junk`() {
        assertNull(extractVideoId("nope"))
        assertNull(extractVideoId(""))
        assertNull(extractVideoId("https://example.com/"))
        assertNull(extractVideoId("too-many-characters-here"))
    }

    // ---------- Quality parse ----------

    @Test
    fun `Quality parse round-trip`() {
        Quality.entries.forEach { q ->
            assertEquals(q, Quality.parse(q.label))
        }
        assertEquals(Quality.P1080, Quality.parse("1080"))
        assertEquals(Quality.Best, Quality.parse(" Best "))
        assertNull(Quality.parse("ultra"))
    }

    @Test
    fun `Quality targetHeight`() {
        assertEquals(1080, Quality.Best.targetHeight)
        assertEquals(1080, Quality.P1080.targetHeight)
        assertEquals(720, Quality.P720.targetHeight)
        assertEquals(480, Quality.P480.targetHeight)
        assertEquals(360, Quality.P360.targetHeight)
    }
}
