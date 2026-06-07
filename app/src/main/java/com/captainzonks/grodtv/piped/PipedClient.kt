package com.captainzonks.grodtv.piped

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class PipedClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val base: String = baseUrl.trimEnd('/')

    suspend fun resolve(videoId: String, quality: Quality): Result<ResolvedVideo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = getJson<StreamsResponse>("$base/streams/$videoId")
                val pair = pickStreamsForQuality(resp.videoStreams, resp.audioStreams, quality)
                val fallback = pickMuxedFallback(resp.videoStreams)
                if (pair == null && fallback == null) {
                    error("no suitable stream found for $videoId")
                }
                ResolvedVideo(
                    id = videoId,
                    title = resp.title,
                    videoUrl = pair?.videoUrl,
                    audioUrl = pair?.audioUrl,
                    qualityLabel = pair?.label ?: "360p",
                    streamUrl = fallback,
                    durationSecs = resp.duration,
                )
            }
        }

    suspend fun search(query: String): Result<List<SearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$base/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("filter", "videos")
                    .build()
                val resp = getJson<SearchResponse>(url.toString())
                resp.items
                    .filter { it.type == "stream" }
                    .map {
                        SearchResult(
                            url = it.url,
                            title = it.title,
                            uploader = it.uploaderName,
                            duration = it.duration,
                            thumbnail = it.thumbnail,
                        )
                    }
            }
        }

    suspend fun title(videoId: String): Result<String> = resolve(videoId, Quality.P360).map { it.title }

    private inline fun <reified T> getJson(url: String): T {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GET $url -> ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty body from $url")
            return PipedJson.decodeFromString(body)
        }
    }
}

internal data class PickedPair(val videoUrl: String, val audioUrl: String, val label: String)

internal fun pickStreamsForQuality(
    videos: List<VideoStreamDto>,
    audios: List<AudioStreamDto>,
    quality: Quality,
): PickedPair? {
    val target = quality.targetHeight

    // Video-only stream, height in (0, target]. Accept H.264 (mp4), VP9 and
    // AV1 (webm/mp4) — YouTube serves nothing above 1080p as H.264, so the
    // 1440p/2160p tiers depend on VP9/AV1. ExoPlayer decodes all three
    // natively, so this is a pure source-selection change (no transcode).
    //
    // Pick the tallest stream within the cap; for ties (same height in
    // multiple codecs) prefer the most efficient codec — AV1 > VP9 > H.264 —
    // since the smaller stream buffers faster over the LAN at equal quality.
    val video = videos
        .filter { v -> v.videoOnly && v.height in 1..target && isPlayableVideo(v) }
        .maxWithOrNull(
            compareBy<VideoStreamDto> { it.height }.thenBy { codecRank(it.codec) }
        )
        ?: return null

    // M4A audio-only. Prefer ORIGINAL track type, then no-track, then anything.
    // Tie-break by bitrate descending.
    val audio = audios
        .filter { a -> a.mimeType == "audio/mp4" && a.format == "M4A" }
        .maxWithOrNull(
            compareBy<AudioStreamDto> { audioTrackTier(it.audioTrackType) }
                .thenBy { it.bitrate }
        )
        ?: return null

    val label = video.quality ?: "${video.height}p"
    return PickedPair(video.url, audio.url, label)
}

internal fun pickMuxedFallback(videos: List<VideoStreamDto>): String? {
    videos.firstOrNull { !it.videoOnly && it.mimeType == "video/mp4" }?.let { return it.url }
    videos.firstOrNull { it.mimeType == "application/x-mpegurl" }?.let { return it.url }
    return null
}

/**
 * Whether a video-only stream is one ExoPlayer can play in a MergingMediaSource.
 * Covers the three codecs YouTube ships for adaptive video: H.264 (mp4), VP9
 * and AV1 (both usually `video/webm`, AV1 sometimes `video/mp4`). The container
 * is left to ExoPlayer's extractor — what matters is the codec being decodable.
 */
private fun isPlayableVideo(v: VideoStreamDto): Boolean =
    v.mimeType.startsWith("video/") &&
        (isAvcCodec(v.codec) || isVp9Codec(v.codec) || isAv1Codec(v.codec))

/** Codec preference for tie-breaks at equal height. The picker uses
 *  `maxWithOrNull`, which takes the *largest* value, so the most
 *  bandwidth-efficient codec gets the highest rank: AV1 (3) > VP9 (2) >
 *  H.264 (1) > unknown (0). At equal height the smaller, modern-codec stream
 *  wins, buffering faster over the LAN at equal visual quality. */
private fun codecRank(codec: String?): Int = when {
    isAv1Codec(codec) -> 3
    isVp9Codec(codec) -> 2
    isAvcCodec(codec) -> 1
    else -> 0
}

private fun isAvcCodec(codec: String?): Boolean {
    val c = codec?.lowercase() ?: return false
    return c.startsWith("avc1") || c.startsWith("avc3") || c.startsWith("h264")
}

private fun isVp9Codec(codec: String?): Boolean {
    val c = codec?.lowercase() ?: return false
    return c.startsWith("vp9") || c.startsWith("vp09")
}

private fun isAv1Codec(codec: String?): Boolean {
    val c = codec?.lowercase() ?: return false
    return c.startsWith("av01") || c.startsWith("av1")
}

private fun audioTrackTier(type: String?): Int = when (type?.uppercase()) {
    "ORIGINAL" -> 2
    null -> 1
    else -> 0
}
