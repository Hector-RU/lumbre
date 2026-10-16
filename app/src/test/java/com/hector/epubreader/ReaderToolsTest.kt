package com.hector.epubreader

import com.hector.epubreader.data.AnnotationExport
import com.hector.epubreader.data.local.AnnotationEntity
import com.hector.epubreader.ui.reader.nextSpeechChunk
import org.junit.Assert.*
import org.junit.Test

class ReaderToolsTest {
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
