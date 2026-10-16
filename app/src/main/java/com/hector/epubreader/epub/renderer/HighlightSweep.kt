package com.hector.epubreader.epub.renderer

/** Visits text nodes in canonical order, retaining original annotation precedence. */
internal class HighlightSweep(highlights: List<EpubContent.Highlight>) {
    private val pending = highlights.withIndex().sortedBy { it.value.start }
    private val active = mutableListOf<IndexedValue<EpubContent.Highlight>>()
    private var cursor = 0

    fun intersecting(start: Int, end: Int): List<EpubContent.Highlight> {
        active.removeAll { it.value.end <= start }
        while (cursor < pending.size && pending[cursor].value.start < end) {
            val next = pending[cursor++]
            if (next.value.end > start) active += next
        }
        return active.sortedBy { it.index }.map { it.value }
    }
}
