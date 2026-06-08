package com.captainzonks.grodtv.piped

enum class Quality {
    Best,
    P2160,
    P1440,
    P1080,
    P720,
    P480,
    P360;

    /**
     * Upper bound on video-stream height for this tier.
     *
     * `Best` is unbounded (Int.MAX_VALUE) so it resolves the single
     * highest-resolution stream YouTube exposes — 2160p when present, else
     * whatever is below it. Before this it was pinned to 1080, which silently
     * capped "best" at Full HD even on 4K-capable hardware.
     *
     * Heights at and above 1440p are served by YouTube only as VP9 or AV1
     * (there is no H.264 above 1080p), so picking them requires the resolver
     * to accept non-AVC codecs — see [pickStreamsForQuality]. ExoPlayer
     * (Media3) decodes VP9/AV1 only when the device has a decoder for that
     * codec: most Android TV devices hardware-decode VP9, but AV1 needs a
     * recent SoC (the NVIDIA Shield's Tegra X1 has none). [pickStreamsForQuality]
     * is therefore given the device's decodable-codec set and never selects a
     * stream the hardware cannot play. No transcode is involved: the chosen
     * video-only stream is merged with the M4A audio track exactly as the
     * H.264 path was.
     */
    val targetHeight: Int
        get() = when (this) {
            Best -> Int.MAX_VALUE
            P2160 -> 2160
            P1440 -> 1440
            P1080 -> 1080
            P720 -> 720
            P480 -> 480
            P360 -> 360
        }

    val label: String
        get() = when (this) {
            Best -> "best"
            P2160 -> "2160p"
            P1440 -> "1440p"
            P1080 -> "1080p"
            P720 -> "720p"
            P480 -> "480p"
            P360 -> "360p"
        }

    companion object {
        fun parse(s: String): Quality? = when (s.trim().lowercase()) {
            "best" -> Best
            "2160p", "2160", "4k" -> P2160
            "1440p", "1440", "2k" -> P1440
            "1080p", "1080" -> P1080
            "720p", "720" -> P720
            "480p", "480" -> P480
            "360p", "360" -> P360
            else -> null
        }
    }
}
