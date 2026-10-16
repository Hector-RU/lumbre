package com.hector.epubreader.ui.reader

internal data class SpeechChunk(val start: Int, val end: Int, val text: String)

/** Short utterances bound replay on engines that do not provide word range events. */
internal fun nextSpeechChunk(text: String, offset: Int, limit: Int = 600): SpeechChunk? {
    require(limit >= 2)
    var start = offset.coerceIn(0, text.length)
    if (start < text.length && text[start].isLowSurrogate() && start > 0 && text[start - 1].isHighSurrogate()) start--
    while (start < text.length && text[start].isWhitespace()) start++
    if (start == text.length) return null
    var end = minOf(start + limit, text.length)
    if (end < text.length) {
        val boundary = (end - 1 downTo start + limit / 2).firstOrNull { text[it].isWhitespace() }
        if (boundary != null) end = boundary
        if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
    }
    return SpeechChunk(start, end, text.substring(start, end))
}
