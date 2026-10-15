package com.hector.epubreader.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.hector.epubreader.data.local.BookEntity
import com.hector.epubreader.data.local.BookmarkEntity
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.PreferencesRepository
import com.hector.epubreader.data.preferences.ReaderPalette
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.BoundedInputStream
import com.hector.epubreader.epub.EpubParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Versioned, user-selected offline backup. Restores add missing books and retain existing ones. */
class LibraryBackup(private val context: Context, private val db: LibraryDatabase, private val books: BookRepository, private val preferences: PreferencesRepository) {
    private val mutex = Mutex()

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) { mutex.withLock {
        val records = books.books.first()
        require(records.size <= 1000) { "A backup supports up to 1000 books" }
        val manifest = JSONObject().put("format", "lumbre-backup").put("version", 1)
            .put("preferences", encodePreferences(preferences.preferences.first()))
        val entries = JSONArray()
        records.forEach { book ->
            val item = JSONObject().put("checksum", book.checksum).put("title", book.title).put("chapter", book.currentChapter)
                .put("position", book.readingPosition).put("offset", book.readingTextOffset)
                .put("path", book.readingPath).put("added", book.dateAdded).put("lastRead", book.lastRead)
            val bookmarks = JSONArray()
            val savedBookmarks = db.library().bookmarks(book.id).first()
            require(savedBookmarks.size <= 10000) { "Too many bookmarks" }
            savedBookmarks.forEach {
                bookmarks.put(JSONObject().put("chapter", it.chapter).put("position", it.position)
                    .put("offset", it.textOffset).put("path", it.resourcePath).put("title", it.title.take(1000)).put("created", it.createdAt))
            }
            item.put("bookmarks", bookmarks)
            entries.put(item)
        }
        manifest.put("books", entries)
        // Prepare a complete archive before opening the selected destination.
        val temporary = File.createTempFile("lumbre-export-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                val bytes = manifest.toString().toByteArray()
                require(bytes.size <= MANIFEST_LIMIT) { "Backup metadata too large" }
                zip.write(bytes); zip.closeEntry()
                var totalBytes = bytes.size.toLong()
                records.forEach { record ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("books/${record.checksum}.epub"))
                    val file = books.open(record.id).file
                    totalBytes += file.length()
                    require(totalBytes <= TOTAL_LIMIT) { "Backup exceeds 2 GiB" }
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    require(temporary.length() <= TOTAL_LIMIT) { "Backup exceeds 2 GiB" }
                }
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                temporary.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Cannot create backup")
        } finally { temporary.delete() }
    } }

    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) { mutex.withLock {
        val staging = File(context.cacheDir, "lumbre-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val imported = mutableListOf<BookEntity>()
        try {
            var manifest: JSONObject? = null
            val archives = mutableMapOf<String, File>()
            val publications = mutableMapOf<String, com.hector.epubreader.epub.EpubPublication>()
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BoundedInputStream(input, TOTAL_LIMIT)).use { zip ->
                    var entry = zip.nextEntry
                    val seen = mutableSetOf<String>()
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        require(seen.add(entry.name) && seen.size <= 2001 && !entry.isDirectory) { "Invalid backup entries" }
                        if (entry.name == "manifest.json") {
                            val bytes = BoundedInputStream(zip, MANIFEST_LIMIT.toLong()).readBytes()
                            total += bytes.size
                            manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                        } else {
                            require(entry.name.matches(Regex("books/[a-f0-9]{64}\\.epub"))) { "Invalid backup path" }
                            val checksum = entry.name.removePrefix("books/").removeSuffix(".epub")
                            val file = File(staging, "$checksum.epub")
                            val digest = MessageDigest.getInstance("SHA-256")
                            file.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                val bounded = BoundedInputStream(zip, 512L * 1024 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = bounded.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= TOTAL_LIMIT) { "Backup exceeds 2 GiB" }
                                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                                }
                            }
                            require(digest.digest().joinToString("") { "%02x".format(it) } == checksum) { "Backup checksum mismatch" }
                            publications[checksum] = EpubParser().parse(file, "EPUB")
                            archives[checksum] = file
                        }
                        require(total <= TOTAL_LIMIT) { "Backup exceeds 2 GiB" }
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                }
            } ?: throw IOException("Cannot open backup")
            val metadata = requireNotNull(manifest) { "Missing backup manifest" }
            require(metadata.getString("format") == "lumbre-backup" && metadata.getInt("version") == 1) { "Unsupported backup version" }
            val items = metadata.getJSONArray("books")
            require(items.length() == archives.size) { "Missing backup books" }
            val checksums = (0 until items.length()).map { items.getJSONObject(it).getString("checksum") }
            require(checksums.toSet() == archives.keys && checksums.distinct().size == checksums.size)
            val restoredPreferences = decodePreferences(metadata.getJSONObject("preferences"))
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val publication = publications.getValue(item.getString("checksum"))
                fun validateLocation(location: JSONObject) {
                    require(location.getInt("chapter") in publication.chapters.indices)
                    val fraction = location.getDouble("position")
                    require(fraction.isFinite() && fraction in 0.0..1.0)
                    require(location.isNull("offset") || location.getInt("offset") >= 0)
                    require(location.isNull("path") || publication.resources[location.getString("path")]?.mediaType in setOf("application/xhtml+xml", "text/html"))
                }
                validateLocation(item)
                item.getString("title")
                require(item.getLong("added") >= 0 && item.getLong("lastRead") >= 0)
                val marks = item.getJSONArray("bookmarks")
                require(marks.length() <= 10000) { "Too many bookmarks" }
                for (m in 0 until marks.length()) {
                    val mark = marks.getJSONObject(m)
                    validateLocation(mark)
                    require(mark.getString("title").length <= 1000 && mark.getLong("created") >= 0)
                }
            }
            // All archives and structure have been validated before any library changes.
            for (index in 0 until items.length()) {
                currentCoroutineContext().ensureActive()
                val item = items.getJSONObject(index)
                val result = withContext(NonCancellable) {
                    books.import(Uri.fromFile(archives.getValue(item.getString("checksum")))).also {
                        if (!it.duplicate) imported += it.book
                    }
                }
                currentCoroutineContext().ensureActive()
                if (result.duplicate) continue
                val book = result.book
                val publication = books.open(book.id).publication
                val chapter = item.getInt("chapter").coerceIn(publication.chapters.indices)
                val position = item.getDouble("position").toFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
                fun path(value: JSONObject): String? = value.optString("path").takeIf {
                    publication.resources[it]?.mediaType in setOf("application/xhtml+xml", "text/html")
                }
                db.withTransaction {
                    db.library().update(book.copy(title = item.getString("title"), sourceUri = "lumbre-backup", dateAdded = item.getLong("added"), lastRead = item.getLong("lastRead")))
                    db.library().saveProgress(book.id, chapter, position, com.hector.epubreader.epub.ReadingProgress.fraction(chapter, position, book.chapterCount), item.getLong("lastRead"), offset(item), path(item))
                    val marks = item.getJSONArray("bookmarks")
                    require(marks.length() <= 10000) { "Too many bookmarks" }
                    for (m in 0 until marks.length()) {
                        val mark = marks.getJSONObject(m)
                        val fraction = mark.getDouble("position").toFloat()
                        require(fraction.isFinite() && fraction in 0f..1f)
                        db.library().insertBookmark(BookmarkEntity(bookId = book.id, chapter = mark.getInt("chapter").coerceIn(publication.chapters.indices), position = fraction, title = mark.getString("title").take(1000), createdAt = mark.getLong("created"), textOffset = offset(mark), resourcePath = path(mark)))
                    }
                }
            }
            preferences.update { restoredPreferences }
            imported.size
        } catch (error: Exception) {
            withContext(NonCancellable) { imported.forEach { books.remove(it) } }
            throw error
        } finally { staging.deleteRecursively() }
    } }

    private fun offset(json: JSONObject): Int? = if (json.isNull("offset")) null else json.getInt("offset").takeIf { it >= 0 }

    companion object {
        private const val MANIFEST_LIMIT = 8 * 1024 * 1024
        private const val TOTAL_LIMIT = 2L * 1024 * 1024 * 1024

        private fun encodePreferences(p: ReaderPreferences) = JSONObject().apply {
            put("appTheme", p.appTheme); put("dynamicColors", p.dynamicColors); put("interfaceColor", p.interfaceColor)
            put("fontSize", p.fontSize); put("font", p.font); put("lineHeight", p.lineHeight); put("paragraphSpacing", p.paragraphSpacing)
            put("horizontalMargin", p.horizontalMargin); put("verticalMargin", p.verticalMargin); put("justified", p.justified)
            put("palette", p.palette.name); put("readingMode", p.readingMode); put("volumeNavigation", p.volumeNavigation)
            put("sort", p.sort); put("ascending", p.ascending); put("listView", p.listView)
        }

        private fun decodePreferences(j: JSONObject) = ReaderPreferences(
            appTheme = j.getString("appTheme"), dynamicColors = j.getBoolean("dynamicColors"), interfaceColor = j.getString("interfaceColor"),
            fontSize = j.getDouble("fontSize").toFloat(), font = j.getString("font"), lineHeight = j.getDouble("lineHeight").toFloat(),
            paragraphSpacing = j.getDouble("paragraphSpacing").toFloat(), horizontalMargin = j.getInt("horizontalMargin"), verticalMargin = j.getInt("verticalMargin"),
            justified = j.getBoolean("justified"), palette = ReaderPalette.entries.firstOrNull { it.name == j.getString("palette") } ?: ReaderPalette.CREAM,
            readingMode = j.getString("readingMode"), volumeNavigation = j.getBoolean("volumeNavigation"), sort = j.getString("sort"), ascending = j.getBoolean("ascending"), listView = j.getBoolean("listView")
        ).validated()
    }
}
