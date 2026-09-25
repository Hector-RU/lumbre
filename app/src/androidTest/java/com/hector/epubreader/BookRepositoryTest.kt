package com.hector.epubreader

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.epub.EpubParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookRepositoryTest {
    @Test fun importsDeduplicatesSearchesAndRestoresPosition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        var id: String? = null
        try {
            val uri = Uri.parse("content://com.hector.epubreader.test.fixture/sample")
            val imported = repository.import(uri)
            id = imported.book.id
            assertFalse(imported.duplicate)
            assertEquals("La luz del puerto", imported.book.title)
            assertEquals("Biblioteca Lumbre", imported.book.author)
            assertNotNull(imported.book.coverPath)
            assertTrue(repository.import(uri).duplicate)
            val opened = repository.open(imported.book.id)
            assertEquals(2, opened.publication.chapters.size)
            assertEquals(2, repository.search(opened, "esperanza").size)
            repository.savePosition(imported.book.id, 1, 0.42f, 2)
            val restored = repository.open(imported.book.id)
            assertEquals(1, restored.record.currentChapter)
            assertEquals(0.42f, restored.record.readingPosition, 0.001f)
            repository.remove(imported.book)
            assertFalse(opened.file.exists())
            assertNull(db.library().book(imported.book.id))
        } finally {
            id?.let { db.library().book(it)?.let { book -> repository.remove(book) } }
            db.close()
        }
    }
}
