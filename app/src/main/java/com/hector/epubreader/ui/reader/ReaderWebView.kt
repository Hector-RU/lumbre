package com.hector.epubreader.ui.reader

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlin.math.min

internal fun nextPageOffset(origin: Int, maximum: Int, viewport: Int, direction: Int): Int =
    (origin + direction * viewport).coerceIn(0, maximum)

data class ReaderSeekRequest(val generation: Int, val serial: Int, val fraction: Float)

private val paginationMetricsScript = """
(function() {
  var dpr = window.devicePixelRatio || 1;
  var flow = document.getElementById('reader-flow');
  if (!flow && document.querySelector('body > .reader-paragraph')) flow = document.body;
  if (!flow) return '0|0';
  var width = flow.getBoundingClientRect().width;
  var count = Math.max(1, Math.ceil(flow.scrollWidth / width));
  // The last column's overflow can omit its trailing margin. Reserve a complete page
  // on the root without widening the column container or adding another column.
  document.documentElement.style.setProperty('min-width', (count * width) + 'px', 'important');
  return count + '|' + (width * dpr);
})()
""".trimIndent()

private fun captureReadingAnchorScript(page: Int) = """
(function() {
  // Keep this text reference across controls toggles; discard it only on navigation.
  if (window.readerReadingAnchor) return;
  var flow = document.getElementById('reader-flow') || document.body;
  var bounds = flow.getBoundingClientRect();
  var width = bounds.width;
  var scroll = window.scrollX;
  // Chromium may pan the viewport before notifying Android about its new size.
  // Locate text in the reader's recorded column, including offscreen ranges.
  var column = $page;
  var left = column * width - scroll;
  var right = left + width;
  var top = 0;
  var bottom = bounds.height;
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  var node;
  while (node = walker.nextNode()) {
    if (!node.data.trim()) continue;
    var range = document.createRange();
    range.selectNodeContents(node);
    var rects = range.getClientRects();
    for (var i = 0; i < rects.length; i++) {
      var rect = rects[i];
      if (rect.right <= left || rect.left >= right || rect.bottom <= top || rect.top >= bottom) continue;
      // A text node may span many columns. Find its first character on this page.
      var low = 0, high = node.length - 1;
      while (low < high) {
        var middle = Math.floor((low + high) / 2);
        range.setStart(node, middle); range.setEnd(node, middle + 1);
        var character = range.getBoundingClientRect();
        var characterColumn = Math.floor((character.left + scroll) / width);
        if (characterColumn < column || (characterColumn === column && character.bottom <= top)) low = middle + 1;
        else high = middle;
      }
      window.readerReadingAnchor = {node:node, offset:low};
      return;
    }
  }
})()
""".trimIndent()

private val readingAnchorPageScript = """
(function() {
  var anchor = window.readerReadingAnchor;
  if (!anchor || !document.body.contains(anchor.node) || !anchor.node.length) return -1;
  var offset = Math.min(anchor.offset, anchor.node.length - 1);
  var range = document.createRange();
  range.setStart(anchor.node, offset); range.setEnd(anchor.node, offset + 1);
  var flow = document.getElementById('reader-flow') || document.body;
  var width = flow.getBoundingClientRect().width;
  var scroll = window.scrollX;
  return Math.max(0, Math.floor((range.getBoundingClientRect().left + scroll) / width));
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
    private var disposed = false
    var viewportAnimating = false
        set(value) {
            if (field == value) return
            field = value
            resizeToken++
            if (!value) postOnAnimation {
                if (!viewportAnimating) {
                    if (resizePosition != null) scheduleResizeRestore()
                    else runPendingPageTurns()
                }
            }
        }
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
    private var resizePosition: Float? = null
    private var resizeVerticalOffset: Int? = null
    private var pendingResizeTurns = 0
    private var resizeSnapshot: Bitmap? = null
    private val resizeSnapshotBounds = Rect()
    private val resizeSnapshotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val resizeBackgroundPaint = Paint()
    private var resizeFade: ValueAnimator? = null
    private fun cancelResizeFade() {
        resizeFade?.removeAllListeners()
        resizeFade?.cancel()
        resizeFade = null
    }
    private fun clearResizeTransition() {
        cancelResizeFade()
        resizeSnapshot = null
        resizePosition = null
        resizeVerticalOffset = null
        pendingResizeTurns = 0
        invalidate()
    }
    private fun captureResizeSnapshot(w: Int, h: Int) {
        // Keep the current page visible while columns are repaginated. Capture once,
        // not on each frame of the controls' height animation.
        val scale = min(1f, min(2048f / w, 2048f / h))
        val snapshot = Bitmap.createBitmap((w * scale).roundToInt().coerceAtLeast(1),
            (h * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(snapshot)
        canvas.scale(scale, scale)
        // View.draw expects a viewport canvas; ViewGroup normally applies this scroll offset.
        canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
        draw(canvas)
        cancelResizeFade()
        resizeSnapshot = snapshot
        resizeSnapshotBounds.set(0, 0, w, h)
        resizeSnapshotPaint.alpha = 255
    }
    private fun revealResizedPage(token: Int, key: Any?) {
        val snapshot = resizeSnapshot ?: return
        // Let Chromium paint the restored page before dissolving the old one.
        postOnAnimation {
            postOnAnimation reveal@{
                if (token != resizeToken || key != renderKey || resizeSnapshot !== snapshot) return@reveal
                resizeFade = ValueAnimator.ofInt(resizeSnapshotPaint.alpha, 0).apply {
                    duration = 140
                    interpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
                    addUpdateListener { animation ->
                        resizeSnapshotPaint.alpha = animation.animatedValue as Int
                        invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (resizeSnapshot === snapshot) {
                                resizeSnapshot = null
                                resizeFade = null
                                invalidate()
                            }
                        }
                    })
                    start()
                }
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        resizeSnapshot?.let { snapshot ->
            val save = canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            if (height > resizeSnapshotBounds.bottom) {
                resizeBackgroundPaint.color = snapshot.getPixel(0, 0)
                resizeBackgroundPaint.alpha = resizeSnapshotPaint.alpha
                canvas.drawRect(0f, resizeSnapshotBounds.bottom.toFloat(), width.toFloat(), height.toFloat(), resizeBackgroundPaint)
            }
            canvas.drawBitmap(snapshot, null, resizeSnapshotBounds, resizeSnapshotPaint)
            canvas.restoreToCount(save)
        }
    }
    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }
    private fun cancelTurn() {
        cancelAnimation()
    }
    private fun settleTurn() {
        val running = animator?.isRunning == true
        cancelTurn()
        if (running && horizontalMode() && pageStride > 0f) {
            scrollToOffset((pageIndex * pageStride).roundToInt())
        }
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
    private fun isControlsTap(event: MotionEvent) =
        !disposed && event.x in width * 0.25f..width * 0.75f && event.y in height * 0.2f..height * 0.8f &&
            hitTestResult.type == HitTestResult.UNKNOWN_TYPE
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isControlsTap(e)) {
                performClick()
                return true
            }
            return false
        }
    })
    init {
        ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.reader_controls)) { _, _ -> performClick(); true }
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (horizontalMode() && !restoring) {
            val key = renderKey
            // Capture before changing controls or system bars. Once the viewport
            // changes, Chromium may already have shifted the visible column.
            evaluateJavascript(captureReadingAnchorScript(pageIndex)) {
                if (key == renderKey) toggle()
            }
        } else toggle()
        return true
    }
    // GestureDetector invokes performClick only after a confirmed central tap, preserving links and selection.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            settleTurn()
            // A second controls tap must keep the pending text restoration alive.
            // Recompute the page index only after that restoration has completed.
            if (!horizontalMode() || (!viewportAnimating && resizePosition == null)) {
                resizeToken++
                clearResizeTransition()
                if (horizontalMode() && pageStride > 0f) pageIndex = (scrollX / pageStride).roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            }
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
            if (horizontalMode() && abs(dx) <= touchSlop && abs(dy) <= touchSlop && isControlsTap(event)) {
                // The reader owns this tap. Cancel the document's gesture before
                // Chromium focuses its tap target and scrolls it into view.
                cancelNativeGesture(event)
                return true
            }
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
        if (horizontalMode() && (viewportAnimating || resizePosition != null)) {
            pendingResizeTurns += direction
            return
        }
        if (horizontalMode()) evaluateJavascript("window.readerReadingAnchor = null", null)
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
                // JavaScript has measured the new columns, but Android's scroll
                // range is updated only when Chromium commits that layout.
                postVisualStateCallback(0L, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) { done() }
                })
            }
        }
    }
    fun seek(fraction: Float) {
        if (horizontalMode()) evaluateJavascript("window.readerReadingAnchor = null", null)
        val resizing = resizePosition != null
        val token = ++resizeToken
        val key = renderKey
        clearResizeTransition()
        if (resizing) measurePagination {
            if (token == resizeToken && key == renderKey) restore(fraction)
        } else restore(fraction)
    }
    fun beginRender() {
        cancelTurn()
        clearResizeTransition()
        resizeToken++
        pageCount = 0
        pageStride = 0f
        pageIndex = 0
        requestedPosition = null
        restoring = true
    }
    fun dispose() {
        disposed = true
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            detector.onTouchEvent(it)
            it.recycle()
        }
        cancelTurn()
        clearResizeTransition()
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!restoring && !viewportAnimating && resizePosition == null) {
            lastKnownFraction = fraction()
            reportPosition(lastKnownFraction)
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val resizing = oldw > 0 && oldh > 0 && w > 0 && h > 0 && (w != oldw || h != oldh) && !restoring
        if (resizing && resizePosition == null) {
            resizePosition = lastKnownFraction
            resizeVerticalOffset = if (!horizontalMode() && w == oldw) scrollY else null
            cancelTurn()
            if (horizontalMode()) evaluateJavascript(captureReadingAnchorScript(pageIndex), null)
            if (horizontalMode() && w == oldw && ValueAnimator.areAnimatorsEnabled()) captureResizeSnapshot(oldw, oldh)
        }
        super.onSizeChanged(w, h, oldw, oldh)
        if (resizing) {
            resizeToken++
            if (!viewportAnimating) scheduleResizeRestore()
        }
    }
    private fun scheduleResizeRestore() {
        val token = ++resizeToken
        val key = renderKey
        postDelayed({
            if (token == resizeToken && key == renderKey && !restoring && !viewportAnimating && resizePosition != null) {
                measurePagination {
                    if (token == resizeToken && key == renderKey && !restoring && !viewportAnimating && resizePosition != null) {
                        restoreResizedPosition(token, key)
                    }
                }
            }
        }, 80L)
    }
    private fun runPendingPageTurns(): Boolean {
        val turns = pendingResizeTurns
        if (turns == 0) return false
        clearResizeTransition()
        repeat(abs(turns)) { step(if (turns > 0) 1 else -1) }
        return true
    }
    private fun restoreResizedPosition(token: Int, key: Any?) {
        fun finish() {
            resizePosition = null
            resizeVerticalOffset = null
            lastKnownFraction = fraction()
            reportPosition(lastKnownFraction)
            if (!runPendingPageTurns()) revealResizedPage(token, key)
        }
        if (horizontalMode()) {
            evaluateJavascript(readingAnchorPageScript) { result ->
                if (token != resizeToken || key != renderKey || restoring) return@evaluateJavascript
                val index = result?.toIntOrNull()
                if (index != null && index >= 0 && pageCount > 0) {
                    pageIndex = index.coerceIn(0, pageCount - 1)
                    scrollToOffset((pageIndex * pageStride).roundToInt())
                } else restore(resizePosition ?: lastKnownFraction)
                postVisualStateCallback(0L, object : VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (token == resizeToken && key == renderKey && !restoring) finish()
                    }
                })
            }
        } else {
            val offset = resizeVerticalOffset
            if (offset != null) scrollToOffset(offset.coerceIn(0, maximum()))
            else restore(resizePosition ?: lastKnownFraction)
            finish()
        }
    }
}

@Composable
fun ReaderWebView(book: OpenBook, location: ReaderLocation, preferences: ReaderPreferences, currentPosition: () -> Float, toggle: () -> Unit, onPosition: (Float) -> Unit, onLink: (Int, String?) -> Unit, onError: () -> Unit, flush: () -> Unit, consumeTarget: (Int) -> Boolean, modifier: Modifier = Modifier, onNextChapter: () -> Unit = {}, onPreviousChapter: () -> Unit = {}, turnRequest: Int = 0, seekRequest: ReaderSeekRequest? = null, onChapterPull: (Float) -> Unit = {}, viewportAnimating: Boolean = false) {
    val fontScale = LocalDensity.current.fontScale
    val palette = preferences.palette
    val colors = EpubContent.Colors(palette.background, palette.text, palette.link, palette.selection, palette.dark)
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
        view.viewportAnimating = viewportAnimating
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
