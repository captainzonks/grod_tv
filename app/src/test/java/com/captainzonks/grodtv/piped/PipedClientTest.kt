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
    fun `pick at Best resolves the highest available stream (2160p)`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.Best)
        assertNotNull("expected a pair", pair)
        // Fixture tops out at 2160p (VP9 + AV1). Best is now unbounded, so it
        // must pick 2160p rather than the old hardcoded 1080p ceiling.
        assertEquals("2160p", pair!!.label)
        assertTrue("video URL set", pair.videoUrl.isNotBlank())
        assertTrue("audio URL set", pair.audioUrl.isNotBlank())
    }

    @Test
    fun `Best prefers AV1 over VP9 at the same height`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.Best)!!
        // Both VP9 and AV1 exist at 2160p; AV1 (av01) is more bandwidth-efficient
        // and must win the codec tie-break.
        val chosen = r.videoStreams.first { it.url == pair.videoUrl }
        assertTrue(
            "expected AV1 at 2160p, got ${chosen.codec}",
            chosen.codec?.startsWith("av01") == true,
        )
    }

    @Test
    fun `device without AV1 decoder picks VP9 at the same height instead`() {
        val r = parseStreams("streams_rickroll.json")
        // Shield (Tegra X1) reports {AVC, VP9} — no AV1. At 2160p the fixture
        // has both VP9 and AV1; the picker must skip AV1 and choose VP9 so the
        // device actually decodes video (regression: AV1 = black screen).
        val shield = setOf(VideoCodec.AVC, VideoCodec.VP9)
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.Best, shield)!!
        val chosen = r.videoStreams.first { it.url == pair.videoUrl }
        assertEquals("still 2160p", "2160p", pair.label)
        assertTrue(
            "expected VP9 (no AV1 decoder), got ${chosen.codec}",
            chosen.codec?.startsWith("vp9") == true || chosen.codec?.startsWith("vp09") == true,
        )
    }

    @Test
    fun `device with only H264 decoder falls back to AVC`() {
        val r = parseStreams("streams_rickroll.json")
        // Degenerate floor: a device advertising only AVC must never select a
        // VP9/AV1 stream. It caps at the tallest H.264 ladder rung (<=1080p).
        val avcOnly = setOf(VideoCodec.AVC)
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.Best, avcOnly)!!
        val chosen = r.videoStreams.first { it.url == pair.videoUrl }
        assertTrue(
            "expected H.264, got ${chosen.codec}",
            chosen.codec?.startsWith("avc") == true || chosen.codec?.startsWith("h264") == true,
        )
        assertTrue("H.264 tops out at 1080p", chosen.height <= 1080)
    }

    @Test
    fun `empty codec support yields no playable video stream`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.Best, emptySet())
        assertNull("no decodable codec => no pair", pair)
    }

    @Test
    fun `pick at 2160p picks 2160p VP9 or AV1`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P2160)!!
        assertEquals("2160p", pair.label)
    }

    @Test
    fun `pick at 1440p picks 1440p`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P1440)!!
        assertEquals("1440p", pair.label)
    }

    @Test
    fun `pick at 1080p still resolves 1080p (caps below VP9-only tiers)`() {
        val r = parseStreams("streams_rickroll.json")
        val pair = pickStreamsForQuality(r.videoStreams, r.audioStreams, Quality.P1080)!!
        assertEquals("1080p", pair.label)
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
        // Best is now unbounded so "best" means the single highest stream.
        assertEquals(Int.MAX_VALUE, Quality.Best.targetHeight)
        assertEquals(2160, Quality.P2160.targetHeight)
        assertEquals(1440, Quality.P1440.targetHeight)
        assertEquals(1080, Quality.P1080.targetHeight)
        assertEquals(720, Quality.P720.targetHeight)
        assertEquals(480, Quality.P480.targetHeight)
        assertEquals(360, Quality.P360.targetHeight)
    }

    @Test
    fun `Quality parse 4k aliases`() {
        assertEquals(Quality.P2160, Quality.parse("4k"))
        assertEquals(Quality.P2160, Quality.parse("2160"))
        assertEquals(Quality.P1440, Quality.parse("2k"))
        assertEquals(Quality.P1440, Quality.parse("1440"))
    }
}
