package com.captainzonks.grodtv.piped

private val ID_CHARS = Regex("^[A-Za-z0-9_-]{11}$")

fun extractVideoId(input: String): String? {
    val trimmed = input.trim()

    if (ID_CHARS.matches(trimmed)) return trimmed

    val vParam = trimmed.indexOf("v=")
    if (vParam >= 0) {
        val candidate = trimmed.substring(vParam + 2).take(11)
        if (ID_CHARS.matches(candidate)) return candidate
    }

    val short = trimmed.indexOf("youtu.be/")
    if (short >= 0) {
        val candidate = trimmed.substring(short + 9).take(11)
        if (ID_CHARS.matches(candidate)) return candidate
    }

    return null
}
