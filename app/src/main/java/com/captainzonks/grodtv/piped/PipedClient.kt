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

    // Video-only mp4 with H.264 codec, height in (0, target]. Pick max height.
    val video = videos
        .filter { v ->
            v.videoOnly &&
                v.height in 1..target &&
                v.mimeType == "video/mp4" &&
                v.format == "MPEG_4" &&
                isAvcCodec(v.codec)
        }
        .maxByOrNull { it.height }
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

private fun isAvcCodec(codec: String?): Boolean {
    if (codec == null) return false
    val c = codec.lowercase()
    return c.startsWith("avc1") || c.startsWith("avc3") || c.startsWith("h264")
}

private fun audioTrackTier(type: String?): Int = when (type?.uppercase()) {
    "ORIGINAL" -> 2
    null -> 1
    else -> 0
}
