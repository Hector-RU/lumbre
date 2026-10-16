package com.hector.epubreader.ui.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hector.epubreader.ReaderApplication
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.SearchHit
import com.hector.epubreader.data.local.BookmarkEntity
import com.hector.epubreader.data.local.AnnotationEntity
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.epub.renderer.EpubText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ReaderLocation(val chapter: Int = 0, val position: Float = 0f, val fragment: String? = null, val query: String? = null, val generation: Int = 0, val textOffset: Int? = null, val resourcePath: String? = null)
data class ReaderUiState(val book: OpenBook? = null, val loading: Boolean = true, val error: Boolean = false, val location: ReaderLocation = ReaderLocation())
data class ReaderSearchState(val query: String = "", val searching: Boolean = false, val hits: List<SearchHit> = emptyList(), val error: Boolean = false)

class ReaderViewModel(private val app: ReaderApplication, private val id: String, private val repository: BookRepository = app.books, speechEnginePackage: String? = null) : ViewModel() {
    private var speechJob: Job? = null
    private var speechSession = false
    private var speechHasPosition = false
    private var speechAnchorPending = false
    private var speechChapter = 0
    private var speechPath: String? = null
    private var speechLength = 1
    private val speechDelegate = lazy { ReaderSpeech(app, progress = { offset, chunk ->
        if (chunk) navigate(speechChapter, textOffset = offset, resourcePath = speechPath, fromSpeech = true)
        textOffset = offset
        _position.value = (offset.toFloat() / speechLength).coerceIn(0f, 1f)
        dirty = true
    }, finished = {
        val book = _state.value.book
        if (speechSession && speechPath == null && book != null && speechChapter < book.publication.chapters.lastIndex) startSpeech(speechChapter + 1, null, 0)
        else { speechSession = false; flush() }
    }, enginePackage = speechEnginePackage, interrupted = { pauseSpeech() }) }
    private val speech get() = speechDelegate.value
    private val _speechState = MutableStateFlow(SpeechState())
    val speechState = _speechState.asStateFlow()
    private var speechObservation: Job? = null
    fun prepareSpeech() {
        val engine = speech
        if (speechObservation == null) speechObservation = viewModelScope.launch { engine.state.collect { _speechState.value = it } }
    }
    val exportingAnnotations = MutableStateFlow(false)
    val exportMessage = MutableStateFlow<Int?>(null)

    fun exportAnnotations(uri: android.net.Uri) {
        if (exportingAnnotations.value) return
        val book = _state.value.book ?: return
        val marks = annotations.value.toList()
        exportingAnnotations.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val markdown = com.hector.epubreader.data.AnnotationExport.markdown(book.record.title, book.record.author, book.publication.chapters.map { it.title }, marks)
                    app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(markdown) }
                        ?: throw java.io.IOException("Cannot create export")
                }
                exportMessage.value = com.hector.epubreader.R.string.annotations_exported
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { exportMessage.value = com.hector.epubreader.R.string.annotation_export_error }
            finally { exportingAnnotations.value = false }
        }
    }

    fun playSpeech() {
        prepareSpeech()
        val location = _state.value.location
        startSpeech(location.chapter, location.resourcePath, textOffset)
    }

    private fun startSpeech(chapter: Int, path: String?, offset: Int?) {
        speechJob?.cancel()
        speechSession = true
        speechHasPosition = false
        speechJob = viewModelScope.launch {
            try {
                val book = _state.value.book ?: return@launch
                val content = withContext(Dispatchers.IO) { repository.canonicalText(book, path ?: book.publication.chapters[chapter].path) }
                ensureActive()
                speechChapter = chapter; speechPath = path; speechLength = content.length.coerceAtLeast(1)
                val start = offset ?: if (chapter == _state.value.location.chapter) (_position.value * content.length).toInt() else 0
                speechHasPosition = true
                speech.play(content, start, book.publication.language)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pauseSpeech(); speech.fail() }
        }
    }

    fun pauseSpeech() {
        if (!speechDelegate.isInitialized()) { flush(); return }
        val wasPlaying = speech.state.value.playing || (speechSession && speechHasPosition)
        speechSession = false
        speechJob?.cancel()
        speech.pause()
        if (wasPlaying) {
            speechAnchorPending = true
            navigate(speechChapter, _position.value, textOffset = textOffset, resourcePath = speechPath, fromSpeech = true)
        }
        flush()
    }
    fun speechRate(rate: Float) = speech.setRate(rate)

    private val _state = MutableStateFlow(ReaderUiState())
    val state = _state.asStateFlow()
    private val _position = MutableStateFlow(0f)
    val position = _position.asStateFlow()
    val bookmarks = repository.bookmarks(id).catch { fail(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val annotations = repository.annotations(id).catch { fail(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val annotationError = MutableStateFlow(false)
    suspend fun createAnnotation(start: Int, end: Int): AnnotationEntity? = withContext(Dispatchers.IO) {
        val state = _state.value
        val book = state.book ?: return@withContext null
        val path = state.location.resourcePath ?: book.publication.chapters[state.location.chapter].path
        val text = repository.canonicalText(book, path)
        if (start < 0 || end <= start || end > text.length || end.toLong() - start > 10000) return@withContext null
        AnnotationEntity(bookId = id, chapter = state.location.chapter, resourcePath = path, startOffset = start, endOffset = end, quote = text.substring(start, end), style = "shade").takeIf { it.quote.isNotBlank() }
    }
    fun saveAnnotation(annotation: AnnotationEntity, done: () -> Unit) = viewModelScope.launch {
        try { repository.saveAnnotation(annotation); annotationError.value = false; done() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { annotationError.value = true }
    }
    fun removeAnnotation(annotation: AnnotationEntity) = viewModelScope.launch {
        try { repository.removeAnnotation(annotation) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { annotationError.value = true }
    }
    private val _search = MutableStateFlow(ReaderSearchState())
    val search = _search.asStateFlow()
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private data class Save(val chapter: Int, val position: Float, val count: Int, val textOffset: Int?, val path: String?)
    private var textOffset: Int? = null
    private val resourceHistory = ArrayDeque<ReaderLocation>()
    private val saves = Channel<Save>(Channel.CONFLATED)
    private var dirty = false
    private var pendingTargetGeneration: Int? = null

    init {
        // The application-owned writer drains final positions even when this ViewModel is cleared.
        app.applicationScope.launch {
            for (save in saves) {
                try { repository.savePosition(id, save.chapter, save.position, save.count, save.textOffset, save.path) }
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
                textOffset = book.record.readingTextOffset
                _state.value = ReaderUiState(book, loading = false, location = ReaderLocation(book.record.currentChapter.coerceIn(book.publication.chapters.indices), book.record.readingPosition, textOffset = textOffset, resourcePath = book.record.readingPath))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    fun navigate(chapter: Int, position: Float = 0f, fragment: String? = null, query: String? = null, textOffset: Int? = null, resourcePath: String? = null, fromSpeech: Boolean = false) {
        if (!fromSpeech) pauseSpeech()
        val book = _state.value.book ?: return
        if (chapter !in book.publication.chapters.indices) return
        if (resourcePath != null && book.publication.resources[resourcePath]?.mediaType !in setOf("application/xhtml+xml", "text/html")) return
        flush()
        _position.value = position
        this.textOffset = textOffset
        _state.update { it.copy(location = ReaderLocation(chapter, position, fragment, query, it.location.generation + 1, textOffset, resourcePath), error = false) }
        pendingTargetGeneration = _state.value.location.generation
        dirty = true
        flush()
    }

    fun openResource(path: String, fragment: String?) {
        resourceHistory.addLast(_state.value.location.copy(position = _position.value, textOffset = textOffset, fragment = null, query = null))
        navigate(_state.value.location.chapter, fragment = fragment, resourcePath = path)
    }

    fun backFromResource(): Boolean {
        if (_state.value.location.resourcePath == null && resourceHistory.isEmpty()) return false
        val target = if (resourceHistory.isEmpty()) ReaderLocation(chapter = _state.value.location.chapter) else resourceHistory.removeLast()
        navigate(target.chapter, target.position, target.fragment, textOffset = target.textOffset, resourcePath = target.resourcePath)
        return true
    }

    fun textPositionChanged(offset: Int?) { if (!speechState.value.playing && !speechAnchorPending) { textOffset = offset; dirty = true } }
    fun currentTextOffset(): Int? = textOffset

    fun positionChanged(position: Float) { if (!speechState.value.playing && !speechAnchorPending && position.isFinite()) { _position.value = position.coerceIn(0f, 1f); dirty = true } }
    fun consumeNavigationTarget(generation: Int): Boolean {
        val pending = pendingTargetGeneration == generation
        if (pending) { pendingTargetGeneration = null; speechAnchorPending = false }
        return pending
    }
    fun flush() {
        val book = _state.value.book ?: return
        if (!dirty) return
        saves.trySend(Save(_state.value.location.chapter, _position.value, book.publication.chapters.size, textOffset, _state.value.location.resourcePath))
        dirty = false
    }
    fun renderError() { _state.update { it.copy(error = true, loading = false) } }
    fun currentBookmark(): BookmarkEntity? = bookmarks.value.firstOrNull { it.chapter == _state.value.location.chapter && it.resourcePath == _state.value.location.resourcePath && kotlin.math.abs(it.position - _position.value) < 0.02f }
    fun toggleBookmark() = viewModelScope.launch {
        val state = _state.value
        val book = state.book ?: return@launch
        try {
            val existing = currentBookmark()
            if (existing != null) repository.removeBookmark(existing)
            else repository.addBookmark(BookmarkEntity(bookId = id, chapter = state.location.chapter, position = _position.value, title = book.publication.chapters[state.location.chapter].title, textOffset = textOffset, resourcePath = state.location.resourcePath))
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
    override fun onCleared() { if (speechDelegate.isInitialized()) speech.close(); speechJob?.cancel(); flush(); saves.close(); super.onCleared() }
}
