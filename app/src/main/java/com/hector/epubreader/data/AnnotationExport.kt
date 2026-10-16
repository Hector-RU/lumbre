package com.hector.epubreader.data

import com.hector.epubreader.data.local.AnnotationEntity

object AnnotationExport {
    private fun literal(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
        .map { if (it in "\\`*_{}[]<>#+!|~") "\\$it" else it.toString() }.joinToString("")

    fun markdown(title: String, author: String?, chapters: List<String>, annotations: List<AnnotationEntity>): String = buildString {
        append("# ").append(literal(title)).append("\n\n")
        author?.let { append(literal(it)).append("\n\n") }
        annotations.sortedWith(compareBy<AnnotationEntity> { it.chapter }.thenBy { it.resourcePath }.thenBy { it.startOffset }).forEach { mark ->
            append("## ").append(literal(chapters.getOrElse(mark.chapter) { "${mark.chapter + 1}" })).append("\n\n")
            literal(mark.quote).lineSequence().forEach { append("> ").append(it).append('\n') }
            if (mark.note.isNotBlank()) append('\n').append(literal(mark.note)).append('\n')
            append("\n---\n\n")
        }
    }
}
