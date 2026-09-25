package com.hector.epubreader

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryDaoTest {
    @Test fun progressSurvivesQueryAndBookmarksCascade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            val book = BookEntity("id", "Título", "Autor", "content://test/book", "checksum", null, "es", null, 4)
            dao.insert(book)
            dao.saveProgress(book.id, 2, 0.5f, 0.625f, 100)
            val restored = requireNotNull(dao.book(book.id))
            assertEquals(2, restored.currentChapter)
            assertEquals(0.5f, restored.readingPosition)
            dao.insertBookmark(BookmarkEntity(bookId = book.id, chapter = 2, position = 0.5f, title = "Capítulo"))
            assertEquals(1, dao.bookmarks(book.id).first().size)
            dao.delete(book)
            assertTrue(dao.bookmarks(book.id).first().isEmpty())
        } finally { db.close() }
    }
}
