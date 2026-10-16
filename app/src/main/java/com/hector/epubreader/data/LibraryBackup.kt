package com.hector.epubreader.data

import android.util.AtomicFile
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.hector.epubreader.data.local.AnnotationEntity
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

data class BackupProgress(val completed: Int = 0, val total: Int = 0)

/** Versioned, user-selected offline backup. Restores add missing books and retain existing ones. */
class LibraryBackup(private val context: Context, private val db: LibraryDatabase, private val books: BookRepository, private val preferences: PreferencesRepository, private val openBackupOutput: (Uri) -> java.io.OutputStream? = { uri -> context.contentResolver.openOutputStream(uri, "wt") }) {
    private val mutex = Mutex()
    private val journal = AtomicFile(File(context.filesDir, "restore-journal.json"))

    fun hasPendingRecovery(): Boolean = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()

    /** Called before allowing library mutations. IDs are persisted before their import starts. */
    suspend fun recoverInterruptedRestore(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { recover() }
    }

    private suspend fun recover(): Boolean {
        if (!hasPendingRecovery()) { pruneStaging(); return false }
        val saved = JSONObject(journal.openRead().bufferedReader().use { it.readText() })
        val ids = saved.getJSONArray("ids")
        for (index in 0 until ids.length()) {
            db.library().book(ids.getString(index))?.let { books.remove(it) }
        }
        preferences.update { decodePreferences(saved.getJSONObject("preferences")) }
        journal.delete()
        pruneStaging()
        return true
    }

    private fun pruneStaging() {
        context.cacheDir.listFiles().orEmpty().filter {
            (it.isDirectory && it.name.matches(Regex("lumbre-restore-[a-f0-9-]{36}"))) ||
                (it.isFile && it.name.startsWith("lumbre-export-") && it.name.endsWith(".zip"))
        }.forEach { if (!it.deleteRecursively()) throw IOException("Cannot remove interrupted backup staging") }
    }

    private fun writeJournal(ids: Collection<String>, previous: ReaderPreferences) {
        val bytes = JSONObject().put("ids", JSONArray(ids.toList()))
            .put("preferences", encodePreferences(previous)).toString().toByteArray()
        val output = journal.startWrite()
        try { output.write(bytes); journal.finishWrite(output) }
        catch (error: Exception) { journal.failWrite(output); throw error }
    }

    private suspend fun copyCancellable(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(65536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    suspend fun export(uri: Uri, progress: (BackupProgress) -> Unit = {}) = withContext(Dispatchers.IO) { mutex.withLock {
        recover()
        val savedPreferences = preferences.preferences.first()
        val (records, entries) = db.withTransaction {
            val records = books.books.first()
            require(records.size <= 1000) { "A backup supports up to 1000 books" }
            val entries = JSONArray()
            records.forEach { book ->
                val item = JSONObject().put("checksum", book.checksum).put("title", book.title).put("chapter", book.currentChapter)
                    .put("position", book.readingPosition).put("offset", book.readingTextOffset)
                    .put("collection", book.collection).put("path", book.readingPath).put("added", book.dateAdded).put("lastRead", book.lastRead)
                val bookmarks = JSONArray()
                val savedBookmarks = db.library().bookmarks(book.id).first()
                require(savedBookmarks.size <= 10000) { "Too many bookmarks" }
                savedBookmarks.forEach {
                    bookmarks.put(JSONObject().put("chapter", it.chapter).put("position", it.position)
                        .put("offset", it.textOffset).put("path", it.resourcePath).put("title", it.title.take(1000)).put("created", it.createdAt))
                }
                val annotations = db.library().annotations(book.id).first()
                require(annotations.size <= 10000) { "Too many annotations" }
                item.put("annotations", JSONArray().apply { annotations.forEach {
                    put(JSONObject().put("chapter", it.chapter).put("path", it.resourcePath)
                        .put("start", it.startOffset).put("end", it.endOffset).put("quote", it.quote)
                        .put("note", it.note).put("created", it.createdAt).put("style", it.style).put("color", it.color))
                } })
                item.put("bookmarks", bookmarks)
                entries.put(item)
            }
            records to entries
        }
        progress(BackupProgress(0, records.size))
        val manifest = JSONObject().put("format", "lumbre-backup").put("version", 4)
            .put("preferences", encodePreferences(savedPreferences))
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
                records.forEachIndexed { index, record ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry("books/${record.checksum}.epub"))
                    val file = books.open(record.id).file
                    totalBytes += file.length()
                    require(totalBytes <= TOTAL_LIMIT) { "Backup exceeds 2 GiB" }
                    file.inputStream().use { copyCancellable(it, zip) }
                    progress(BackupProgress(index + 1, records.size))
                    zip.closeEntry()
                    require(temporary.length() <= TOTAL_LIMIT) { "Backup exceeds 2 GiB" }
                }
            }
            openBackupOutput(uri)?.use { output ->
                temporary.inputStream().use { copyCancellable(it, output) }
            } ?: throw IOException("Cannot create backup")
        } finally { temporary.delete() }
    } }

    suspend fun restore(uri: Uri, progress: (BackupProgress) -> Unit = {}): Int = withContext(Dispatchers.IO) { mutex.withLock {
        recover()
        val staging = File(context.cacheDir, "lumbre-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val imported = mutableListOf<BookEntity>()
        var journalWritten = false
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
            require(metadata.getString("format") == "lumbre-backup" && metadata.getInt("version") in 1..4) { "Unsupported backup version" }
            val items = metadata.getJSONArray("books")
            require(items.length() <= 1000) { "A backup supports up to 1000 books" }
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
                val notes = if (metadata.getInt("version") >= 2) item.getJSONArray("annotations") else JSONArray()
                require(notes.length() <= 10000) { "Too many annotations" }
                val source = archives.getValue(item.getString("checksum"))
                val record = BookEntity("validation", "", null, "", "", null, null, null, publication.chapters.size)
                val decodedNotes = (0 until notes.length()).map { decodeAnnotation(notes.getJSONObject(it), record.id, metadata.getInt("version")) }
                // Read each resource once, even when many annotations target the same chapter.
                decodedNotes.groupBy { it.resourcePath }.forEach { (path, group) ->
                    currentCoroutineContext().ensureActive()
                    require(publication.resources[path]?.mediaType in setOf("application/xhtml+xml", "text/html"))
                    val text = com.hector.epubreader.epub.renderer.EpubText.text(EpubParser().readResource(source, path))
                    group.forEach { books.validateAnnotation(it, OpenBook(record, publication, source), text) }
                }
                validateLocation(item)
                item.getString("title")
                require(metadata.getInt("version") < 4 || item.getString("collection").length <= 80)
                require(item.getLong("added") >= 0 && item.getLong("lastRead") >= 0)
                val marks = item.getJSONArray("bookmarks")
                require(marks.length() <= 10000) { "Too many bookmarks" }
                for (m in 0 until marks.length()) {
                    val mark = marks.getJSONObject(m)
                    validateLocation(mark)
                    require(mark.getString("title").length <= 1000 && mark.getLong("created") >= 0)
                }
            }
            // Reserve every ID durably before the first import; recovery also covers the
            // gap between Room insertion and the import returning to this coroutine.
            val plannedIds = checksums.associateWith { UUID.randomUUID().toString() }
            writeJournal(plannedIds.values, preferences.preferences.first())
            journalWritten = true
            progress(BackupProgress(0, items.length()))
            // All archives and structure have been validated before any library changes.
            for (index in 0 until items.length()) {
                currentCoroutineContext().ensureActive()
                val item = items.getJSONObject(index)
                val result = books.import(Uri.fromFile(archives.getValue(item.getString("checksum"))), plannedIds.getValue(item.getString("checksum")))
                if (!result.duplicate) imported += result.book
                currentCoroutineContext().ensureActive()
                if (result.duplicate) { progress(BackupProgress(index + 1, items.length())); continue }
                val book = result.book
                val publication = books.open(book.id).publication
                val chapter = item.getInt("chapter").coerceIn(publication.chapters.indices)
                val position = item.getDouble("position").toFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
                fun path(value: JSONObject): String? = value.optString("path").takeIf {
                    publication.resources[it]?.mediaType in setOf("application/xhtml+xml", "text/html")
                }
                db.withTransaction {
                    db.library().update(book.copy(title = item.getString("title"), sourceUri = "lumbre-backup", dateAdded = item.getLong("added"), lastRead = item.getLong("lastRead"), collection = if (metadata.getInt("version") >= 4) item.getString("collection").trim() else ""))
                    db.library().saveProgress(book.id, chapter, position, com.hector.epubreader.epub.ReadingProgress.fraction(chapter, position, book.chapterCount), item.getLong("lastRead"), offset(item), path(item))
                    val notes = if (metadata.getInt("version") >= 2) item.getJSONArray("annotations") else JSONArray()
                    for (n in 0 until notes.length()) db.library().saveAnnotation(decodeAnnotation(notes.getJSONObject(n), book.id, metadata.getInt("version")))
                    val marks = item.getJSONArray("bookmarks")
                    require(marks.length() <= 10000) { "Too many bookmarks" }
                    for (m in 0 until marks.length()) {
                        val mark = marks.getJSONObject(m)
                        val fraction = mark.getDouble("position").toFloat()
                        require(fraction.isFinite() && fraction in 0f..1f)
                        db.library().insertBookmark(BookmarkEntity(bookId = book.id, chapter = mark.getInt("chapter").coerceIn(publication.chapters.indices), position = fraction, title = mark.getString("title").take(1000), createdAt = mark.getLong("created"), textOffset = offset(mark), resourcePath = path(mark)))
                    }
                }
                progress(BackupProgress(index + 1, items.length()))
            }
            preferences.update { restoredPreferences }
            journal.delete()
            imported.size
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (journalWritten) try { recover() } catch (recovery: Exception) { error.addSuppressed(recovery) }
            }
            throw error
        } finally { staging.deleteRecursively() }
    } }

    private fun decodeAnnotation(json: JSONObject, bookId: String, version: Int) = AnnotationEntity(
        bookId = bookId, chapter = exactInt(json, "chapter"), resourcePath = json.getString("path"),
        startOffset = exactInt(json, "start"), endOffset = exactInt(json, "end"), quote = json.getString("quote"),
        note = json.getString("note"), createdAt = json.getLong("created"),
        style = if (version >= 3) json.getString("style") else "both",
        color = if (version >= 3) json.getString("color") else "yellow")

    private fun exactInt(json: JSONObject, key: String): Int {
        val value = json.getDouble(key)
        require(value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt().toDouble())
        return value.toInt()
    }

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
