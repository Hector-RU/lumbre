package com.hector.epubreader.ui.reader

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
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

internal fun nextPageOffset(origin: Int, maximum: Int, viewport: Int, direction: Int): Int =
    (origin + direction * viewport).coerceIn(0, maximum)

data class ReaderSeekRequest(val generation: Int, val serial: Int, val fraction: Float)

private val paginationMetricsScript = """
(function() {
  var dpr = window.devicePixelRatio || 1;
  var slides = document.querySelectorAll('body > .reader-paragraph');
  if (slides.length) {
    var stride = slides.length > 1
      ? slides[1].getBoundingClientRect().left - slides[0].getBoundingClientRect().left
      : slides[0].getBoundingClientRect().width;
    return slides.length + '|' + (stride * dpr);
  }
  var flow = document.getElementById('reader-flow');
  if (!flow) return '0|0';
  var width = flow.getBoundingClientRect().width;
  return Math.max(1, Math.ceil(flow.scrollWidth / width)) + '|' + (width * dpr);
})()
""".trimIndent()

private class ReadingWebView(context: Context) : WebView(context) {
    var toggle: () -> Unit = {}
    var reportPosition: (Float) -> Unit = {}
    var requestNextChapter: () -> Unit = {}
    var requestPreviousChapter: () -> Unit = {}
    var reportChapterPull: (Float) -> Unit = {}
    var hasNextChapter = false
    var hasPreviousChapter = false
    var pages = false
    var paragraphs = false
    var lastTurnRequest = 0
    var lastSeekSerial = 0
    var requestedPosition: Float? = null
    private var pageCount = 0
    private var pageStride = 0f
    private var pageIndex = 0
    var restoring = true
    var renderKey: Any? = null
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pullThreshold = 96 * resources.displayMetrics.density
    private var nativeCancelled = false
    private var pullStart: Float? = null
    private var pullDirection = 0
    private var pullProgress = 0f
    private var animator: ValueAnimator? = null
    private var resizeToken = 0
    private var lastKnownFraction = 0f
    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }
    private fun cancelTurn() {
        cancelAnimation()
    }
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
    override fun performLongClick(): Boolean = if (pages || paragraphs) false else super.performLongClick()
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
            cancelTurn()
            resizeToken++
            if (horizontalMode() && pageStride > 0f) pageIndex = (scrollX / pageStride).roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            restoring = false
            downX = event.x
            downY = event.y
            downScroll = offset()
            nativeCancelled = false
            pullStart = null
            pullDirection = 0
            pullProgress = 0f
            reportChapterPull(0f)
        }
        detector.onTouchEvent(event)
        val dx = event.x - downX
        val dy = event.y - downY
        if (event.action == MotionEvent.ACTION_MOVE) {
            val horizontal = pages || paragraphs
            val alongAxis = if (horizontal) abs(dx) > abs(dy) else abs(dy) > abs(dx)
            val forward = if (horizontal) -dx else -dy
            if (alongAxis && pullStart == null) {
                val direction = when {
                    forward > touchSlop && hasNextChapter && atEnd() -> 1
                    forward < -touchSlop && hasPreviousChapter && atStart() -> -1
                    else -> 0
                }
                if (direction != 0) {
                    pullDirection = direction
                    pullStart = if (horizontal) { if (downScroll == offset()) downX else event.x } else { if (downScroll == offset()) downY else event.y }
                }
            }
            pullProgress = if (alongAxis && pullStart != null) {
                val distance = (pullStart!! - if (horizontal) event.x else event.y) * pullDirection
                (distance / pullThreshold).coerceIn(0f, 1f)
            } else 0f
            reportChapterPull(pullProgress * pullDirection)
            if ((pages && (abs(dx) > touchSlop || abs(dy) > touchSlop)) ||
                (paragraphs && alongAxis && abs(dx) > touchSlop) || pullProgress > 0f) {
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
                if (commitChapter) {
                    if (pullDirection > 0) requestNextChapter() else requestPreviousChapter()
                } else if (pages || paragraphs) scrollToOffset(downScroll)
                return true
            }
            if ((pages || paragraphs) && abs(dx) > width * 0.15f && abs(dx) > abs(dy)) {
                cancelNativeGesture(event)
                step(if (dx < 0) 1 else -1, downScroll)
                return true
            }
            if (nativeCancelled) { scrollToOffset(downScroll); return true }
        }
        if (event.action == MotionEvent.ACTION_CANCEL) {
            reportChapterPull(0f)
            pullProgress = 0f
            if (pages || paragraphs) scrollToOffset(downScroll)
        }
        return super.onTouchEvent(event)
    }
    private fun horizontalMode() = pages || paragraphs
    private fun offset() = if (horizontalMode()) scrollX else scrollY
    private fun maximum() = if (horizontalMode()) {
        if (pageCount > 0) ((pageCount - 1) * pageStride).roundToInt() else (computeHorizontalScrollRange() - width).coerceAtLeast(0)
    } else (computeVerticalScrollRange() - height).coerceAtLeast(0)
    private fun atEnd() = if (horizontalMode()) {
        if (pageCount > 0) pageIndex >= pageCount - 1 else !canScrollHorizontally(1)
    } else !canScrollVertically(1)
    private fun atStart() = if (horizontalMode()) {
        if (pageCount > 0) pageIndex <= 0 else !canScrollHorizontally(-1)
    } else !canScrollVertically(-1)
    private fun scrollToOffset(value: Int) { if (horizontalMode()) scrollTo(value, 0) else scrollTo(0, value) }
    fun step(direction: Int, origin: Int = offset()) {
        if (restoring) return
        if (direction > 0 && maximum() - origin <= 4) { requestNextChapter(); return }
        if (direction < 0 && origin <= 4) { requestPreviousChapter(); return }
        if (horizontalMode()) {
            if (pageCount > 0) {
                pageIndex = (pageIndex + direction).coerceIn(0, pageCount - 1)
                animatePage((pageIndex * pageStride).roundToInt())
            } else animatePage(nextPageOffset(origin, maximum(), width, direction))
        } else scrollToOffset((origin + direction * (height * 0.85f).toInt()).coerceIn(0, maximum()))
        reportPosition(fraction())
    }
    private fun animatePage(target: Int) {
        cancelAnimation()
        val start = offset()
        if (start == target) return
        animator = ValueAnimator.ofInt(start, target).apply {
            duration = 240
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { animation -> scrollToOffset(animation.animatedValue as Int) }
            start()
        }
    }
    fun fraction(): Float {
        val maximum = maximum()
        return if (maximum == 0) 0f else (offset().toFloat() / maximum).coerceIn(0f, 1f)
    }
    fun restore(fraction: Float) {
        cancelAnimation()
        lastKnownFraction = fraction.coerceIn(0f, 1f)
        val offset = (maximum() * fraction).toInt().coerceIn(0, maximum())
        if (horizontalMode()) {
            if (pageCount > 0) {
                pageIndex = (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()
                scrollToOffset((pageIndex * pageStride).roundToInt())
            } else {
                val page = if (width > 0) ((offset + width / 2) / width) * width else offset
                scrollToOffset(page.coerceIn(0, maximum()))
            }
        } else scrollToOffset(offset)
    }
    fun measurePagination(done: () -> Unit) {
        if (!horizontalMode()) { done(); return }
        val viewportHeight = height / resources.displayMetrics.density
        evaluateJavascript("document.documentElement.style.setProperty('--reader-viewport-height', '${viewportHeight}px')") {
            evaluateJavascript(paginationMetricsScript) { result ->
                val metrics = result?.removeSurrounding("\"")?.split('|')
                val count = metrics?.getOrNull(0)?.toIntOrNull()
                val stride = metrics?.getOrNull(1)?.toFloatOrNull()
                if (count != null && count > 0 && stride != null && stride.isFinite() && stride > 0f) {
                    pageCount = count
                    pageStride = stride
                }
                done()
            }
        }
    }
    fun seek(fraction: Float) {
        resizeToken++
        restore(fraction)
    }
    fun beginRender() {
        cancelTurn()
        resizeToken++
        pageCount = 0
        pageStride = 0f
        pageIndex = 0
        requestedPosition = null
        restoring = true
    }
    fun dispose() {
        cancelTurn()
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!restoring) {
            lastKnownFraction = fraction()
            reportPosition(lastKnownFraction)
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val position = lastKnownFraction
        val key = renderKey
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && w > 0 && h > 0 && (w != oldw || h != oldh) && !restoring) {
            val token = ++resizeToken
            listOf(80L, 300L).forEach { delay ->
                postDelayed({
                    if (token == resizeToken && key == renderKey && !restoring) {
                        measurePagination { if (token == resizeToken && key == renderKey) restore(position) }
                    }
                }, delay)
            }
        }
    }
}

@Composable
fun ReaderWebView(book: OpenBook, location: ReaderLocation, preferences: ReaderPreferences, currentPosition: () -> Float, toggle: () -> Unit, onPosition: (Float) -> Unit, onLink: (Int, String?) -> Unit, onError: () -> Unit, flush: () -> Unit, consumeTarget: (Int) -> Boolean, modifier: Modifier = Modifier, onNextChapter: () -> Unit = {}, onPreviousChapter: () -> Unit = {}, turnRequest: Int = 0, seekRequest: ReaderSeekRequest? = null, onChapterPull: (Float) -> Unit = {}) {
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
    val latestPreviousChapter by rememberUpdatedState(onPreviousChapter)
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
            requestPreviousChapter = { latestPreviousChapter() }
            reportChapterPull = { latestChapterPull(it) }
            lastTurnRequest = turnRequest
        }
    }, update = { view ->
        view.pages = preferences.readingMode == "pages"
        view.paragraphs = preferences.readingMode == "paragraphs"
        if (view.settings.javaScriptEnabled != (view.pages || view.paragraphs)) view.settings.javaScriptEnabled = view.pages || view.paragraphs
        view.hasNextChapter = location.chapter < book.publication.chapters.lastIndex
        view.hasPreviousChapter = location.chapter > 0
        view.isLongClickable = !view.pages && !view.paragraphs
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        val key = listOf(location, renderPreferences, fontScale, colors)
        if (view.renderKey != key) {
            val position = currentPosition()
            val targeted = consumeTarget(location.generation)
            val fragment = location.fragment.takeIf { targeted }
            val query = location.query.takeIf { targeted }
            view.renderKey = key
            view.beginRender()
            view.setBackgroundColor(colors.background.toColorInt())
            val client = BookWebViewClient(book, renderPreferences, colors, fontScale,
                onLink = { index, fragment -> latestLink(index, fragment) }, onError = { latestError() },
                onReady = {
                    listOf(100L, 350L, 800L).forEach { delay ->
                        view.postDelayed({
                            if (view.renderKey == key && view.restoring) {
                                view.measurePagination {
                                    if (view.renderKey != key || !view.restoring) return@measurePagination
                                    if (fragment == null && query == null) view.restore(view.requestedPosition ?: position)
                                    if (delay == 800L) {
                                        view.restoring = false
                                        if (query != null) {
                                            view.findAllAsync(query)
                                            view.postDelayed({ if (view.renderKey == key) view.clearMatches() }, 30_000)
                                        }
                                        latestPosition(view.fraction())
                                    }
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
        if (seekRequest != null && seekRequest.generation == location.generation && seekRequest.serial != view.lastSeekSerial) {
            view.lastSeekSerial = seekRequest.serial
            view.requestedPosition = seekRequest.fraction
            if (!view.restoring) view.seek(seekRequest.fraction)
        }
    }, onRelease = { view ->
        if (!view.restoring) latestPosition(view.fraction())
        latestFlush()
        view.dispose()
        view.renderKey = null
        view.stopLoading()
        view.removeAllViews()
        view.destroy()
    })
}
