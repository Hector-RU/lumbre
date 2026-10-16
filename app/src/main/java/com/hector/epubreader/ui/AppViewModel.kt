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
import kotlinx.coroutines.Job
import android.system.ErrnoException
import android.system.OsConstants
import com.hector.epubreader.data.BackupProgress

data class LibraryUiState(val books: List<BookEntity> = emptyList(), val loading: Boolean = true, val importing: Boolean = false, @param:StringRes val message: Int? = null, val backupBusy: Boolean = false, val backupProgress: BackupProgress = BackupProgress(), val recoveryFailed: Boolean = false)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ReaderApplication
    private val repository = app.books
    private val _library = MutableStateFlow(LibraryUiState(backupBusy = true))
    val library = _library.asStateFlow()
    private val _preferences = MutableStateFlow(ReaderPreferences())
    val preferences = _preferences.asStateFlow()
    private val _preferencesLoaded = MutableStateFlow(false)
    val preferencesLoaded = _preferencesLoaded.asStateFlow()

    private var backupJob: Job? = null
    private var storageReady = false

    init {
        viewModelScope.launch {
            prepareStorage()
            repository.books.catch { report(it) }.collect { list -> _library.update { it.copy(books = list, loading = false) } }
        }
        viewModelScope.launch {
            app.preferences.preferences.catch { report(it) }.collect {
                _preferences.value = it
                _preferencesLoaded.value = true
            }
        }
    }

    private suspend fun prepareStorage() {
        _library.update { it.copy(backupBusy = true, recoveryFailed = false) }
        try {
            app.backup.recoverInterruptedRestore()
            repository.pruneOrphans()
            storageReady = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { report(e) }
        _library.update { it.copy(backupBusy = !storageReady, recoveryFailed = !storageReady) }
    }
    fun retryRecovery() = viewModelScope.launch { if (!storageReady) prepareStorage() }

    fun import(uri: Uri) {
        if (_library.value.importing || _library.value.backupBusy) return
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
    fun setCollection(book: BookEntity, name: String) = action { repository.setCollection(book, name) }
    fun clearHistory() = action { repository.clearHistory() }
    fun exportBackup(uri: Uri) = backupAction { app.backup.export(uri, ::backupProgress); R.string.backup_exported }
    fun restoreBackup(uri: Uri) = backupAction { app.backup.restore(uri, ::backupProgress); R.string.backup_restored }
    private fun backupProgress(value: BackupProgress) { _library.update { it.copy(backupProgress = value) } }
    fun cancelBackup() { backupJob?.cancel() }
    private fun backupAction(block: suspend () -> Int) {
        if (_library.value.backupBusy || _library.value.importing) return
        _library.update { it.copy(backupBusy = true, backupProgress = BackupProgress()) }
        backupJob = viewModelScope.launch {
            try { val message = block(); _library.update { it.copy(message = message) } }
            catch (e: CancellationException) { _library.update { it.copy(message = R.string.backup_cancelled) }; throw e }
            catch (e: Exception) {
                val causes = generateSequence<Throwable>(e) { it.cause }.take(20).toList()
                val message = when {
                    causes.any { it is SecurityException } -> R.string.access_error
                    causes.any { it is ErrnoException && it.errno == OsConstants.ENOSPC } -> R.string.backup_no_space
                    e is IllegalArgumentException || e is org.json.JSONException || e is com.hector.epubreader.epub.InvalidEpub || e is UnsupportedEpub || e is java.util.zip.ZipException -> R.string.backup_invalid
                    else -> R.string.backup_error
                }
                _library.update { it.copy(message = message) }
            }
            finally {
                val pending = app.backup.hasPendingRecovery()
                if (pending) storageReady = false
                _library.update { it.copy(backupBusy = pending, recoveryFailed = pending) }
            }
        }
    }
    fun clearMessage() { _library.update { it.copy(message = null) } }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        if (_library.value.backupBusy) return@launch
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
