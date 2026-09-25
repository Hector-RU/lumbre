package com.hector.epubreader.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

class EpubParser {
    fun parse(file: File, fallbackTitle: String): EpubPublication {
        try {
            ZipFile(file).use { zip ->
                validateArchive(zip)
                if (read(zip, "mimetype", 100).toString(Charsets.US_ASCII).trim() != "application/epub+zip") throw InvalidEpub("Missing EPUB mimetype")
                if (zip.getEntry("META-INF/encryption.xml") != null) throw UnsupportedEpub()
                val container = xml(read(zip, "META-INF/container.xml"))
                val opfPath = container.tag("rootfile").firstOrNull()?.attr("full-path")?.let { EpubPaths.resolve("", it) }
                    ?: throw InvalidEpub("Missing package")
                val opf = xml(read(zip, opfPath))
                if (opf.tag("meta").any { it.attr("property") == "rendition:layout" && it.text() == "pre-paginated" }) throw UnsupportedEpub()
                val metadata = opf.tag("metadata").firstOrNull() ?: throw InvalidEpub("Missing metadata")
                val manifest = opf.tag("manifest").firstOrNull()?.tag("item").orEmpty().map {
                    EpubResource(it.attr("id"), EpubPaths.resolve(opfPath, it.attr("href")), it.attr("media-type"), it.attr("properties").split(' ').toSet())
                }
                if (manifest.isEmpty() || manifest.map { it.id }.distinct().size != manifest.size) throw InvalidEpub("Invalid manifest")
                val byId = manifest.associateBy { it.id }
                val spine = opf.tag("spine").firstOrNull() ?: throw InvalidEpub("Missing spine")
                val chapterItems = spine.tag("itemref").filter { it.attr("linear") != "no" }.map {
                    byId[it.attr("idref")] ?: throw InvalidEpub("Missing spine resource")
                }
                if (chapterItems.isEmpty() || chapterItems.any { it.mediaType !in setOf("application/xhtml+xml", "text/html") || zip.getEntry(it.path) == null }) throw InvalidEpub("Invalid reading order")
                val contents = mutableListOf<TocEntry>()
                val nav = manifest.firstOrNull { "nav" in it.properties }
                if (nav != null) {
                    val doc = xml(read(zip, nav.path))
                    val toc = doc.tag("nav").firstOrNull { "toc" in it.attr("epub:type").split(' ') || it.attr("role") == "doc-toc" }
                    toc?.tag("a")?.forEach { link ->
                        val href = link.attr("href")
                        val path = EpubPaths.resolve(nav.path, href)
                        if (chapterItems.any { it.path == path }) contents += TocEntry(link.text(), path, EpubPaths.fragment(href), (link.parents().count { it.normalName() == "ol" } - 1).coerceAtLeast(0))
                    }
                }
                if (contents.isEmpty()) {
                    val ncx = byId[spine.attr("toc")] ?: manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                    if (ncx != null) xml(read(zip, ncx.path)).tag("navPoint").forEach { point ->
                        val href = point.children().firstOrNull { it.normalName().equals("content", true) }?.attr("src").orEmpty()
                        if (href.isNotEmpty()) {
                            val path = EpubPaths.resolve(ncx.path, href)
                            if (chapterItems.any { it.path == path }) contents += TocEntry(point.tag("navLabel").firstOrNull()?.text().orEmpty(), path, EpubPaths.fragment(href), point.parents().count { it.normalName().equals("navPoint", true) })
                        }
                    }
                }
                val chapters = chapterItems.mapIndexed { index, item -> EpubChapter(item.id, item.path, contents.firstOrNull { it.path == item.path }?.title?.ifBlank { null } ?: "${index + 1}") }
                val coverId = metadata.tag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
                val cover = manifest.firstOrNull { "cover-image" in it.properties } ?: byId[coverId]
                return EpubPublication(
                    metadata.tag("title").firstOrNull()?.text()?.ifBlank { null } ?: fallbackTitle,
                    metadata.tag("creator").map { it.text() }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
                    metadata.tag("language").firstOrNull()?.text(), metadata.tag("publisher").firstOrNull()?.text(),
                    cover?.takeIf { it.mediaType.startsWith("image/") && zip.getEntry(it.path) != null }?.path,
                    chapters, contents.ifEmpty { chapters.map { TocEntry(it.title, it.path) } }, manifest.associateBy { it.path }
                )
            }
        } catch (e: UnsupportedEpub) { throw e
        } catch (e: InvalidEpub) { throw e
        } catch (e: java.io.IOException) { throw InvalidEpub("Unreadable EPUB", e)
        } catch (e: IllegalArgumentException) { throw InvalidEpub("Malformed EPUB", e) }
    }

    fun readResource(file: File, path: String, limit: Int = 8 * 1024 * 1024): ByteArray = ZipFile(file).use { read(it, path, limit) }

    private fun validateArchive(zip: ZipFile) {
        if (zip.size() > 20_000) throw InvalidEpub("Too many ZIP entries")
        var total = 0L
        val seen = HashSet<String>()
        zip.entries().asSequence().forEach { entry ->
            val path = EpubPaths.normalize(entry.name)
            if (path != entry.name.trimEnd('/') || !seen.add(path)) throw InvalidEpub("Ambiguous ZIP path")
            if (entry.size < 0 || entry.size > 128L * 1024 * 1024) throw InvalidEpub("Oversized resource")
            total += entry.size
            if (total > 1024L * 1024 * 1024) throw InvalidEpub("Archive too large")
        }
    }

    private fun read(zip: ZipFile, path: String, limit: Int = 2 * 1024 * 1024): ByteArray {
        val entry = zip.getEntry(path) ?: throw InvalidEpub("Missing resource")
        if (entry.size > limit) throw InvalidEpub("Resource exceeds limit")
        return zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                if (out.size() + count > limit) throw InvalidEpub("Resource exceeds limit")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    // jsoup's XML parser does not load DTDs or resolve external entities.
    private fun xml(bytes: ByteArray) = Jsoup.parse(bytes.inputStream(), null, "", Parser.xmlParser())
    private fun Element.tag(local: String): List<Element> = getAllElements().filter { it.tagName().substringAfter(':').equals(local, true) }
}
