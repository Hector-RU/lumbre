package com.hector.epubreader

import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.renderer.EpubContent
import com.hector.epubreader.epub.renderer.EpubText
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TextIndexTest {
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
