package com.hector.epubreader

import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.renderer.EpubContent
import com.hector.epubreader.epub.renderer.EpubText
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TextIndexTest {
    @Test fun eachMarkStyleAndColorPreservesTextAcrossThemesAndReadingModes() {
        val bytes = "<p>Alpha beta gamma.</p>".toByteArray()
        val text = EpubText.text(bytes)
        for (mode in listOf("scroll", "pages", "paragraphs")) for (dark in listOf(false, true)) {
            for (style in com.hector.epubreader.epub.AnnotationStyle.entries) for (color in com.hector.epubreader.epub.AnnotationColor.entries) {
                val colors = EpubContent.Colors(if (dark) "#222222" else "#ffffff", if (dark) "#eeeeee" else "#111111", "#008800", "#888888", dark)
                val doc = Jsoup.parse(EpubContent.render(bytes, ReaderPreferences(readingMode = mode), colors = colors, highlights = listOf(EpubContent.Highlight(2, 12, style.id, color.id))))
                val marks = doc.select("[data-reader-offset]")
                val marked = marks.first { it.attr("data-reader-offset") == "2" }.attr("style")
                assertEquals(style.shaded, marked.contains("background-color:${color.background}"))
                assertEquals(style.underlined, marked.contains("text-decoration:underline"))
                if (style.underlined) assertTrue(marked.contains("text-decoration-color:${if (dark && !style.shaded) color.darkLine else color.line}"))
                marks.forEach {
                    val start = it.attr("data-reader-offset").toInt()
                    assertEquals(it.wholeText(), text.substring(start, start + it.wholeText().length))
                }
            }
        }
    }

    @Test fun overlappingShadeAndUnderlineKeepTheirIndependentColors() {
        val doc = Jsoup.parse(EpubContent.render("<p>Alpha beta gamma.</p>".toByteArray(), ReaderPreferences(), highlights = listOf(
            EpubContent.Highlight(0, 10, "shade", "blue"), EpubContent.Highlight(6, 15, "underline", "red"))))
        val overlap = doc.selectFirst("[data-reader-offset=6]")!!.attr("style")
        assertTrue(overlap.contains("background-color:#bcdcff"))
        assertTrue(overlap.contains("text-decoration-color:#c2394b"))
    }
    @Test fun overlappingHighlightsPreserveCanonicalOffsetsInEveryMode() {
        val input = "<p>Alpha <em>beta</em> gamma 😀 delta.</p><p>Next.</p>".toByteArray()
        val canonical = EpubText.text(input)
        for (mode in listOf("scroll", "pages", "paragraphs")) {
            val doc = Jsoup.parse(EpubContent.render(input, ReaderPreferences(readingMode = mode), highlights = listOf(EpubContent.Highlight(3, 13), EpubContent.Highlight(8, 22))))
            val markers = doc.select("[data-reader-offset]")
            assertTrue(markers.any { it.attr("style").contains("background-color:#ffe082") })
            markers.forEach {
                val node = it.childNode(0) as org.jsoup.nodes.TextNode
                val offset = it.attr("data-reader-offset").toInt()
                assertEquals(node.wholeText, canonical.substring(offset, offset + node.wholeText.length))
                if (offset in 3 until 22) assertTrue(it.attr("style").contains("background-color:#ffe082"))
            }
            assertEquals("Alpha beta gamma 😀 delta. Next.", doc.body().text())
        }
    }
    @Test fun leavesSvgTextStructureIntactInEveryReadingMode() {
        val input = "<p>Before</p><svg viewBox='0 0 100 100'><text x='5' y='30'>Graphic label</text></svg><p>After</p>".toByteArray()
        assertFalse(EpubText.text(input).contains("Graphic label"))
        for (mode in listOf("scroll", "pages", "paragraphs")) {
            val doc = Jsoup.parse(EpubContent.render(input, ReaderPreferences(readingMode = mode)))
            val text = requireNotNull(doc.selectFirst("svg text"))
            assertEquals("Graphic label", text.text())
            assertTrue(text.childNode(0) is org.jsoup.nodes.TextNode)
            assertTrue(doc.select("svg [data-reader-offset]").isEmpty())
        }
    }
    @Test fun offsetsSurviveEveryModeAndExcludeExecutableContent() {
        val input = "<html><body><style>.hidden{display:none}</style><h1>Inicio</h1><div><p>Uno <em>dos</em> tres 😀.</p><p>Otro párrafo.</p></div><script>bad()</script><p data-reader-offset='0'>Final</p></body></html>".toByteArray()
        val text = EpubText.text(input)
        assertFalse(text.contains("bad()"))
        assertFalse(text.contains("display:none"))
        val layouts = listOf("scroll", "pages", "paragraphs").map { mode ->
            Jsoup.parse(EpubContent.render(input, ReaderPreferences(readingMode = mode))).select("[data-reader-offset]").map {
                val offset = it.attr("data-reader-offset").toInt()
                val value = it.wholeText()
                assertEquals(value, text.substring(offset, offset + value.length))
                offset to value
            }
        }
        assertEquals(layouts[0], layouts[1])
        assertEquals(layouts[0], layouts[2])
    }

    @Test fun findsEveryOccurrenceAcrossInlineMarkupAndWhitespace() {
        val text = EpubText.text("<p>Espe<em>ranza</em> y esperanza.</p><p>LUZ   DEL <b>puerto</b>.</p>".toByteArray())
        val offsets = EpubText.matches(text, "esperanza").toList()
        assertEquals(2, offsets.size)
        assertEquals("Esperanza", text.substring(offsets[0]))
        assertEquals(1, EpubText.matches(text, "luz del puerto").count())
        assertEquals(0, EpubText.matches(text, "a").count())
        assertEquals(0, EpubText.matches(text, ".*").count())
    }
}
