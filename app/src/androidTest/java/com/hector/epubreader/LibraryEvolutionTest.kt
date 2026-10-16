package com.hector.epubreader

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.LibraryBackup
import com.hector.epubreader.data.local.*
import com.hector.epubreader.epub.EpubParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

class LibraryEvolutionTest {
    @Test fun migratesVersionOneWithoutLosingBooksOrBookmarks() = migrate(1)
    @Test fun migratesVersionTwoWithoutLosingBooksOrBookmarks() = migrate(2)
    @Test fun migratesVersionThreeAndPreservesExistingAnnotationAppearance() = migrate(3)

    @Test fun migratesVersionFourWithoutLosingAnnotations() = migrate(4)

    private fun migrate(version: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("com.hector.epubreader.data.local.LibraryDatabase/$version.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            sqlite.execSQL("INSERT INTO books (id,title,author,sourceUri,checksum,coverPath,language,publisher,chapterCount,currentChapter,readingPosition,progress,dateAdded,lastRead) VALUES ('old','Title',NULL,'content://old','hash',NULL,NULL,NULL,2,1,0.4,0.7,1,2)")
            sqlite.execSQL("INSERT INTO bookmarks (id,bookId,chapter,position,title,createdAt) VALUES (1,'old',1,0.4,'Mark',3)")
            if (version >= 3) sqlite.execSQL("INSERT INTO annotations (id,bookId,chapter,resourcePath,startOffset,endOffset,quote,note,createdAt) VALUES ('old-mark','old',1,'notes.xhtml',10,14,'Note','Original note',4)")
            sqlite.version = version
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5).build()
        try {
            val book = requireNotNull(db.library().book("old"))
            assertEquals(0.4f, book.readingPosition, 0.001f)
            assertNull(book.readingTextOffset)
            assertEquals("Mark", db.library().bookmarks("old").first().single().title)
            db.library().saveProgress("old", 1, 0.5f, 0.75f, 4, 1234, "notes.xhtml")
            assertEquals(1234, db.library().book("old")!!.readingTextOffset)
            if (version >= 3) {
                val annotation = db.library().annotations("old").first().single()
                assertEquals("Original note", annotation.note)
                assertEquals("both", annotation.style)
                assertEquals("yellow", annotation.color)
            }
            db.library().saveAnnotation(AnnotationEntity(bookId = "old", chapter = 1, resourcePath = "notes.xhtml", startOffset = 10, endOffset = 14, quote = "Note", note = "Saved"))
            assertEquals("Saved", db.library().annotations("old").first().first { it.id != "old-mark" }.note)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun backupRoundTripPreservesLocatorsAndKeepsDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = (context.applicationContext as ReaderApplication).preferences
        val original = preferences.preferences.first()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val books = BookRepository(context, db.library(), EpubParser())
        val backup = LibraryBackup(context, db, books, preferences)
        val file = File.createTempFile("backup-test-", ".zip", context.cacheDir)
        try {
            val imported = books.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
            db.library().update(imported.copy(title = "Saved fallback title", collection = "Lecturas"))
            books.savePosition(imported.id, 1, 0.4f, 2, 100)
            books.addBookmark(BookmarkEntity(bookId = imported.id, chapter = 1, position = 0.4f, title = "Test", textOffset = 100))
            val opened = books.open(imported.id)
            val chapterPath = opened.publication.chapters[0].path
            val canonical = com.hector.epubreader.epub.renderer.EpubText.text(EpubParser().readResource(opened.file, chapterPath))
            val start = canonical.indexOfFirst { !it.isWhitespace() }
            val annotation = AnnotationEntity(bookId = imported.id, chapter = 0, resourcePath = chapterPath, startOffset = start, endOffset = start + 12, quote = canonical.substring(start, start + 12), note = "Una nota con acentos y 😀", style = "underline", color = "purple")
            books.saveAnnotation(annotation)
            try { books.saveAnnotation(annotation.copy(endOffset = Int.MAX_VALUE)); fail("Invalid range accepted") } catch (_: IllegalArgumentException) { }
            preferences.update { it.copy(fontSize = 29f, readingMode = "paragraphs") }
            backup.export(Uri.fromFile(file))
            books.remove(imported)
            assertTrue(db.library().annotations(imported.id).first().isEmpty())
            preferences.update { original }
            assertEquals(1, backup.restore(Uri.fromFile(file)))
            val restored = books.books.first().single()
            val restoredNote = db.library().annotations(restored.id).first().single()
            assertEquals(annotation.quote, restoredNote.quote)
            assertEquals(annotation.note, restoredNote.note)
            assertEquals("underline", restoredNote.style)
            assertEquals("purple", restoredNote.color)
            assertEquals(annotation.startOffset, restoredNote.startOffset)
            books.saveAnnotation(restoredNote.copy(note = "Edited after restore"))
            assertEquals("Saved fallback title", restored.title)
            assertEquals("Lecturas", restored.collection)
            assertEquals(100, restored.readingTextOffset)
            assertEquals(100, db.library().bookmarks(restored.id).first().single().textOffset)
            assertEquals(29f, preferences.preferences.first().fontSize, 0.001f)
            books.savePosition(restored.id, 0, 0.1f, 2, 50)
            assertEquals(0, backup.restore(Uri.fromFile(file)))
            assertEquals(50, db.library().book(restored.id)!!.readingTextOffset)
            assertEquals("Edited after restore", db.library().annotations(restored.id).first().single().note)
            books.remove(restored)
            fun rewriteManifest(change: (JSONObject) -> Unit) {
                val entries = ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } } }.toMutableMap()
                val metadata = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
                change(metadata)
                entries["manifest.json"] = metadata.toString().toByteArray()
                ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
            }
            rewriteManifest { it.getJSONArray("books").getJSONObject(0).getJSONArray("annotations").getJSONObject(0).put("end", 4294967296L + annotation.endOffset) }
            try { backup.restore(Uri.fromFile(file)); fail("Overflowing annotation range accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(books.books.first().isEmpty())
            rewriteManifest {
                val note = it.getJSONArray("books").getJSONObject(0).getJSONArray("annotations").getJSONObject(0)
                note.put("end", annotation.endOffset).put("color", "red; background:url(https://example.com)")
            }
            try { backup.restore(Uri.fromFile(file)); fail("Unknown color accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(books.books.first().isEmpty())
            rewriteManifest {
                it.put("version", 2)
                val note = it.getJSONArray("books").getJSONObject(0).getJSONArray("annotations").getJSONObject(0)
                note.remove("style"); note.remove("color")
            }
            assertEquals(1, backup.restore(Uri.fromFile(file)))
            val versionTwoBook = books.books.first().single()
            val versionTwoNote = db.library().annotations(versionTwoBook.id).first().single()
            assertEquals("both", versionTwoNote.style)
            assertEquals("yellow", versionTwoNote.color)
            books.remove(versionTwoBook)
            rewriteManifest { it.put("version", 1); it.getJSONArray("books").getJSONObject(0).remove("annotations") }
            assertEquals(1, backup.restore(Uri.fromFile(file)))
            val legacyRestored = books.books.first().single()
            assertEquals(100, legacyRestored.readingTextOffset)
            assertTrue(db.library().annotations(legacyRestored.id).first().isEmpty())
        } finally {
            books.books.first().forEach { books.remove(it) }
            preferences.update { original }; file.delete(); db.close()
        }
    }

    @Test fun interruptedRestoreRecoveryIsIdempotentAndKeepsOtherBooks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = (context.applicationContext as ReaderApplication).preferences
        val original = preferences.preferences.first()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val books = BookRepository(context, db.library(), EpubParser())
        val file = File.createTempFile("recovery-test-", ".zip", context.cacheDir)
        val journal = File(context.filesDir, "restore-journal.json")
        try {
            val book = books.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
            LibraryBackup(context, db, books, preferences).export(Uri.fromFile(file))
            val saved = ZipFile(file).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }) }
            val unrelated = book.copy(id = UUID.randomUUID().toString(), checksum = "unrelated")
            db.library().insert(unrelated)
            journal.writeText(JSONObject().put("ids", org.json.JSONArray().put(book.id))
                .put("preferences", saved.getJSONObject("preferences")).toString())
            preferences.update { it.copy(fontSize = 35f) }
            val restarted = LibraryBackup(context, db, books, preferences)
            assertTrue(restarted.recoverInterruptedRestore())
            assertNull(db.library().book(book.id))
            assertNotNull(db.library().book(unrelated.id))
            assertEquals(original.fontSize, preferences.preferences.first().fontSize, 0f)
            assertFalse(restarted.recoverInterruptedRestore())
        } finally {
            db.library().bookIds().forEach { db.library().book(it)?.let { record -> db.library().delete(record) } }
            preferences.update { original }; journal.delete(); file.delete(); db.close()
        }
    }

    @Test fun cancellationAfterImportRollsBackBooksAndPreferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = (context.applicationContext as ReaderApplication).preferences
        val original = preferences.preferences.first()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val books = BookRepository(context, db.library(), EpubParser())
        val backup = LibraryBackup(context, db, books, preferences)
        val file = File.createTempFile("cancel-test-", ".zip", context.cacheDir)
        try {
            val book = books.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
            backup.export(Uri.fromFile(file)); books.remove(book)
            try {
                backup.restore(Uri.fromFile(file)) { if (it.completed == 1) throw kotlinx.coroutines.CancellationException("Test cancellation") }
                fail("Restore was not cancelled")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(books.books.first().isEmpty())
            assertEquals(original, preferences.preferences.first())
            assertEquals(1, backup.restore(Uri.fromFile(file)))
        } finally {
            books.books.first().forEach { books.remove(it) }
            preferences.update { original }; file.delete(); db.close()
        }
    }

    @Test fun repeatedSearchReturnsIdenticalLocationsAndReportsTiming() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val books = BookRepository(context, db.library(), EpubParser())
        val record = books.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        try {
            val book = books.open(record.id)
            val canonical = books.canonicalText(book, book.publication.chapters.first().path)
            val query = Regex("[\\p{L}]{4,}").find(canonical)!!.value
            val start = System.nanoTime()
            val first = books.search(book, query)
            val firstMs = (System.nanoTime() - start) / 1_000_000
            val repeatStart = System.nanoTime()
            val repeated = books.search(book, query)
            val repeatMs = (System.nanoTime() - repeatStart) / 1_000_000
            assertTrue(first.isNotEmpty())
            assertEquals(first, repeated)
            println("search firstMs=$firstMs repeatedMs=$repeatMs hits=${first.size}")
        } finally { books.remove(record); db.close() }
    }

    @Test fun failedDestinationWriteKeepsLibraryAndRemovesTemporaryArchive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = (context.applicationContext as ReaderApplication).preferences
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val books = BookRepository(context, db.library(), EpubParser())
        val before = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val backup = LibraryBackup(context, db, books, preferences, openBackupOutput = {
            object : java.io.OutputStream() {
                override fun write(value: Int) { throw java.io.IOException("No space", android.system.ErrnoException("write", android.system.OsConstants.ENOSPC)) }
            }
        })
        try {
            val book = books.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
            try { backup.export(Uri.parse("content://failed/destination")); fail("Failed write reported success") }
            catch (error: java.io.IOException) { assertEquals(android.system.OsConstants.ENOSPC, (error.cause as android.system.ErrnoException).errno) }
            assertEquals(book.id, books.books.first().single().id)
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("lumbre-export-") && it.name !in before })
        } finally { books.books.first().forEach { books.remove(it) }; db.close() }
    }

    @Test fun rejectsUnsafeBackupBeforeChangingTheLibrary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = (context.applicationContext as ReaderApplication).preferences
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val books = BookRepository(context, db.library(), EpubParser())
        val file = File.createTempFile("invalid-backup-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../escape.epub")); zip.write(byteArrayOf(1)); zip.closeEntry() }
            try { LibraryBackup(context, db, books, preferences).restore(Uri.fromFile(file)); fail("Traversal must be rejected") }
            catch (error: Exception) { assertTrue(error is IllegalArgumentException || error is java.io.IOException) }
            assertTrue(books.books.first().isEmpty())
        } finally { file.delete(); db.close() }
    }
}
