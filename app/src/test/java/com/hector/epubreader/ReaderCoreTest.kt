package com.hector.epubreader

import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.data.preferences.ReaderPalette
import com.hector.epubreader.epub.*
import com.hector.epubreader.epub.renderer.EpubContent
import com.hector.epubreader.ui.reader.nextPageOffset
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ReaderCoreTest {
    @Test fun readingThemesKeepStrongTextContrastAndRenderTheirOwnColors() {
        fun luminance(hex: String): Double {
            val channels = hex.removePrefix("#").chunked(2).map {
                val channel = it.toInt(16) / 255.0
                if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
            }
            return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
        }
        ReaderPalette.entries.forEach { palette ->
            val background = luminance(palette.background)
            val text = luminance(palette.text)
            val contrast = (maxOf(background, text) + 0.05) / (minOf(background, text) + 0.05)
            assertTrue("${palette.name} needs legible body text: $contrast", contrast >= 7.0)
            val css = Jsoup.parse(EpubContent.render("<p>Reading</p>".toByteArray(), ReaderPreferences(palette = palette)))
                .selectFirst("style")!!.html()
            assertTrue(css.contains("background:${palette.background}"))
            assertTrue(css.contains("color:${palette.text}"))
            assertTrue(css.contains("color-scheme: ${if (palette.dark) "dark" else "light"}"))
        }
    }
    @Test fun limitsActualInflatedStreamIncludingSkippedBytes() {
        BoundedInputStream(ByteArray(20).inputStream(), 10).use { stream ->
            assertEquals(8, stream.read(ByteArray(8)))
            assertThrows(InvalidEpub::class.java) { stream.skip(4) }
        }
    }
    @Test fun allowsAnExactlyBoundedResource() {
        BoundedInputStream(byteArrayOf(1, 2).inputStream(), 2).use { stream ->
            assertEquals(1, stream.read())
            assertEquals(2, stream.read())
            assertEquals(-1, stream.read())
        }
    }
    @Test fun resolvesRelativeAndEncodedResources() {
        assertEquals("OPS/Images/a b+c.png", EpubPaths.resolve("OPS/Text/ch.xhtml", "../Images/a%20b+c.png#frag"))
        assertEquals("OPS/ch.xhtml", EpubPaths.resolve("OPS/ch.xhtml", "#note"))
        assertEquals("nota uno+dos", EpubPaths.fragment("ch.xhtml#nota%20uno+dos"))
        assertNull(EpubPaths.fragment("ch.xhtml"))
    }
    @Test fun rejectsRemoteAndEscapingPaths() {
        listOf("../../../secret", "%2e%2e/%2e%2e/secret", "https://example.com", "//example.com/book", "file:///secret", "a\\b", "%2Fetc/passwd").forEach { href ->
            assertThrows(href, InvalidEpub::class.java) { EpubPaths.resolve("OPS/book.opf", href) }
        }
    }
    @Test fun sanitizesExecutableMarkupButKeepsFormatting() {
        val input = """<html><head><script>alert(1)</script><base href="https://bad.test"/><meta http-equiv="refresh" content="0;url=https://bad.test"/></head><body onload="bad()"><h1>Título</h1><p><em>Texto</em><img src="cover.png" onerror="bad()"/></p><iframe src="bad"></iframe><a href="javascript:bad()">Mal</a><a href="two.xhtml#note">Nota</a><form><input/></form></body></html>"""
        val output = Jsoup.parse(EpubContent.render(input.toByteArray(), ReaderPreferences()))
        assertTrue(output.select("script, iframe, form, base, meta[http-equiv], [onload], [onerror]").isEmpty())
        assertEquals("Texto", output.selectFirst("em")?.text())
        assertEquals("cover.png", output.selectFirst("img")?.attr("src"))
        assertEquals("", output.select("a")[0].attr("href"))
        assertEquals("two.xhtml#note", output.select("a")[1].attr("href"))
        assertTrue(output.select("style").html().contains("font-size:20.0px"))
    }
    @Test fun computesAndBoundsProgress() {
        assertEquals(0.25f, ReadingProgress.fraction(0, 1f, 4), 0.0001f)
        assertEquals(0.625f, ReadingProgress.fraction(2, 0.5f, 4), 0.0001f)
        assertEquals(1f, ReadingProgress.fraction(99, 9f, 4), 0.0001f)
        assertEquals(0f, ReadingProgress.fraction(0, 0f, 0), 0.0001f)
    }
    @Test fun validatesPreferencesAtStorageBoundary() {
        assertFalse(ReaderPreferences().dynamicColors)
        val p = ReaderPreferences(fontSize = Float.NaN, lineHeight = 100f, horizontalMargin = -100, font = "bad;css", appTheme = "unknown", interfaceColor = "invalid", readingMode = "invalid").validated()
        assertEquals(20f, p.fontSize)
        assertEquals(2.2f, p.lineHeight)
        assertEquals(8, p.horizontalMargin)
        assertEquals("serif", p.font)
        assertEquals("system", p.appTheme)
        assertEquals("green", p.interfaceColor)
        assertEquals("scroll", p.readingMode)
        assertEquals("paragraphs", ReaderPreferences(readingMode = "paragraphs").validated().readingMode)
    }

    @Test fun renderedHtmlUsesProvidedAppColors() {
        val colors = EpubContent.Colors("#123456", "#F0F0F0", "#ABCDEF", "#445566", true)
        val html = EpubContent.render("<html><body><p>Texto</p></body></html>".toByteArray(), ReaderPreferences(readingMode = "pages"), colors = colors)
        val css = Jsoup.parse(html).selectFirst("style")!!.html()
        assertTrue(css.contains("background:#123456"))
        assertTrue(css.contains("color:#F0F0F0"))
        assertTrue(css.contains("color-scheme: dark"))
        assertTrue(css.contains("column-width:calc(100vw"))
        assertTrue(css.contains("column-fill:auto"))
        assertEquals("Texto", Jsoup.parse(html).selectFirst("body > #reader-flow > p")?.text())
    }
    @Test fun paragraphModeShowsOneContentBlockPerSlide() {
        val html = "<html><body><section class='story'><h1>Title</h1><p id='one'>First <em>paragraph</em>.</p><p id='two'>Second paragraph.</p></section></body></html>"
        val output = Jsoup.parse(EpubContent.render(html.toByteArray(), ReaderPreferences(readingMode = "paragraphs")))
        val slides = output.select("body > .reader-paragraph")
        assertEquals(3, slides.size)
        assertEquals("Title", slides[0].text())
        assertEquals("First paragraph.", slides[1].text())
        assertEquals("Second paragraph.", slides[2].text())
        assertEquals("one", slides[1].selectFirst("p")?.id())
        val css = output.selectFirst("style")!!.html()
        assertTrue(css.contains("column-fill:auto"))
        assertTrue(css.contains("break-before:column"))
        assertFalse(css.contains("overflow-y:auto"))
    }
    @Test fun paragraphModeKeepsTextOutsideParagraphTags() {
        val html = "<html><body><div>Intro <em>important</em><p>Middle</p>Outro</div></body></html>"
        val output = Jsoup.parse(EpubContent.render(html.toByteArray(), ReaderPreferences(readingMode = "paragraphs")))
        assertEquals(listOf("Intro important", "Middle", "Outro"), output.select("body > .reader-paragraph").map { it.text() })
    }
    @Test fun horizontalPageGesturesAdvanceAndReturnAtChapterEnd() {
        assertEquals(1000, nextPageOffset(0, 2500, 1000, 1))
        assertEquals(2000, nextPageOffset(1000, 2500, 1000, 1))
        assertEquals(2500, nextPageOffset(2000, 2500, 1000, 1))
        assertEquals(1500, nextPageOffset(2500, 2500, 1000, -1))
        assertEquals(500, nextPageOffset(1500, 2500, 1000, -1))
        assertEquals(0, nextPageOffset(0, 2500, 1000, -1))
    }
}

