package com.captainzonks.grodtv.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueueEntryDto(
    val pos: Int,
    val id: String,
    val title: String,
)

@Serializable
data class NowPlayingDto(
    val id: String,
    val title: String,
)

@Serializable
data class StatusResponse(
    val state: String,
    @SerialName("now_playing") val nowPlaying: NowPlayingDto?,
    val queue: List<QueueEntryDto>,
    val daemon: Boolean,
    val quality: String,
    val position: Long? = null,
    val duration: Long? = null,
    @SerialName("piped_url") val pipedUrl: String? = null,
)

@Serializable
data class UrlBody(
    val url: String,
    val force: Boolean = false,
)

@Serializable
data class SeekBody(
    val seconds: Int? = null,
)

@Serializable
data class QualityBody(
    val quality: String,
)

@Serializable
data class PipedUrlBody(
    val url: String,
)

@Serializable
data class PipedUrlSetResponse(
    @SerialName("piped_url") val pipedUrl: String,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class QueuedResponse(val pos: Int, val title: String)

@Serializable
data class CastResponse(
    val casting: Boolean = true,
    val title: String,
    val quality: String,
)

@Serializable
data class CastQueuedResponse(
    val queued: Boolean = true,
    val pos: Int,
    val title: String,
)

@Serializable
data class RemovedResponse(val removed: String)

@Serializable
data class QualitySetResponse(val quality: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class SearchResultDto(
    val url: String,
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumbnail: String,
)
