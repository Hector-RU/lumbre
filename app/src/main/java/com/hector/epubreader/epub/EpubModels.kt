package com.hector.epubreader.epub

data class EpubResource(val id: String, val path: String, val mediaType: String, val properties: Set<String>)
data class EpubChapter(val id: String, val path: String, val title: String)
data class TocEntry(val title: String, val path: String, val fragment: String? = null, val depth: Int = 0)
data class EpubPublication(
    val title: String,
    val author: String?,
    val language: String?,
    val publisher: String?,
    val coverPath: String?,
    val chapters: List<EpubChapter>,
    val contents: List<TocEntry>,
    val resources: Map<String, EpubResource>
)

class InvalidEpub(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
class UnsupportedEpub : java.io.IOException("Unsupported EPUB")

object ReadingProgress {
    fun fraction(chapter: Int, position: Float, count: Int): Float =
        if (count <= 0) 0f else ((chapter.coerceIn(0, count - 1) + position.coerceIn(0f, 1f)) / count).coerceIn(0f, 1f)
}
