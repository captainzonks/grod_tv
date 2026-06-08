package com.captainzonks.grodtv.piped

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * The adaptive video codecs grod_tv knows how to request from the resolver.
 *
 * YouTube serves three video codecs for adaptive (video-only) streams; which
 * ones a given device can actually *decode* differs by hardware:
 *   - H.264/AVC: universally decodable, but YouTube ships nothing above 1080p
 *     as H.264.
 *   - VP9: hardware-decoded on most Android TV devices, including the NVIDIA
 *     Shield (Tegra X1). Carries 1440p/2160p.
 *   - AV1: only on newer SoCs. The Shield (Tegra X1/X1+) has **no AV1 decoder
 *     at all** — selecting an AV1 stream there yields no video track (audio
 *     plays, screen stays black).
 */
enum class VideoCodec { AVC, VP9, AV1 }

/**
 * Which video codecs the current device has a decoder for.
 *
 * Resolved once from [android.media.MediaCodecList] and handed to
 * [pickStreamsForQuality] so the picker never selects a stream the device
 * cannot decode. This is deliberately device-aware rather than a hardcoded
 * "no AV1" rule: a Shield reports {AVC, VP9}; a 2024+ Google TV with an AV1
 * block reports {AVC, VP9, AV1} and keeps the efficient-codec preference.
 *
 * H.264 is always included as a safety floor — every YouTube video exposes an
 * H.264 ladder up to 1080p, so even a device that somehow advertises no AVC
 * decoder still has a last-resort path rather than an empty set.
 */
object VideoCodecSupport {

    /** MIME types YouTube's adaptive video-only streams arrive as. */
    private val MIME_BY_CODEC = mapOf(
        VideoCodec.AVC to MediaFormat.MIMETYPE_VIDEO_AVC,   // "video/avc"
        VideoCodec.VP9 to MediaFormat.MIMETYPE_VIDEO_VP9,   // "video/x-vnd.on2.vp9"
        VideoCodec.AV1 to MediaFormat.MIMETYPE_VIDEO_AV1,   // "video/av01"
    )

    /**
     * Detect decodable codecs from the platform codec list.
     *
     * Uses [MediaCodecList.REGULAR_CODECS] (the set used for normal playback,
     * excluding codecs that require special permissions) and inspects only
     * decoders. AVC is always force-included as the universal floor.
     */
    fun detect(): Set<VideoCodec> {
        val supported = mutableSetOf(VideoCodec.AVC)
        val decoderMimes = buildDecoderMimeSet()
        for ((codec, mime) in MIME_BY_CODEC) {
            if (mime.lowercase() in decoderMimes) supported += codec
        }
        return supported
    }

    private fun buildDecoderMimeSet(): Set<String> {
        val mimes = HashSet<String>()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info: MediaCodecInfo in list.codecInfos) {
            if (!info.isEncoder) {
                for (type in info.supportedTypes) mimes += type.lowercase()
            }
        }
        return mimes
    }
}
