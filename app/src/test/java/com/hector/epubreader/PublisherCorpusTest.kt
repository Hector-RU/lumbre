package com.hector.epubreader

import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.epub.renderer.EpubContent
import com.hector.epubreader.epub.renderer.EpubText
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PublisherCorpusTest {
    @Test fun publicationsPreserveCanonicalOffsetsAcrossEveryChapterAndMode() {
        val root = listOf(File("."), File("..")).first { File(it, "docs/samples/lumbre-demo.epub").isFile }
        val supplied = File(root, "test-artifacts/corpus").listFiles().orEmpty().filter { it.extension == "epub" }.sortedBy { it.name }
        val corpus = supplied.ifEmpty { listOf(File(root, "docs/samples/lumbre-demo.epub")) }
        val parser = EpubParser()
        for (file in corpus) {
            val publication = parser.parse(file, file.nameWithoutExtension)
            assertTrue(publication.title.isNotBlank())
            assertTrue(publication.chapters.isNotEmpty())
            var markedCharacters = 0
            publication.chapters.forEach { chapter ->
                val source = parser.readResource(file, chapter.path)
                val canonical = EpubText.text(source)
                for (mode in listOf("scroll", "pages", "paragraphs")) {
                    val rendered = EpubContent.render(source, ReaderPreferences(readingMode = mode))
                    val markers = Jsoup.parse(rendered).select("[data-reader-offset]")
                    for (marker in markers) {
                        val offset = marker.attr("data-reader-offset").toInt()
                        val text = (marker.childNode(0) as TextNode).wholeText
                        assertTrue("${file.name}/${chapter.path}/$mode", offset >= 0 && offset + text.length <= canonical.length)
                        assertEquals("${file.name}/${chapter.path}/$mode", canonical.substring(offset, offset + text.length), text)
                        markedCharacters += text.length
                    }
                }
            }
            assertTrue(markedCharacters > 0)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            println("corpus file=${file.name} chapters=${publication.chapters.size} bytes=${file.length()} sha256=${digest.digest().joinToString("") { "%02x".format(it) }} modes=3")
        }
    }
}
