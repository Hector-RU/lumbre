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

class LibraryEvolutionTest {
    @Test fun migratesVersionOneWithoutLosingBooksOrBookmarks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("com.hector.epubreader.data.local.LibraryDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            sqlite.execSQL("INSERT INTO books VALUES ('old','Title',NULL,'content://old','hash',NULL,NULL,NULL,2,1,0.4,0.7,1,2)")
            sqlite.execSQL("INSERT INTO bookmarks VALUES (1,'old',1,0.4,'Mark',3)")
            sqlite.version = 1
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2).build()
        try {
            val book = requireNotNull(db.library().book("old"))
            assertEquals(0.4f, book.readingPosition, 0.001f)
            assertNull(book.readingTextOffset)
            assertEquals("Mark", db.library().bookmarks("old").first().single().title)
            db.library().saveProgress("old", 1, 0.5f, 0.75f, 4, 1234, "notes.xhtml")
            assertEquals(1234, db.library().book("old")!!.readingTextOffset)
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
            db.library().update(imported.copy(title = "Saved fallback title"))
            books.savePosition(imported.id, 1, 0.4f, 2, 100)
            books.addBookmark(BookmarkEntity(bookId = imported.id, chapter = 1, position = 0.4f, title = "Test", textOffset = 100))
            preferences.update { it.copy(fontSize = 29f, readingMode = "paragraphs") }
            backup.export(Uri.fromFile(file))
            books.remove(imported)
            preferences.update { original }
            assertEquals(1, backup.restore(Uri.fromFile(file)))
            val restored = books.books.first().single()
            assertEquals("Saved fallback title", restored.title)
            assertEquals(100, restored.readingTextOffset)
            assertEquals(100, db.library().bookmarks(restored.id).first().single().textOffset)
            assertEquals(29f, preferences.preferences.first().fontSize, 0.001f)
            books.savePosition(restored.id, 0, 0.1f, 2, 50)
            assertEquals(0, backup.restore(Uri.fromFile(file)))
            assertEquals(50, db.library().book(restored.id)!!.readingTextOffset)
        } finally {
            books.books.first().forEach { books.remove(it) }
            preferences.update { original }; file.delete(); db.close()
        }
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
