package com.hector.epubreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hector.epubreader.R
import com.hector.epubreader.MainActivity
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.ui.settings.ReaderSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, preferences: ReaderPreferences, change: ((ReaderPreferences) -> ReaderPreferences) -> Unit, back: () -> Unit, settings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    var controls by rememberSaveable { mutableStateOf(true) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var chapterPull by remember { mutableFloatStateOf(0f) }
    val arrowProgress by animateFloatAsState(kotlin.math.abs(chapterPull), spring(stiffness = 1200f), label = "chapterPull")
    var turnRequest by remember { mutableIntStateOf(0) }
    var seekSerial by remember { mutableIntStateOf(0) }
    var seekRequest by remember { mutableStateOf<ReaderSeekRequest?>(null) }
    var topBarHeight by remember { mutableIntStateOf(0) }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val bottomInset = WindowInsets.safeDrawing.getBottom(density)
    val readerTop = with(density) { (if (controls) (topBarHeight - topInset).coerceAtLeast(0) else 0).toDp() }
    val readerBottom = with(density) { (if (controls) (bottomBarHeight - bottomInset).coerceAtLeast(0) else 0).toDp() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    DisposableEffect(activity, preferences.volumeNavigation) {
        val host = activity as? MainActivity
        host?.onReaderVolumeKey = if (preferences.volumeNavigation) { direction -> turnRequest += direction; true } else null
        onDispose { host?.onReaderVolumeKey = null }
    }
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.flush() }
    }
    DisposableEffect(controls, sheet, activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controls || sheet != null) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { if (sheet != null) sheet = null else if (controls) controls = false else { vm.flush(); back() } }
    val book = state.book
    LaunchedEffect(state.location.chapter, preferences.readingMode) { chapterPull = 0f }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (book != null && !state.error) {
            ReaderWebView(book, state.location, preferences, { vm.position.value }, { controls = !controls }, vm::positionChanged,
                onLink = { chapter, fragment -> vm.navigate(chapter, fragment = fragment) }, onError = vm::renderError, flush = vm::flush, consumeTarget = vm::consumeNavigationTarget,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = readerTop, bottom = readerBottom),
                onNextChapter = { if (state.location.chapter < book.publication.chapters.lastIndex) vm.navigate(state.location.chapter + 1) }, turnRequest = turnRequest,
                onPreviousChapter = { if (state.location.chapter > 0) vm.navigate(state.location.chapter - 1, 1f) },
                seekRequest = seekRequest,
                onChapterPull = { chapterPull = it })
        }
        val loadingLabel = stringResource(R.string.loading)
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = loadingLabel })
        if (state.error) Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.reader_error))
                Button(onClick = vm::load) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = back) { Text(stringResource(R.string.back)) }
            }
        }
        if (arrowProgress > 0.01f && book != null) {
            val horizontal = preferences.readingMode == "pages" || preferences.readingMode == "paragraphs"
            val previous = chapterPull < 0f
            Surface(Modifier
                .align(if (horizontal) { if (previous) Alignment.CenterStart else Alignment.CenterEnd } else { if (previous) Alignment.TopCenter else Alignment.BottomCenter })
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = if (horizontal && previous) 16.dp else 0.dp, end = if (horizontal && !previous) 16.dp else 0.dp,
                    top = if (!horizontal && previous) { if (controls) 72.dp else 28.dp } else 0.dp,
                    bottom = if (!horizontal && !previous) { if (controls) 164.dp else 28.dp } else 0.dp)
                .graphicsLayer {
                    alpha = arrowProgress
                    val travel = 72.dp.toPx() * (1f - arrowProgress)
                    translationX = if (horizontal) travel * if (previous) -1 else 1 else 0f
                    translationY = if (horizontal) 0f else travel * if (previous) -1 else 1
                    scaleX = 0.7f + arrowProgress * 0.3f
                    scaleY = scaleX
                }, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(if (horizontal) { if (previous) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight }
                    else { if (previous) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown },
                    stringResource(if (previous) R.string.return_chapter else R.string.continue_chapter),
                    Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (controls && book != null) {
            TopAppBar(title = { Text(book.record.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                IconButton(onClick = { vm.flush(); back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            }, actions = {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks)) }, onClick = { menu = false; sheet = "bookmarks" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; vm.flush(); settings() })
                }
            }, modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topBarHeight = it.height })
            Surface(modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomBarHeight = it.height }, tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.navigate(state.location.chapter - 1) }, enabled = state.location.chapter > 0) { Icon(Icons.AutoMirrored.Outlined.NavigateBefore, stringResource(R.string.previous_chapter)) }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(book.publication.chapters[state.location.chapter].title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.chapter, state.location.chapter + 1, book.publication.chapters.size), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { vm.navigate(state.location.chapter + 1) }, enabled = state.location.chapter < book.publication.chapters.lastIndex) { Icon(Icons.AutoMirrored.Outlined.NavigateNext, stringResource(R.string.next_chapter)) }
                    }
                    var slider by remember(state.location) { mutableFloatStateOf(position) }
                    var dragging by remember(state.location) { mutableStateOf(false) }
                    LaunchedEffect(position, dragging) { if (!dragging) slider = position }
                    val progressLabel = stringResource(R.string.sort_progress)
                    Slider(value = slider, onValueChange = {
                        dragging = true
                        slider = it
                        seekRequest = ReaderSeekRequest(state.location.generation, ++seekSerial, it)
                    }, onValueChangeFinished = {
                        dragging = false
                        vm.positionChanged(slider)
                        vm.flush()
                    }, modifier = Modifier.semantics { contentDescription = progressLabel })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { sheet = "contents" }) { Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, stringResource(R.string.contents)) }
                        IconButton(onClick = { sheet = "appearance" }) { Icon(Icons.Outlined.TextFields, stringResource(R.string.appearance)) }
                        IconButton(onClick = { sheet = "search" }) { Icon(Icons.Outlined.Search, stringResource(R.string.search_book)) }
                        val marked = bookmarks.any { it.chapter == state.location.chapter && kotlin.math.abs(it.position - position) < 0.02f }
                        IconButton(onClick = { vm.toggleBookmark() }) { Icon(if (marked) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, stringResource(if (marked) R.string.remove_bookmark else R.string.add_bookmark)) }
                    }
                }
            }
        } else if (book != null) {
            Surface(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                Text("${state.location.chapter + 1} / ${book.publication.chapters.size}", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (sheet != null && book != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when (sheet) {
            "appearance" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                ReaderSettings(preferences, change, compact = true)
                TextButton(onClick = { sheet = null; vm.flush(); settings() }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.all_settings)) }
            }
            "contents" -> {
                SheetTitle(stringResource(R.string.contents))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(book.publication.contents) { item ->
                        val chapter = book.publication.chapters.indexOfFirst { it.path == item.path }
                        TextButton(onClick = { vm.navigate(chapter, fragment = item.fragment); sheet = null }, modifier = Modifier.fillMaxWidth().padding(start = (16 + item.depth.coerceAtMost(5) * 12).dp)) {
                            Text(item.title, Modifier.fillMaxWidth(), color = if (chapter == state.location.chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            "bookmarks" -> {
                SheetTitle(stringResource(R.string.bookmarks))
                if (bookmarks.isEmpty()) Text(stringResource(R.string.no_bookmarks), Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        ListItem(headlineContent = { TextButton(onClick = { vm.navigate(bookmark.chapter, bookmark.position); sheet = null }) { Text(bookmark.title) } }, supportingContent = { Text(stringResource(R.string.bookmark_position, (bookmark.position * 100).toInt())) }, trailingContent = { IconButton(onClick = { vm.removeBookmark(bookmark) }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.remove_bookmark)) } })
                    }
                }
            }
            "search" -> {
                SheetTitle(stringResource(R.string.search_book))
                OutlinedTextField(search.query, vm::search, singleLine = true, label = { Text(stringResource(R.string.search_book_hint)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).imePadding())
                val searchingLabel = stringResource(R.string.searching)
                if (search.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp).semantics { contentDescription = searchingLabel })
                if (search.error) Text(stringResource(R.string.reader_error), Modifier.padding(24.dp))
                if (!search.searching && !search.error && search.query.length >= 2 && search.hits.isEmpty()) Text(stringResource(R.string.no_results), Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(search.hits) { hit ->
                        TextButton(onClick = { vm.navigate(hit.chapter, query = hit.query); sheet = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(hit.title, style = MaterialTheme.typography.titleSmall); Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetTitle(title: String) { Text(title, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge) }
