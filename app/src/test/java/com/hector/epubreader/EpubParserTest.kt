package com.hector.epubreader

import com.hector.epubreader.epub.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private val parser = EpubParser()
    private fun epub(overrides: Map<String, String> = emptyMap(), omit: Set<String> = emptySet()): File {
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            "OEBPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>La luz &amp; el mar</dc:title><dc:creator>Ana</dc:creator><dc:language>es</dc:language></metadata><manifest><item id="c1" href="text/uno.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="text/dos.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/></manifest><spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="text/dos.xhtml#inicio">El mar</a><ol><li><a href="text/uno.xhtml">La luz</a></li></ol></li></ol></nav></body></html>""",
            "OEBPS/text/uno.xhtml" to "<html><body><p>Una esperanza.</p></body></html>",
            "OEBPS/text/dos.xhtml" to "<html><body><h1 id='inicio'>El mar</h1></body></html>",
            "OEBPS/cover.png" to "test"
        ).apply { putAll(overrides); omit.forEach { remove(it) } }
        return temporary.newFile().apply { ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() } } }
    }

    @Test fun readsMetadataSpineNavAndCover() {
        val book = parser.parse(epub(), "fallback")
        assertEquals("La luz & el mar", book.title)
        assertEquals("Ana", book.author)
        assertEquals("es", book.language)
        assertEquals("OEBPS/text/dos.xhtml", book.chapters.first().path)
        assertEquals("El mar", book.chapters.first().title)
        assertEquals("inicio", book.contents.first().fragment)
        assertEquals(1, book.contents[1].depth)
        assertEquals("OEBPS/cover.png", book.coverPath)
    }
    @Test fun readsEpub2Ncx() {
        val opf = """<package><metadata><title>EPUB 2</title><meta name="cover" content="cover"/></metadata><manifest><item id="c1" href="text/uno.xhtml" media-type="application/xhtml+xml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="cover" href="cover.png" media-type="image/png"/></manifest><spine toc="ncx"><itemref idref="c1"/></spine></package>"""
        val ncx = """<ncx><navMap><navPoint id="one"><navLabel><text>Primero</text></navLabel><content src="text/uno.xhtml#p1"/></navPoint></navMap></ncx>"""
        val book = parser.parse(epub(mapOf("OEBPS/book.opf" to opf, "OEBPS/toc.ncx" to ncx)), "fallback")
        assertEquals("Primero", book.chapters.first().title)
        assertEquals("p1", book.contents.first().fragment)
        assertNotNull(book.coverPath)
    }
    @Test fun fallsBackWhenMetadataIncomplete() {
        val opf = """<package><metadata/><manifest><item id="c" href="text/uno.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        val book = parser.parse(epub(mapOf("OEBPS/book.opf" to opf)), "Mi libro")
        assertEquals("Mi libro", book.title)
        assertNull(book.author)
        assertEquals(1, book.contents.size)
    }
    @Test fun rejectsMissingContainer() { assertThrows(InvalidEpub::class.java) { parser.parse(epub(omit = setOf("META-INF/container.xml")), "book") } }
    @Test fun rejectsMissingSpineFile() { assertThrows(InvalidEpub::class.java) { parser.parse(epub(omit = setOf("OEBPS/text/dos.xhtml")), "book") } }
    @Test fun rejectsZipSlip() { assertThrows(InvalidEpub::class.java) { parser.parse(epub(mapOf("../outside.txt" to "x")), "book") } }
    @Test fun rejectsAmbiguousZipNames() { assertThrows(InvalidEpub::class.java) { parser.parse(epub(mapOf("OEBPS/text/../escape.txt" to "x")), "book") } }
    @Test fun rejectsWrongMimetype() { assertThrows(InvalidEpub::class.java) { parser.parse(epub(mapOf("mimetype" to "application/zip")), "book") } }
    @Test fun rejectsEncryptedBook() { assertThrows(UnsupportedEpub::class.java) { parser.parse(epub(mapOf("META-INF/encryption.xml" to "<encryption/>")), "book") } }
    @Test fun rejectsNonZip() { assertThrows(InvalidEpub::class.java) { parser.parse(temporary.newFile().apply { writeText("not a zip") }, "book") } }
    @Test fun boundsResourceReads() { assertThrows(InvalidEpub::class.java) { parser.readResource(epub(), "OEBPS/text/uno.xhtml", 8) } }
    @Test fun doesNotExpandExternalEntities() {
        val secret = temporary.newFile().apply { writeText("PRIVATE_SENTINEL") }
        val xml = """<!DOCTYPE package [<!ENTITY xxe SYSTEM "${secret.toURI()}">]><package><metadata><title>&xxe;</title></metadata><manifest><item id="c" href="text/uno.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        assertFalse(parser.parse(epub(mapOf("OEBPS/book.opf" to xml)), "fallback").title.contains("PRIVATE_SENTINEL"))
    }
}
