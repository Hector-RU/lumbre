package com.hector.epubreader.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    val lastRead: Long = 0,
    val readingTextOffset: Int? = null,
    val readingPath: String? = null,
    @ColumnInfo(defaultValue = "''") val collection: String = ""
)

@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    val position: Float,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val textOffset: Int? = null,
    val resourcePath: String? = null
)

@Entity(tableName = "annotations", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val bookId: String, val chapter: Int, val resourcePath: String,
    val startOffset: Int, val endOffset: Int, val quote: String,
    val note: String = "", val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'both'") val style: String = "both",
    @ColumnInfo(defaultValue = "'yellow'") val color: String = "yellow"
)

@Dao
interface LibraryDao {
    @Query("SELECT id FROM books") suspend fun bookIds(): List<String>
    @Query("SELECT * FROM books ORDER BY lastRead DESC, dateAdded DESC") fun observeBooks(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM books WHERE checksum = :checksum") suspend fun duplicate(checksum: String): BookEntity?
    @Insert suspend fun insert(book: BookEntity)
    @Update suspend fun update(book: BookEntity)
    @Delete suspend fun delete(book: BookEntity)
    @Query("UPDATE books SET currentChapter = :chapter, readingPosition = :position, progress = :progress, lastRead = :timestamp, readingTextOffset = :textOffset, readingPath = :path WHERE id = :id")
    suspend fun saveProgress(id: String, chapter: Int, position: Float, progress: Float, timestamp: Long, textOffset: Int? = null, path: String? = null)
    @Query("UPDATE books SET collection = :collection WHERE id = :id") suspend fun setCollection(id: String, collection: String)
    @Query("UPDATE books SET lastRead = 0") suspend fun clearHistory()
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapter, position") fun bookmarks(bookId: String): Flow<List<BookmarkEntity>>
    @Insert suspend fun insertBookmark(bookmark: BookmarkEntity)
    @Delete suspend fun deleteBookmark(bookmark: BookmarkEntity)
    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY chapter, resourcePath, startOffset") fun annotations(bookId: String): Flow<List<AnnotationEntity>>
    @Upsert suspend fun saveAnnotation(annotation: AnnotationEntity)
    @Delete suspend fun deleteAnnotation(annotation: AnnotationEntity)
}

@Database(entities = [BookEntity::class, BookmarkEntity::class, AnnotationEntity::class], version = 5, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN collection TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE annotations ADD COLUMN style TEXT NOT NULL DEFAULT 'both'")
                db.execSQL("ALTER TABLE annotations ADD COLUMN color TEXT NOT NULL DEFAULT 'yellow'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS annotations (id TEXT NOT NULL, bookId TEXT NOT NULL, chapter INTEGER NOT NULL, resourcePath TEXT NOT NULL, startOffset INTEGER NOT NULL, endOffset INTEGER NOT NULL, quote TEXT NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations(bookId)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readingTextOffset INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN readingPath TEXT")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN textOffset INTEGER")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN resourcePath TEXT")
            }
        }
    }
}
