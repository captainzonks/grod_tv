package com.captainzonks.grodtv.piped

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val PipedJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
internal data class StreamsResponse(
    val title: String = "",
    val videoStreams: List<VideoStreamDto> = emptyList(),
    val audioStreams: List<AudioStreamDto> = emptyList(),
    val duration: Long = 0L,
)

@Serializable
internal data class VideoStreamDto(
    val url: String = "",
    val mimeType: String = "",
    val videoOnly: Boolean = false,
    val quality: String? = null,
    val height: Int = 0,
    val format: String? = null,
    val codec: String? = null,
    val itag: Int = -1,
)

@Serializable
internal data class AudioStreamDto(
    val url: String = "",
    val mimeType: String = "",
    val bitrate: Long = 0L,
    val format: String? = null,
    val codec: String? = null,
    val itag: Int = -1,
    val audioTrackType: String? = null,
    val audioTrackLocale: String? = null,
)

@Serializable
internal data class SearchResponse(
    val items: List<SearchItemDto> = emptyList(),
)

@Serializable
internal data class SearchItemDto(
    val type: String = "",
    val url: String = "",
    val title: String = "",
    val uploaderName: String = "",
    val duration: Long = 0L,
    val thumbnail: String = "",
)

data class SearchResult(
    val url: String,
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumbnail: String,
)

data class ResolvedVideo(
    val id: String,
    val title: String,
    val videoUrl: String?,
    val audioUrl: String?,
    val qualityLabel: String,
    val streamUrl: String?,
    val durationSecs: Long,
) {
    fun hasMergingPair(): Boolean = videoUrl != null && audioUrl != null
}
