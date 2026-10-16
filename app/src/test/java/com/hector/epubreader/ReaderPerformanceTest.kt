package com.hector.epubreader

import com.hector.epubreader.data.CanonicalTextCache
import com.hector.epubreader.epub.renderer.EpubContent
import com.hector.epubreader.epub.renderer.EpubText
import com.hector.epubreader.epub.renderer.HighlightSweep
import com.hector.epubreader.data.preferences.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReaderPerformanceTest {
    @Test fun cacheEvictsLeastRecentlyUsedAndNeverRetainsOversizedChapters() {
        val cache = CanonicalTextCache(12)
        cache.put("a", "one", "abc"); cache.put("a", "two", "def")
        assertEquals("abc", cache.get("a", "one"))
        cache.put("b", "three", "ghi")
        assertNull(cache.get("a", "two"))
        assertEquals("abc", cache.get("a", "one"))
        cache.put("b", "large", "1234567")
        assertNull(cache.get("b", "large"))
        cache.remove("a")
        assertNull(cache.get("a", "one"))
        assertEquals("ghi", cache.get("b", "three"))
    }

    @Test fun sweepMatchesBruteForceForOverlappingAnnotationsAndPreservesPriority() {
        val random = Random(42)
        val marks = List(1000) {
            val start = random.nextInt(10000)
            EpubContent.Highlight(start, start + random.nextInt(1, 400))
        }
        val sweep = HighlightSweep(marks)
        for (start in 0 until 11000 step 17) {
            assertEquals(marks.filter { it.start < start + 17 && it.end > start }, sweep.intersecting(start, start + 17))
        }
    }

    @Test fun largeAnnotatedChaptersKeepCanonicalTextAcrossModes() {
        val source = ("<body>" + (0 until 1000).joinToString("") { "<p>Paragraph $it: <em>Unicode café 😀</em> and nested text.</p>" } + "</body>").toByteArray()
        val text = EpubText.text(source)
        val marks = (0 until text.length step 55).map { val start = if (text[it].isLowSurrogate()) it + 1 else it
            val proposedEnd = minOf(it + 40, text.length)
            val end = if (proposedEnd < text.length && text[proposedEnd].isLowSurrogate()) proposedEnd - 1 else proposedEnd
            EpubContent.Highlight(start, end) }
        for (mode in listOf("scroll", "pages", "paragraphs")) {
            val start = System.nanoTime()
            val rendered = EpubContent.render(source, ReaderPreferences(readingMode = mode), highlights = marks)
            // Pagination adds a container newline; marked text retains exact original offsets.
            val markers = org.jsoup.Jsoup.parse(rendered).select("[data-reader-offset]")
            val markedText = markers.joinToString("") { (it.childNode(0) as org.jsoup.nodes.TextNode).wholeText }
            assertEquals(text.filterNot { it.isWhitespace() }, markedText.filterNot { it.isWhitespace() })
            org.jsoup.Jsoup.parse(rendered).select("[data-reader-offset]").forEach { node ->
                val offset = node.attr("data-reader-offset").toInt()
                val content = (node.childNode(0) as org.jsoup.nodes.TextNode).wholeText
                assertEquals(content, text.substring(offset, offset + content.length))
            }
            println("render mode=$mode sourceBytes=${source.size} annotations=${marks.size} durationMs=${(System.nanoTime() - start) / 1_000_000}")
        }
    }
}
