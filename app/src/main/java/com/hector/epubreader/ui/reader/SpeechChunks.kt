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

/** EPUBs also use ISO-639-2 tags (spa, eng) and underscore-separated region tags. */
internal fun speechLocale(tag: String?, fallback: java.util.Locale = java.util.Locale.getDefault()): java.util.Locale {
    val locale = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }?.let(java.util.Locale::forLanguageTag)
        ?.takeIf { it.language.isNotBlank() && it.language != "und" } ?: return fallback
    if (locale.language.length != 3) return locale
    val language = java.util.Locale.getISOLanguages().firstOrNull {
        runCatching { java.util.Locale.forLanguageTag(it).isO3Language == locale.language }.getOrDefault(false)
    } ?: return locale
    return java.util.Locale.Builder().setLocale(locale).setLanguage(language).build()
}

/** Cache linguistic sentence boundaries once per chapter, preserving canonical UTF-16 offsets. */
internal class SpeechSentences(private val text: String, locale: java.util.Locale) {
    private val ends = java.text.BreakIterator.getSentenceInstance(locale).run {
        setText(this@SpeechSentences.text)
        val boundaries = mutableListOf<Int>()
        var boundary = first()
        while (boundary != java.text.BreakIterator.DONE) {
            if (boundary > 0) boundaries.add(boundary)
            boundary = next()
        }
        boundaries.toIntArray()
    }

    fun bounds(offset: Int): Pair<Int, Int> {
        val found = ends.binarySearch(offset.coerceIn(0, text.length))
        val index = if (found >= 0) found + 1 else -found - 1
        var start = if (index > 0) ends[index - 1] else 0
        var end = ends.getOrElse(index) { text.length }
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return start to end
    }

    fun adjacentStart(offset: Int, direction: Int): Int? {
        val (start, end) = bounds(offset)
        if (direction < 0) return if (start > 0) bounds(start - 1).first.takeIf { it < start } else null
        if (direction > 0) return next(end)?.start
        return null
    }

    fun next(offset: Int, limit: Int = 600): SpeechChunk? {
        val bounded = nextSpeechChunk(text, offset, limit) ?: return null
        val found = ends.binarySearch(bounded.start)
        val index = if (found >= 0) found + 1 else -found - 1
        val sentenceEnd = ends.getOrElse(index) { text.length }
        var end = minOf(sentenceEnd, bounded.end)
        while (end > bounded.start && text[end - 1].isWhitespace()) end--
        return SpeechChunk(bounded.start, end, text.substring(bounded.start, end))
    }
}
