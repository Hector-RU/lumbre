package com.hector.epubreader

import android.app.Application
import androidx.room.Room
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.PreferencesRepository
import com.hector.epubreader.epub.EpubParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReaderApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").build() }
    val books by lazy { BookRepository(this, database.library(), EpubParser()) }
    val preferences by lazy { PreferencesRepository(this) }
}
