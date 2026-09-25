package com.hector.epubreader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.hector.epubreader.R
import com.hector.epubreader.data.OpenBook
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.renderer.BookWebViewClient
import com.hector.epubreader.epub.renderer.EpubContent
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun nextPageOffset(origin: Int, maximum: Int, viewport: Int, direction: Int): Int {
    val pageLength = (viewport * 0.94f).toInt().coerceAtLeast(1)
    return (((origin.toFloat() / pageLength).roundToInt() + direction).coerceAtLeast(0) * pageLength).coerceAtMost(maximum)
}

private class ReadingWebView(context: Context) : WebView(context) {
    var toggle: () -> Unit = {}
    var reportPosition: (Float) -> Unit = {}
    var requestNextChapter: () -> Unit = {}
    var reportChapterPull: (Float) -> Unit = {}
    var hasNextChapter = false
    var pages = false
    var lastTurnRequest = 0
    var restoring = true
    var renderKey: Any? = null
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pullThreshold = 96 * resources.displayMetrics.density
    private var nativeCancelled = false
    private var pullStart: Float? = null
    private var pullProgress = 0f
    private fun cancelNativeGesture(event: MotionEvent) {
        if (nativeCancelled) return
        MotionEvent.obtain(event).also {
            it.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(it)
            it.recycle()
        }
        nativeCancelled = true
        cancelLongPress()
    }
    override fun performLongClick(): Boolean = if (pages) false else super.performLongClick()
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (e.x in width * 0.25f..width * 0.75f && e.y in height * 0.2f..height * 0.8f && hitTestResult.type == HitTestResult.UNKNOWN_TYPE) {
                performClick()
                return true
            }
            return false
        }
    })
    init {
        ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.reader_controls)) { _, _ -> performClick(); true }
    }
    override fun performClick(): Boolean { super.performClick(); toggle(); return true }
    // GestureDetector invokes performClick only after a confirmed central tap, preserving links and selection.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            restoring = false
            downX = event.x
            downY = event.y
            downScroll = scrollY
            nativeCancelled = false
            pullStart = if (hasNextChapter && atEnd()) { if (pages) event.x else event.y } else null
            pullProgress = 0f
            reportChapterPull(0f)
        }
        detector.onTouchEvent(event)
        val dx = event.x - downX
        val dy = event.y - downY
        if (event.action == MotionEvent.ACTION_MOVE) {
            val alongAxis = if (pages) abs(dx) > abs(dy) else abs(dy) > abs(dx)
            if (!pages && hasNextChapter && atEnd() && pullStart == null) pullStart = event.y
            if (hasNextChapter && alongAxis && pullStart != null) {
                val distance = pullStart!! - if (pages) event.x else event.y
                pullProgress = (distance / pullThreshold).coerceIn(0f, 1f)
                reportChapterPull(pullProgress)
            } else { pullProgress = 0f; reportChapterPull(0f) }
            if ((pages && (abs(dx) > touchSlop || abs(dy) > touchSlop)) || pullProgress > 0f) {
                cancelNativeGesture(event)
                return true
            }
            if (nativeCancelled) return true
        }
        if (event.action == MotionEvent.ACTION_UP) {
            val commitChapter = pullProgress >= 1f
            reportChapterPull(0f)
            if (pullStart != null && (nativeCancelled || commitChapter)) {
                cancelNativeGesture(event)
                if (commitChapter) requestNextChapter()
                // A reverse swipe still turns back a page.
                if (pages && dx > width * 0.15f && abs(dx) > abs(dy)) step(-1, downScroll)
                return true
            }
            if (pages && abs(dx) > width * 0.15f && abs(dx) > abs(dy)) {
                cancelNativeGesture(event)
                step(if (dx < 0) 1 else -1, downScroll)
                return true
            }
            if (nativeCancelled) { if (pages) scrollTo(0, downScroll); return true }
        }
        if (event.action == MotionEvent.ACTION_CANCEL) {
            reportChapterPull(0f)
            pullProgress = 0f
            if (pages) scrollTo(0, downScroll)
        }
        return super.onTouchEvent(event)
    }
    private fun maximum() = (computeVerticalScrollRange() - height).coerceAtLeast(0)
    private fun atEnd() = maximum() - scrollY <= 4
    private fun pageLength() = (height * 0.94f).toInt().coerceAtLeast(1)
    fun step(direction: Int, origin: Int = scrollY) {
        if (restoring) return
        if (direction > 0 && maximum() - origin <= 4) { requestNextChapter(); return }
        if (pages) {
            scrollTo(0, nextPageOffset(origin, maximum(), height, direction))
        } else scrollTo(0, (origin + direction * (height * 0.85f).toInt()).coerceIn(0, maximum()))
        reportPosition(fraction())
    }
    fun fraction(): Float {
        val maximum = maximum()
        return if (maximum == 0) 0f else (scrollY.toFloat() / maximum).coerceIn(0f, 1f)
    }
    fun restore(fraction: Float) {
        val offset = (maximum() * fraction).toInt()
        if (pages) scrollTo(0, ((offset.toFloat() / pageLength()).roundToInt() * pageLength()).coerceAtMost(maximum())) else scrollTo(0, offset)
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!restoring) reportPosition(fraction())
    }
}

@Composable
fun ReaderWebView(book: OpenBook, location: ReaderLocation, preferences: ReaderPreferences, currentPosition: () -> Float, toggle: () -> Unit, onPosition: (Float) -> Unit, onLink: (Int, String?) -> Unit, onError: () -> Unit, flush: () -> Unit, consumeTarget: (Int) -> Boolean, modifier: Modifier = Modifier, onNextChapter: () -> Unit = {}, turnRequest: Int = 0, onChapterPull: (Float) -> Unit = {}) {
    val fontScale = LocalDensity.current.fontScale
    val scheme = MaterialTheme.colorScheme
    fun hex(color: androidx.compose.ui.graphics.Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
    val colors = EpubContent.Colors(hex(scheme.surface), hex(scheme.onSurface), hex(scheme.primary), hex(scheme.primaryContainer), scheme.surface.luminance() < 0.5f)
    val latestToggle by rememberUpdatedState(toggle)
    val latestPosition by rememberUpdatedState(onPosition)
    val latestLink by rememberUpdatedState(onLink)
    val latestError by rememberUpdatedState(onError)
    val latestFlush by rememberUpdatedState(flush)
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val latestChapterPull by rememberUpdatedState(onChapterPull)
    val renderPreferences = preferences.copy(appTheme = "system", dynamicColors = false, interfaceColor = "green", sort = "recent", ascending = false, listView = false)
    AndroidView(modifier = modifier, factory = { context ->
        ReadingWebView(context).apply {
            settings.javaScriptEnabled = false
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
            this.toggle = { latestToggle() }
            reportPosition = { latestPosition(it) }
            requestNextChapter = { latestNextChapter() }
            reportChapterPull = { latestChapterPull(it) }
            lastTurnRequest = turnRequest
        }
    }, update = { view ->
        view.pages = preferences.readingMode == "pages"
        view.hasNextChapter = location.chapter < book.publication.chapters.lastIndex
        view.isLongClickable = !view.pages
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        val key = listOf(location, renderPreferences, fontScale, colors)
        if (view.renderKey != key) {
            val position = currentPosition()
            val targeted = consumeTarget(location.generation)
            val fragment = location.fragment.takeIf { targeted }
            val query = location.query.takeIf { targeted }
            view.renderKey = key
            view.restoring = true
            view.setBackgroundColor(colors.background.toColorInt())
            val client = BookWebViewClient(book, renderPreferences, colors, fontScale,
                onLink = { index, fragment -> latestLink(index, fragment) }, onError = { latestError() },
                onReady = {
                    listOf(100L, 350L, 800L).forEach { delay ->
                        view.postDelayed({
                            if (view.renderKey == key && view.restoring) {
                                if (fragment == null && query == null) view.restore(position)
                                if (delay == 800L) {
                                    view.restoring = false
                                    if (query != null) {
                                        view.findAllAsync(query)
                                        view.postDelayed({ if (view.renderKey == key) view.clearMatches() }, 30_000)
                                    }
                                    latestPosition(view.fraction())
                                }
                            }
                        }, delay)
                    }
                })
            view.webViewClient = client
            view.loadUrl(client.url(book.publication.chapters[location.chapter].path, fragment))
        }
        if (turnRequest != view.lastTurnRequest) {
            val direction = if (turnRequest > view.lastTurnRequest) 1 else -1
            view.lastTurnRequest = turnRequest
            view.step(direction)
        }
    }, onRelease = { view ->
        if (!view.restoring) latestPosition(view.fraction())
        latestFlush()
        view.renderKey = null
        view.stopLoading()
        view.removeAllViews()
        view.destroy()
    })
}
