package com.hector.epubreader

import com.hector.epubreader.data.AnnotationExport
import com.hector.epubreader.data.local.AnnotationEntity
import com.hector.epubreader.ui.reader.nextSpeechChunk
import org.junit.Assert.*
import org.junit.Test

class ReaderToolsTest {
    @Test fun sentenceSkipsUseAdjacentSentenceStartsFromAnywhereInsideTheCurrentSentence() {
        val text = "Primera oración muy corta. Segunda oración con café y palabras. Tercera oración final."
        val sentences = com.hector.epubreader.ui.reader.SpeechSentences(text, java.util.Locale.forLanguageTag("es"))
        val second = text.indexOf("Segunda")
        val third = text.indexOf("Tercera")
        assertEquals(second, sentences.adjacentStart(7, 1))
        assertEquals(0, sentences.adjacentStart(second + 12, -1))
        assertEquals(third, sentences.adjacentStart(second + 12, 1))
        assertEquals(second, sentences.adjacentStart(third, -1))
        assertNull(sentences.adjacentStart(0, -1))
        assertNull(sentences.adjacentStart(third, 1))
        val longText = "Una oración " + "larga ".repeat(200) + ". Segunda oración."
        val longSentences = com.hector.epubreader.ui.reader.SpeechSentences(longText, java.util.Locale.forLanguageTag("es"))
        assertEquals(longText.indexOf("Segunda"), longSentences.adjacentStart(700, 1))
    }


    @Test fun epubSpeechLanguagesResolveRegionalAndIso639Tags() {
        val fallback = java.util.Locale.ENGLISH
        assertEquals("es", com.hector.epubreader.ui.reader.speechLocale("spa", fallback).language)
        assertEquals("en", com.hector.epubreader.ui.reader.speechLocale("eng-US", fallback).language)
        assertEquals("MX", com.hector.epubreader.ui.reader.speechLocale(" es_MX ", fallback).country)
        assertEquals("zz", com.hector.epubreader.ui.reader.speechLocale("zz", fallback).language)
        assertEquals(fallback, com.hector.epubreader.ui.reader.speechLocale("und", fallback))
        assertEquals(fallback, com.hector.epubreader.ui.reader.speechLocale(null, fallback))
    }
    @Test fun narrationChunksContainOnlyTheCurrentSentence() {
        val text = "Vi tres fotografías de aquel hombre. La primera podría decirse que era de su infancia, tendría unos diez años. Estaba rodeado de mujeres."
        val sentences = com.hector.epubreader.ui.reader.SpeechSentences(text, java.util.Locale.forLanguageTag("es"))
        val first = sentences.next(0)!!
        assertEquals("Vi tres fotografías de aquel hombre.", first.text)
        val second = sentences.next(first.end)!!
        assertEquals("La primera podría decirse que era de su infancia, tendría unos diez años.", second.text)
        assertTrue(second.start > first.end)
        val third = sentences.next(second.end)!!
        assertEquals("Estaba rodeado de mujeres.", third.text)
        assertNull(sentences.next(third.end))
        assertEquals("fotografías de aquel hombre.", sentences.next(text.indexOf("fotografías"))!!.text)
    }
    @Test fun longSentencesKeepBoundedUnicodeSafeChunksAndCanonicalOffsets() {
        val text = "café \uD83D\uDE00 palabras ".repeat(100) + ". ¿Otra oración? ¡Sí!"
        val sentences = com.hector.epubreader.ui.reader.SpeechSentences(text, java.util.Locale.forLanguageTag("es"))
        assertEquals(sentences.bounds(0), sentences.bounds(80))
        assertTrue(sentences.bounds(80).second > 600)
        var offset = 0
        val parts = mutableListOf<String>()
        while (true) {
            val chunk = sentences.next(offset, 80) ?: break
            assertTrue(chunk.end > offset)
            assertTrue(chunk.text.length <= 80)
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
            assertEquals(text.substring(chunk.start, chunk.end), chunk.text)
            parts.add(chunk.text)
            offset = chunk.end
        }
        assertEquals(text.filterNot(Char::isWhitespace), parts.joinToString("").filterNot(Char::isWhitespace))
    }

    @Test fun exportedNotesArePortableAndCannotInjectMarkdownLinksOrHtml() {
        val mark = AnnotationEntity(bookId = "book", chapter = 0, resourcePath = "c.xhtml", startOffset = 0, endOffset = 5,
            quote = "First\nSecond 😀", note = "[link](https://example.com) <script> & café")
        val output = AnnotationExport.markdown("Title #1", "Author", listOf("Chapter"), listOf(mark))
        assertTrue(output.startsWith("# Title \\#1\n\nAuthor"))
        assertTrue(output.contains("> First\n> Second 😀"))
        assertTrue(output.contains("\\[link\\]"))
        assertFalse(output.contains("<script>"))
        assertTrue(output.contains("café"))
    }

    @Test fun speechChunksStayBoundedAndNeverSplitUnicodePairs() {
        val text = ("a".repeat(599) + "😀" + " Mixed café words. ").repeat(20)
        var offset = 0
        var spoken = ""
        while (true) {
            val chunk = nextSpeechChunk(text, offset) ?: break
            assertTrue(chunk.end > offset)
            assertTrue(chunk.text.length <= 600)
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
            assertEquals(text.substring(chunk.start, chunk.end), chunk.text)
            spoken += chunk.text
            offset = chunk.end
        }
        assertEquals(text.filterNot { it.isWhitespace() }, spoken.filterNot { it.isWhitespace() })
        assertNull(nextSpeechChunk(" \n\t", 0))
        assertEquals(0, nextSpeechChunk("😀 hi", 1)!!.start)
    }
}
