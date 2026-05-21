package com.captainzonks.grodtv.piped

enum class Quality {
    Best,
    P1080,
    P720,
    P480,
    P360;

    val targetHeight: Int
        get() = when (this) {
            Best, P1080 -> 1080
            P720 -> 720
            P480 -> 480
            P360 -> 360
        }

    val label: String
        get() = when (this) {
            Best -> "best"
            P1080 -> "1080p"
            P720 -> "720p"
            P480 -> "480p"
            P360 -> "360p"
        }

    companion object {
        fun parse(s: String): Quality? = when (s.trim().lowercase()) {
            "best" -> Best
            "1080p", "1080" -> P1080
            "720p", "720" -> P720
            "480p", "480" -> P480
            "360p", "360" -> P360
            else -> null
        }
    }
}
