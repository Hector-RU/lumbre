package com.hector.epubreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hector.epubreader.data.local.*
import com.hector.epubreader.epub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class OpenBook(val record: BookEntity, val publication: EpubPublication, val file: File)
data class SearchHit(val chapter: Int, val title: String, val snippet: String, val query: String)
data class ImportResult(val book: BookEntity, val duplicate: Boolean)

class BookRepository(private val context: Context, private val dao: LibraryDao, private val parser: EpubParser) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val importMutex = Mutex()
    val books = dao.observeBooks()

    suspend fun pruneOrphans() = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val retained = dao.bookIds().toSet()
            root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) && it.name !in retained }?.forEach {
                if (!it.deleteRecursively()) throw IOException("Cannot remove unfinished import")
            }
        }
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "EPUB"
            if (!name.endsWith(".epub", ignoreCase = true) && resolver.getType(uri) != "application/epub+zip") throw InvalidEpub("Not an EPUB")
            val id = UUID.randomUUID().toString()
            val folder = File(root, id).apply { mkdirs() }
            var committed = false
            try {
                val archive = File(folder, "book.epub")
                val digest = MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 512L * 1024 * 1024) throw InvalidEpub("EPUB exceeds 512 MiB")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IOException("Cannot open document")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val duplicate = dao.duplicate(hash)
                if (duplicate != null) return@withLock ImportResult(duplicate, true)
                val epub = parser.parse(archive, name.substringBeforeLast('.'))
                val cover = epub.coverPath?.let { path ->
                    // Unsupported cover formats retain the generated cover instead.
                    if (epub.resources[path]?.mediaType !in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) null
                    else File(folder, "cover").apply { writeBytes(parser.readResource(archive, path, 16 * 1024 * 1024)) }.absolutePath
                }
                val record = BookEntity(id, epub.title, epub.author, uri.toString(), hash, cover, epub.language, epub.publisher, epub.chapters.size)
                withContext(NonCancellable) {
                    dao.insert(record)
                    committed = true
                }
                ImportResult(record, false)
            } finally {
                if (!committed) folder.deleteRecursively()
            }
        }
    }

    suspend fun open(id: String): OpenBook = withContext(Dispatchers.IO) {
        val record = dao.book(id) ?: throw IOException("Book unavailable")
        val file = File(folder(id), "book.epub")
        OpenBook(record, parser.parse(file, record.title), file)
    }

    suspend fun savePosition(id: String, chapter: Int, position: Float, count: Int) {
        val safePosition = position.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        dao.saveProgress(id, chapter.coerceIn(0, (count - 1).coerceAtLeast(0)), safePosition, ReadingProgress.fraction(chapter, safePosition, count), System.currentTimeMillis())
    }

    suspend fun reset(book: BookEntity, completed: Boolean) = dao.saveProgress(book.id, if (completed) book.chapterCount - 1 else 0, if (completed) 1f else 0f, if (completed) 1f else 0f, book.lastRead)
    suspend fun remove(book: BookEntity) = withContext(Dispatchers.IO) {
        dao.delete(book)
        if (!folder(book.id).deleteRecursively()) throw IOException("Could not remove local files")
    }
    suspend fun clearHistory() = dao.clearHistory()
    fun bookmarks(id: String) = dao.bookmarks(id)
    suspend fun addBookmark(bookmark: BookmarkEntity) = dao.insertBookmark(bookmark)
    suspend fun removeBookmark(bookmark: BookmarkEntity) = dao.deleteBookmark(bookmark)

    suspend fun search(book: OpenBook, query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val term = query.trim()
        val hits = mutableListOf<SearchHit>()
        book.publication.chapters.forEachIndexed { index, chapter ->
            currentCoroutineContext().ensureActive()
            val bytes = parser.readResource(book.file, chapter.path)
            val text = Jsoup.parse(bytes.inputStream(), null, "").body().text()
            val match = text.indexOf(term, ignoreCase = true)
            if (match >= 0) hits += SearchHit(index, chapter.title, text.substring((match - 55).coerceAtLeast(0), (match + term.length + 100).coerceAtMost(text.length)), term)
        }
        hits
    }

    private fun folder(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, id)
    }
}
