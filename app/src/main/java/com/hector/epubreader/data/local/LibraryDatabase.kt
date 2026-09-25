package com.hector.epubreader.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index(value = ["checksum"], unique = true)])
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val sourceUri: String,
    val checksum: String,
    val coverPath: String?,
    val language: String?,
    val publisher: String?,
    val chapterCount: Int,
    val currentChapter: Int = 0,
    val readingPosition: Float = 0f,
    val progress: Float = 0f,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastRead: Long = 0
)

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    val position: Float,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface LibraryDao {
    @Query("SELECT id FROM books") suspend fun bookIds(): List<String>
    @Query("SELECT * FROM books ORDER BY lastRead DESC, dateAdded DESC") fun observeBooks(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM books WHERE checksum = :checksum") suspend fun duplicate(checksum: String): BookEntity?
    @Insert suspend fun insert(book: BookEntity)
    @Delete suspend fun delete(book: BookEntity)
    @Query("UPDATE books SET currentChapter = :chapter, readingPosition = :position, progress = :progress, lastRead = :timestamp WHERE id = :id")
    suspend fun saveProgress(id: String, chapter: Int, position: Float, progress: Float, timestamp: Long)
    @Query("UPDATE books SET lastRead = 0") suspend fun clearHistory()
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapter, position") fun bookmarks(bookId: String): Flow<List<BookmarkEntity>>
    @Insert suspend fun insertBookmark(bookmark: BookmarkEntity)
    @Delete suspend fun deleteBookmark(bookmark: BookmarkEntity)
}

@Database(entities = [BookEntity::class, BookmarkEntity::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
