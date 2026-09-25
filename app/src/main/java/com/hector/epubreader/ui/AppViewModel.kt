package com.hector.epubreader.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hector.epubreader.R
import com.hector.epubreader.ReaderApplication
import com.hector.epubreader.data.local.BookEntity
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.InvalidEpub
import com.hector.epubreader.epub.UnsupportedEpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryUiState(val books: List<BookEntity> = emptyList(), val loading: Boolean = true, val importing: Boolean = false, @param:StringRes val message: Int? = null)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ReaderApplication
    private val repository = app.books
    private val _library = MutableStateFlow(LibraryUiState())
    val library = _library.asStateFlow()
    private val _preferences = MutableStateFlow(ReaderPreferences())
    val preferences = _preferences.asStateFlow()
    private val _preferencesLoaded = MutableStateFlow(false)
    val preferencesLoaded = _preferencesLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            try { repository.pruneOrphans() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            repository.books.catch { report(it) }.collect { list -> _library.update { it.copy(books = list, loading = false) } }
        }
        viewModelScope.launch {
            app.preferences.preferences.catch { report(it) }.collect {
                _preferences.value = it
                _preferencesLoaded.value = true
            }
        }
    }

    fun import(uri: Uri) {
        if (_library.value.importing) return
        _library.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val result = repository.import(uri)
                _library.update { it.copy(message = if (result.duplicate) R.string.already_imported else R.string.import_success) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(e)
            } finally { _library.update { it.copy(importing = false) } }
        }
    }
    fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) = viewModelScope.launch {
        try { app.preferences.update(transform) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { report(e) }
    }
    fun remove(book: BookEntity) = action { repository.remove(book) }
    fun reset(book: BookEntity, completed: Boolean) = action { repository.reset(book, completed) }
    fun clearHistory() = action { repository.clearHistory() }
    fun clearMessage() { _library.update { it.copy(message = null) } }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    }
    private fun report(error: Throwable) {
        Log.w("Library", "Operation failed: ${error.javaClass.simpleName}")
        _library.update { it.copy(loading = false, message = when(error) {
            is UnsupportedEpub -> R.string.unsupported_epub
            is InvalidEpub -> R.string.invalid_epub
            is SecurityException -> R.string.access_error
            else -> R.string.storage_error
        }) }
    }
}
