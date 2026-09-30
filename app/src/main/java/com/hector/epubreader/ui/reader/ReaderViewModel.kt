package com.hector.epubreader.ui.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hector.epubreader.ReaderApplication
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.SearchHit
import com.hector.epubreader.data.local.BookmarkEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ReaderLocation(val chapter: Int = 0, val position: Float = 0f, val fragment: String? = null, val query: String? = null, val generation: Int = 0)
data class ReaderUiState(val book: OpenBook? = null, val loading: Boolean = true, val error: Boolean = false, val location: ReaderLocation = ReaderLocation())
data class ReaderSearchState(val query: String = "", val searching: Boolean = false, val hits: List<SearchHit> = emptyList(), val error: Boolean = false)

class ReaderViewModel(private val app: ReaderApplication, private val id: String, private val repository: BookRepository = app.books) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state = _state.asStateFlow()
    private val _position = MutableStateFlow(0f)
    val position = _position.asStateFlow()
    val bookmarks = repository.bookmarks(id).catch { fail(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _search = MutableStateFlow(ReaderSearchState())
    val search = _search.asStateFlow()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private data class Save(val chapter: Int, val position: Float, val count: Int)
    private val saves = Channel<Save>(Channel.CONFLATED)
    private var dirty = false
    private var pendingTargetGeneration: Int? = null

    init {
        // The application-owned writer drains final positions even when this ViewModel is cleared.
        app.applicationScope.launch {
            for (save in saves) {
                try { repository.savePosition(id, save.chapter, save.position, save.count) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e) }
            }
        }
        load()
        viewModelScope.launch { while (isActive) { delay(1000); flush() } }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val book = repository.open(id)
                _position.value = book.record.readingPosition
                _state.value = ReaderUiState(book, loading = false, location = ReaderLocation(book.record.currentChapter.coerceIn(book.publication.chapters.indices), book.record.readingPosition))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    fun navigate(chapter: Int, position: Float = 0f, fragment: String? = null, query: String? = null) {
        val book = _state.value.book ?: return
        if (chapter !in book.publication.chapters.indices) return
        flush()
        _position.value = position
        _state.update { it.copy(location = ReaderLocation(chapter, position, fragment, query, it.location.generation + 1), error = false) }
        pendingTargetGeneration = _state.value.location.generation
        dirty = true
        flush()
    }

    fun positionChanged(position: Float) { if (position.isFinite()) { _position.value = position.coerceIn(0f, 1f); dirty = true } }
    fun consumeNavigationTarget(generation: Int): Boolean {
        val pending = pendingTargetGeneration == generation
        if (pending) pendingTargetGeneration = null
        return pending
    }
    fun flush() {
        val book = _state.value.book ?: return
        if (!dirty) return
        saves.trySend(Save(_state.value.location.chapter, _position.value, book.publication.chapters.size))
        dirty = false
    }
    fun renderError() { _state.update { it.copy(error = true, loading = false) } }
    fun currentBookmark(): BookmarkEntity? = bookmarks.value.firstOrNull { it.chapter == _state.value.location.chapter && kotlin.math.abs(it.position - _position.value) < 0.02f }
    fun toggleBookmark() = viewModelScope.launch {
        val state = _state.value
        val book = state.book ?: return@launch
        try {
            val existing = currentBookmark()
            if (existing != null) repository.removeBookmark(existing)
            else repository.addBookmark(BookmarkEntity(bookId = id, chapter = state.location.chapter, position = _position.value, title = book.publication.chapters[state.location.chapter].title))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
    }
    fun removeBookmark(bookmark: BookmarkEntity) = viewModelScope.launch {
        try { repository.removeBookmark(bookmark) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
    }
    fun search(query: String) {
        searchJob?.cancel()
        _search.value = ReaderSearchState(query, searching = query.trim().length >= 2)
        if (query.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(350)
            try {
                val book = _state.value.book ?: return@launch
                val hits = repository.search(book, query)
                _search.value = ReaderSearchState(query, hits = hits)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("ReaderSearch", "Search failed: ${e.javaClass.simpleName}")
                _search.value = ReaderSearchState(query, error = true)
            }
        }
    }
    private fun fail(e: Throwable) { Log.w("Reader", "Operation failed: ${e.javaClass.simpleName}"); renderError() }
    override fun onCleared() { flush(); saves.close(); super.onCleared() }
}
