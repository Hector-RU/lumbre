package com.hector.epubreader.ui.reader

import com.hector.epubreader.data.local.AnnotationEntity
import android.webkit.WebSettings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.renderer.BookWebViewClient
import com.hector.epubreader.epub.renderer.EpubContent

internal fun nextPageOffset(origin: Int, maximum: Int, viewport: Int, direction: Int): Int =
    (origin + direction * viewport).coerceIn(0, maximum)

data class ReaderSeekRequest(val generation: Int, val serial: Int, val fraction: Float)

@Composable
fun ReaderWebView(book: OpenBook, location: ReaderLocation, preferences: ReaderPreferences, currentPosition: () -> Float, toggle: () -> Unit, onPosition: (Float) -> Unit, onLink: (Int, String?) -> Unit, onError: () -> Unit, flush: () -> Unit, consumeTarget: (Int) -> Boolean, modifier: Modifier = Modifier, onNextChapter: () -> Unit = {}, onPreviousChapter: () -> Unit = {}, turnRequest: Int = 0, seekRequest: ReaderSeekRequest? = null, onChapterPull: (Float) -> Unit = {}, viewportAnimating: Boolean = false, onTextPosition: (Int?) -> Unit = {}, currentTextOffset: () -> Int? = { location.textOffset }, onResourceLink: (String, String?) -> Unit = { _, _ -> }, annotations: List<AnnotationEntity> = emptyList(), onSelection: (Int, Int) -> Unit = { _, _ -> }) {
    val fontScale = LocalDensity.current.fontScale
    val palette = preferences.palette
    val colors = EpubContent.Colors(palette.background, palette.text, palette.link, palette.selection, palette.dark)
    val latestSelection by rememberUpdatedState(onSelection)
    val latestToggle by rememberUpdatedState(toggle)
    val latestPosition by rememberUpdatedState(onPosition)
    val latestLink by rememberUpdatedState(onLink)
    val latestTextPosition by rememberUpdatedState(onTextPosition)
    val latestResourceLink by rememberUpdatedState(onResourceLink)
    val latestError by rememberUpdatedState(onError)
    val latestFlush by rememberUpdatedState(flush)
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val latestPreviousChapter by rememberUpdatedState(onPreviousChapter)
    val latestChapterPull by rememberUpdatedState(onChapterPull)
    val renderPreferences = preferences.copy(appTheme = "system", dynamicColors = false, interfaceColor = "green", sort = "recent", ascending = false, listView = false)
    AndroidView(modifier = modifier, factory = { context ->
        ReadingWebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            this.onSelection = { start, end -> latestSelection(start, end) }
            this.toggle = { latestToggle() }
            reportPosition = { latestPosition(it) }
            reportTextPosition = { latestTextPosition(it) }
            requestNextChapter = { latestNextChapter() }
            requestPreviousChapter = { latestPreviousChapter() }
            reportChapterPull = { latestChapterPull(it) }
            lastTurnRequest = turnRequest
        }
    }, update = { view ->
        view.viewportAnimating = viewportAnimating
        view.pages = preferences.readingMode == "pages"
        view.paragraphs = preferences.readingMode == "paragraphs"

        view.hasNextChapter = location.chapter < book.publication.chapters.lastIndex
        view.hasPreviousChapter = location.chapter > 0
        view.isLongClickable = true
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        val highlights = annotations.groupBy { it.resourcePath }.mapValues { (_, notes) -> notes.map { EpubContent.Highlight(it.startOffset, it.endOffset, it.style, it.color) }.distinct() }
        val key = listOf(location, renderPreferences, fontScale, colors, highlights)
        if (view.renderKey != key) {
            val position = currentPosition()
            val targeted = consumeTarget(location.generation)
            val textOffset = if (targeted) location.textOffset else currentTextOffset() ?: view.lastTextOffset.takeIf { (view.renderKey as? List<*>)?.firstOrNull() == location }
            view.lastTextOffset = textOffset
            val fragment = location.fragment.takeIf { targeted }
            val query = location.query.takeIf { targeted }
            view.renderKey = key
            view.beginRender()
            view.setBackgroundColor(colors.background.toColorInt())
            val client = BookWebViewClient(book, renderPreferences, colors, fontScale,
                highlights = highlights, searchQuery = query, searchOffset = textOffset, onLink = { index, fragment -> latestLink(index, fragment) }, onError = { latestError() }, onResourceLink = { path, fragment -> latestResourceLink(path, fragment) },
                onReady = {
                    view.afterLayoutSettles(key) {
                        view.measurePagination {
                            if (view.renderKey != key || !view.restoring) return@measurePagination
                            fun finish() {
                                view.afterVisualState {
                                    if (view.renderKey != key) return@afterVisualState
                                    view.restoring = false
                                    latestPosition(view.fraction())
                                    if (textOffset != null && view.lastTextOffset == textOffset && view.requestedPosition == null && fragment == null) {
                                        view.lastTextOffset = textOffset
                                        latestTextPosition(textOffset)
                                    } else view.captureTextPosition()
                                }
                            }
                            if (fragment == null) view.restoreTextPosition(
                                textOffset.takeIf { view.requestedPosition == null }, view.requestedPosition ?: position, ::finish)
                            else finish()
                        }
                    }
                })
            view.webViewClient = client
            view.loadUrl(client.url(location.resourcePath ?: book.publication.chapters[location.chapter].path, fragment))
        }
        if (turnRequest != view.lastTurnRequest) {
            val direction = if (turnRequest > view.lastTurnRequest) 1 else -1
            view.lastTurnRequest = turnRequest
            view.step(direction)
        }
        if (seekRequest != null && seekRequest.generation == location.generation && seekRequest.serial != view.lastSeekSerial) {
            view.lastSeekSerial = seekRequest.serial
            view.requestedPosition = seekRequest.fraction
            if (!view.restoring) view.seek(seekRequest.fraction)
        }
    }, onRelease = { view ->
        if (!view.restoring) { latestPosition(view.fraction()); latestTextPosition(view.lastTextOffset) }
        latestFlush()
        view.dispose()
        view.renderKey = null
        view.stopLoading()
        view.removeAllViews()
        view.destroy()
    })
}
