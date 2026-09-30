package com.hector.epubreader

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.epub.renderer.EpubContent
import com.hector.epubreader.ui.reader.ReaderLocation
import com.hector.epubreader.ui.reader.ReaderWebView
import com.hector.epubreader.ui.reader.ReaderScreen
import com.hector.epubreader.ui.reader.ReaderViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class ReaderGestureTest {
    private fun launchReaderScenario(): ActivityScenario<ComponentActivity> =
        ActivityScenario.launch(ComponentActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun javascript(scenario: ActivityScenario<ComponentActivity>, script: String): String {
        val result = AtomicReference<String>()
        scenario.onActivity { activity ->
            requireNotNull(findWebView(activity.window.decorView)).evaluateJavascript(script) { result.set(it) }
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (result.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        return requireNotNull(result.get()) { "JavaScript did not finish" }.trim('"')
    }

    private fun touchReader(scenario: ActivityScenario<ComponentActivity>, swipe: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            scenario.onActivity { activity ->
                val web = requireNotNull(findWebView(activity.window.decorView))
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    web.width * fraction, web.height * 0.5f, 0)
                web.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, if (swipe) 0.8f else 0.5f)
        SystemClock.sleep(40)
        if (swipe) {
            send(MotionEvent.ACTION_MOVE, 0.5f)
            SystemClock.sleep(40)
        }
        send(MotionEvent.ACTION_UP, if (swipe) 0.2f else 0.5f)
    }

    private fun awaitReaderSettled(scenario: ActivityScenario<ComponentActivity>) {
        var settled = false
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!settled && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val web = requireNotNull(findWebView(activity.window.decorView))
                fun field(name: String) = web.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(web)
                val animation = field("animator") as android.animation.ValueAnimator?
                settled = field("viewportAnimating") == false && field("resizePosition") == null &&
                    field("resizeSnapshot") == null && animation?.isRunning != true
            }
            if (!settled) SystemClock.sleep(25)
        }
        assertTrue("Reader must finish resizing and page navigation", settled)
    }

    @Test fun longParagraphContinuesHorizontallyWithoutVerticalOverflow() {
        val paragraph = (1..1600).joinToString(" ") { "word$it" }
        val html = EpubContent.render("<html><body><p>$paragraph</p></body></html>".toByteArray(), ReaderPreferences(readingMode = "paragraphs"))
        val metrics = AtomicReference<String>()
        val scenario = launchReaderScenario()
        try {
            scenario.onActivity { activity ->
                val web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("[document.querySelectorAll('body > .reader-paragraph').length, Math.ceil(document.body.scrollWidth / document.body.clientWidth), document.documentElement.scrollHeight, window.innerHeight].join('|')") {
                            metrics.set(it.trim('"'))
                        }
                    }
                }
                activity.setContentView(web)
                web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (metrics.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
            val values = requireNotNull(metrics.get()) { "Paragraph layout was not measured" }.split('|').map { it.toInt() }
            assertEquals(1, values[0])
            assertTrue("Long paragraph should occupy multiple horizontal parts: $values", values[1] > 2)
            assertTrue("Paragraph mode should fit the viewport vertically: $values", values[2] <= values[3] + 2)
        } finally { scenario.close() }
    }

    @Test fun horizontalModesLandOnMeasuredPageBoundaries(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        try {
            val book = repository.open(imported.id)
            for (mode in listOf("pages", "paragraphs")) {
                val scenario = launchReaderScenario()
                val ready = AtomicBoolean(false)
                try {
                    scenario.onActivity { activity -> activity.setContent {
                        ReaderWebView(book, ReaderLocation(position = 0.5f), ReaderPreferences(readingMode = mode),
                            currentPosition = { 0.5f }, toggle = {}, onPosition = { ready.set(true) },
                            onLink = { _, _ -> }, onError = {}, flush = {}, consumeTarget = { false },
                            modifier = Modifier.fillMaxSize())
                    } }
                    val deadline = SystemClock.uptimeMillis() + 20_000
                    while (!ready.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                    assertTrue("$mode did not render", ready.get())
                    scenario.onActivity { activity ->
                        val web = requireNotNull(findWebView(activity.window.decorView))
                        val count = web.javaClass.getDeclaredField("pageCount").apply { isAccessible = true }.getInt(web)
                        val stride = web.javaClass.getDeclaredField("pageStride").apply { isAccessible = true }.getFloat(web)
                        assertTrue("$mode must have multiple positions", count > 2 && stride > 0f)
                        val index = (web.scrollX / stride).roundToInt()
                        assertTrue("$mode starts between positions: x=${web.scrollX}, stride=$stride", abs(web.scrollX - index * stride) < 3f)
                    }
                    val pageHeight = AtomicReference<Float>()
                    scenario.onActivity { activity ->
                        val web = requireNotNull(findWebView(activity.window.decorView))
                        web.evaluateJavascript("(document.getElementById('reader-flow') || document.body).getBoundingClientRect().height") {
                            pageHeight.set(it.toFloatOrNull())
                        }
                    }
                    val heightDeadline = SystemClock.uptimeMillis() + 5_000
                    while (pageHeight.get() == null && SystemClock.uptimeMillis() < heightDeadline) SystemClock.sleep(25)
                    val height = requireNotNull(pageHeight.get())
                    assertTrue("$mode page must fill the viewport; measured $height CSS px", height > 300f)
                } finally { scenario.close() }
            }
        } finally {
            repository.remove(imported)
            db.close()
        }
    }

    @Test fun horizontalModesShowCompleteLastPage(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        try {
            val book = repository.open(imported.id)
            for (mode in listOf("pages", "paragraphs")) for (chapter in book.publication.chapters.indices) {
                val scenario = launchReaderScenario()
                val ready = AtomicBoolean(false)
                val metrics = AtomicReference<String>()
                try {
                    scenario.onActivity { activity -> activity.setContent {
                        ReaderWebView(book, ReaderLocation(chapter = chapter, position = 1f), ReaderPreferences(readingMode = mode),
                            currentPosition = { 1f }, toggle = {}, onPosition = { ready.set(true) },
                            onLink = { _, _ -> }, onError = {}, flush = {}, consumeTarget = { false },
                            modifier = Modifier.fillMaxSize())
                    } }
                    val deadline = SystemClock.uptimeMillis() + 20_000
                    while (!ready.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                    assertTrue("$mode did not render", ready.get())
                    scenario.onActivity { activity ->
                        val web = requireNotNull(findWebView(activity.window.decorView))
                        val count = web.javaClass.getDeclaredField("pageCount").apply { isAccessible = true }.getInt(web)
                        val stride = web.javaClass.getDeclaredField("pageStride").apply { isAccessible = true }.getFloat(web)
                        assertTrue("$mode last page is clamped: x=${web.scrollX}, count=$count, stride=$stride",
                            abs(web.scrollX - (count - 1) * stride) < 3f)
                        web.evaluateJavascript("""
                            (function() {
                              var flow = document.getElementById('reader-flow') || document.body;
                              var walker = document.createTreeWalker(flow, NodeFilter.SHOW_TEXT);
                              var node, last;
                              while (node = walker.nextNode()) if (node.data.trim()) last = node;
                              var end = last.data.trimEnd().length;
                              var range = document.createRange();
                              range.setStart(last, end - 1); range.setEnd(last, end);
                              var rect = range.getBoundingClientRect();
                              var viewport = flow.getBoundingClientRect();
                              var visual = window.visualViewport;
                              return [rect.left - visual.offsetLeft, rect.right - visual.offsetLeft,
                                rect.top - visual.offsetTop, rect.bottom - visual.offsetTop, viewport.width, viewport.height,
                                document.documentElement.scrollWidth, Math.ceil(flow.scrollWidth / viewport.width)].join('|');
                            })()
                        """.trimIndent()) { metrics.set(it.trim('"')) }
                    }
                    val metricsDeadline = SystemClock.uptimeMillis() + 5_000
                    while (metrics.get() == null && SystemClock.uptimeMillis() < metricsDeadline) SystemClock.sleep(25)
                    val values = requireNotNull(metrics.get()).split('|').map { it.toFloat() }
                    val preferences = ReaderPreferences()
                    assertTrue("$mode document must include the full width of its last page: $values",
                        values[6] >= values[7] * values[4] - 1)
                    assertTrue("$mode final text must be fully inside the last page: $values",
                        values[0] >= preferences.horizontalMargin - 1 &&
                            values[1] <= values[4] - preferences.horizontalMargin + 1 &&
                            values[2] >= preferences.verticalMargin - 1 &&
                            values[3] <= values[5] - preferences.verticalMargin + 1)
                } finally { scenario.close() }
            }
        } finally {
            repository.remove(imported)
            db.close()
        }
    }

    @Test fun animatedViewportResizeKeepsPositionAndReleasesSnapshot(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        try {
            val book = repository.open(imported.id)
            for (mode in listOf("pages", "paragraphs", "scroll")) {
                val scenario = launchReaderScenario()
                val ready = AtomicBoolean(false)
                val compact = mutableStateOf(false)
                val turn = mutableStateOf(0)
                val position = AtomicReference(0.5f)
                try {
                    scenario.onActivity { activity -> activity.setContent {
                        val targetInset = if (compact.value) 100.dp else 0.dp
                        val inset by animateDpAsState(targetInset, tween(240))
                        ReaderWebView(book, ReaderLocation(position = 0.5f), ReaderPreferences(readingMode = mode),
                            currentPosition = { position.get() }, toggle = {},
                            onPosition = { position.set(it); ready.set(true) },
                            onLink = { _, _ -> }, onError = {}, flush = {}, consumeTarget = { false },
                            modifier = Modifier.fillMaxSize().padding(vertical = inset), turnRequest = turn.value,
                            viewportAnimating = inset != targetInset)
                    } }
                    val deadline = SystemClock.uptimeMillis() + 20_000
                    while (!ready.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                    assertTrue("$mode did not render", ready.get())
                    val originalPosition = position.get()
                    var originalOffset = 0
                    scenario.onActivity { activity ->
                        val web = requireNotNull(findWebView(activity.window.decorView))
                        originalOffset = web.scrollY
                    }
                    if (mode == "pages") assertEquals("true", javascript(scenario, """
                        (function() {
                          var visual = window.visualViewport;
                          var points = [[25, 52], [visual.offsetLeft + 25, visual.offsetTop + 52]];
                          for (var i = 0; i < points.length; i++) {
                            var caret = document.caretRangeFromPoint(points[i][0], points[i][1]);
                            if (!caret || caret.startContainer.nodeType !== Node.TEXT_NODE) continue;
                            var node = caret.startContainer;
                            var offset = Math.min(caret.startOffset, node.length - 1);
                            if (offset < 0) continue;
                            var range = document.createRange();
                            range.setStart(node, offset); range.setEnd(node, offset + 1);
                            var rect = range.getBoundingClientRect();
                            var left = rect.left - visual.offsetLeft;
                            var top = rect.top - visual.offsetTop;
                            if (left >= 0 && left < visual.width && top >= 0 && top < visual.height) {
                              window.testTurnOrigin = range;
                              return true;
                            }
                          }
                          return false;
                        })()
                    """.trimIndent()))
                    scenario.onActivity { compact.value = true }
                    if (mode != "scroll") {
                        var captured = false
                        var retainedPage: Bitmap? = null
                        val captureDeadline = SystemClock.uptimeMillis() + 1000
                        while (!captured && SystemClock.uptimeMillis() < captureDeadline) {
                            scenario.onActivity { activity ->
                                val web = requireNotNull(findWebView(activity.window.decorView))
                                val bitmap = web.javaClass.getDeclaredField("resizeSnapshot").apply { isAccessible = true }.get(web) as Bitmap?
                                if (bitmap != null) {
                                    var ink = 0
                                    for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
                                        val color = bitmap.getPixel(x, y)
                                        if (Color.alpha(color) > 0 && Color.red(color) < 180 && Color.green(color) < 180 && Color.blue(color) < 180) ink++
                                    }
                                    assertTrue("$mode snapshot must contain the visible text", ink > 20)
                                    retainedPage = bitmap
                                    captured = true
                                }
                            }
                            if (!captured) SystemClock.sleep(20)
                        }
                        assertTrue("$mode should retain its page during repagination", captured)
                        touchReader(scenario)
                        scenario.onActivity { activity ->
                            val web = requireNotNull(findWebView(activity.window.decorView))
                            val snapshot = web.javaClass.getDeclaredField("resizeSnapshot").apply { isAccessible = true }.get(web)
                            assertSame("$mode must keep the visible page when tapped during resize", retainedPage, snapshot)
                        }
                        if (mode == "pages") scenario.onActivity { turn.value++ }
                    }
                    // Reverse the transition before it finishes, as with repeated screen taps.
                    scenario.onActivity { compact.value = false }
                    SystemClock.sleep(60)
                    scenario.onActivity { compact.value = true }
                    SystemClock.sleep(60)
                    scenario.onActivity { compact.value = false }
                    SystemClock.sleep(800)
                    awaitReaderSettled(scenario)
                    val originPage = if (mode == "pages") javascript(scenario, """
                        (function() {
                          var width = document.getElementById('reader-flow').getBoundingClientRect().width;
                          var scroll = window.scrollX;
                          return Math.floor((window.testTurnOrigin.getBoundingClientRect().left + scroll) / width);
                        })()
                    """.trimIndent()).toInt() else 0
                    scenario.onActivity { activity ->
                        val web = requireNotNull(findWebView(activity.window.decorView))
                        assertNull("$mode must release the resize anchor", web.javaClass.getDeclaredField("resizePosition").apply { isAccessible = true }.get(web))
                        assertNull("$mode must release the snapshot", web.javaClass.getDeclaredField("resizeSnapshot").apply { isAccessible = true }.get(web))
                        val count = web.javaClass.getDeclaredField("pageCount").apply { isAccessible = true }.getInt(web)
                        val tolerance = if (count > 1) 1f / (count - 1) + 0.005f else 0.025f
                        assertEquals("$mode must preserve reading progress", originalPosition, position.get(), tolerance)
                        if (mode == "scroll") assertEquals("Vertical reading must keep the same text offset", originalOffset, web.scrollY)
                        if (mode == "pages") assertEquals("$mode must advance past the original visible text after resizing", originPage + 1,
                            web.javaClass.getDeclaredField("pageIndex").apply { isAccessible = true }.getInt(web))
                    }
                    if (mode == "pages") {
                        var target = 0
                        scenario.onActivity { activity ->
                            val web = requireNotNull(findWebView(activity.window.decorView))
                            target = web.javaClass.getDeclaredField("pageIndex").apply { isAccessible = true }.getInt(web) + 1
                            turn.value++
                        }
                        var turning = false
                        val turnDeadline = SystemClock.uptimeMillis() + 1000
                        while (!turning && SystemClock.uptimeMillis() < turnDeadline) {
                            scenario.onActivity { activity ->
                                val web = requireNotNull(findWebView(activity.window.decorView))
                                val animation = web.javaClass.getDeclaredField("animator").apply { isAccessible = true }.get(web) as android.animation.ValueAnimator?
                                turning = animation?.isRunning == true
                            }
                            if (!turning) SystemClock.sleep(10)
                        }
                        assertTrue("Forward page animation must start", turning)
                        touchReader(scenario)
                        awaitReaderSettled(scenario)
                        scenario.onActivity { activity ->
                            val web = requireNotNull(findWebView(activity.window.decorView))
                            assertEquals("A controls tap during a page turn must keep its destination", target,
                                web.javaClass.getDeclaredField("pageIndex").apply { isAccessible = true }.getInt(web))
                        }
                    }
                } finally { scenario.close() }
            }
        } finally {
            repository.remove(imported)
            db.close()
        }
    }

    @Test fun togglingControlsKeepsTheSameTextAfterReadingForward(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReaderApplication
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val repository = BookRepository(app, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val chapter = buildString {
            append("<html><body>")
            repeat(60) { append("<p><span id='short$it'>Short$it.</span> A few words.</p>") }
            append("<p>")
            repeat(8000) { append("word$it. ") }
            append("</p></body></html>")
        }
        try {
            val book = repository.open(imported.id)
            val replacement = File(book.file.parentFile, "resize-fixture.epub")
            ZipFile(book.file).use { source ->
                ZipOutputStream(replacement.outputStream()).use { output ->
                    source.entries().asSequence().forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == book.publication.chapters.first().path) output.write(chapter.toByteArray())
                        else source.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            replacement.copyTo(book.file, overwrite = true)
            replacement.delete()
            for (mode in listOf("pages", "paragraphs")) {
                repository.savePosition(imported.id, 0, 0.2f, book.publication.chapters.size)
                val scenario = launchReaderScenario()
                val preferences = ReaderPreferences(readingMode = mode, fontSize = 24f)
                try {
                    scenario.onActivity { activity ->
                        activity.enableEdgeToEdge()
                        val vm = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, imported.id, repository) as T
                        })[ReaderViewModel::class.java]
                        activity.setContent { ReaderScreen(vm, preferences, {}, {}, {}) }
                    }
                    fun awaitReady() {
                        var ready = false
                        val deadline = SystemClock.uptimeMillis() + 20_000
                        while (!ready && SystemClock.uptimeMillis() < deadline) {
                            scenario.onActivity { activity ->
                                val web = findWebView(activity.window.decorView)
                                ready = web != null && !web.javaClass.getDeclaredField("restoring").apply { isAccessible = true }.getBoolean(web)
                            }
                            if (!ready) SystemClock.sleep(25)
                        }
                        assertTrue("$mode did not render", ready)
                    }
                    awaitReady()
                    repeat(3) { navigation ->
                        if (navigation == 2) {
                            scenario.onActivity { activity ->
                                val web = requireNotNull(findWebView(activity.window.decorView))
                                web.javaClass.getDeclaredMethod("seek", Float::class.javaPrimitiveType).apply { isAccessible = true }.invoke(web, 0.65f)
                            }
                            SystemClock.sleep(350)
                        } else repeat(if (navigation == 0) 3 else 1) {
                            touchReader(scenario, swipe = true)
                            SystemClock.sleep(350)
                        }
                        assertEquals("$mode must start with visible text", "true", javascript(scenario, """
                            (function() {
                              var visual = window.visualViewport;
                              var points = [[${preferences.horizontalMargin + 1}, ${preferences.verticalMargin + 28}],
                                [visual.offsetLeft + ${preferences.horizontalMargin + 1}, visual.offsetTop + ${preferences.verticalMargin + 28}]];
                              for (var i = 0; i < points.length; i++) {
                                var caret = document.caretRangeFromPoint(points[i][0], points[i][1]);
                                if (!caret || caret.startContainer.nodeType !== Node.TEXT_NODE) continue;
                                var node = caret.startContainer;
                                var offset = Math.min(caret.startOffset, node.length - 1);
                                if (offset < 0) continue;
                                var range = document.createRange();
                                range.setStart(node, offset); range.setEnd(node, offset + 1);
                                var rect = range.getBoundingClientRect();
                                var left = rect.left - visual.offsetLeft;
                                var top = rect.top - visual.offsetTop;
                                if (left >= 0 && left < visual.width && top >= 0 && top < visual.height) {
                                  window.testTextAnchor = range;
                                  return true;
                                }
                              }
                              return false;
                            })()
                        """.trimIndent()))
                        repeat(if (navigation == 0) 6 else 2) { toggle ->
                            var beforeHeight = 0
                            scenario.onActivity { activity -> beforeHeight = requireNotNull(findWebView(activity.window.decorView)).height }
                            touchReader(scenario)
                            SystemClock.sleep(1100)
                            awaitReaderSettled(scenario)
                            scenario.onActivity { activity ->
                                assertNotEquals("A central tap must toggle controls", beforeHeight, requireNotNull(findWebView(activity.window.decorView)).height)
                            }
                            val metrics = javascript(scenario, """
                                (function() {
                                  var visual = window.visualViewport;
                                  var scroll = window.scrollX;
                                  var rect = window.testTextAnchor.getBoundingClientRect();
                                  return [rect.left + scroll - visual.pageLeft, rect.right + scroll - visual.pageLeft,
                                    rect.top - visual.offsetTop, rect.bottom - visual.offsetTop,
                                    visual.width, visual.height].join('|');
                                })()
                            """.trimIndent()).split('|').map { it.toFloat() }
                            assertTrue("$mode lost the original visible text after navigation $navigation, toggle $toggle: $metrics",
                                metrics[1] > 0 && metrics[0] < metrics[4] && metrics[3] > 0 && metrics[2] < metrics[5])
                        }
                    }
                } finally { scenario.close() }
            }
        } finally {
            repository.remove(imported)
            db.close()
        }
    }

    @Test fun verticalChapterArrowTracksDragAndCommitsOnlyOnRelease(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        val scenario = launchReaderScenario()
        try {
            val book = repository.open(imported.id)
            val ready = AtomicBoolean(false)
            val failed = AtomicBoolean(false)
            val pull = AtomicReference(0f)
            val next = AtomicInteger(0)
            scenario.onActivity { activity -> activity.setContent {
                if (visible.value) ReaderWebView(book, ReaderLocation(position = 1f), ReaderPreferences(),
                    currentPosition = { 1f }, toggle = {}, onPosition = { ready.set(true) },
                    onLink = { _, _ -> }, onError = { failed.set(true) }, flush = {}, consumeTarget = { false },
                    modifier = Modifier.fillMaxSize(), onChapterPull = { pull.set(it) },
                    onNextChapter = { next.incrementAndGet() })
            }
            }
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (!ready.get() && !failed.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue("Vertical reader did not become ready", ready.get())
            assertFalse(failed.get())
            scenario.onActivity { activity ->
                val web = requireNotNull(findWebView(activity.window.decorView))
                assertFalse(web.isVerticalScrollBarEnabled)
                val x = web.width * 0.75f
                val y = web.height * 0.7f
                val threshold = 96 * web.resources.displayMetrics.density
                val before = next.get()
                val start = SystemClock.uptimeMillis()
                fun send(action: Int, distance: Float, tick: Long) {
                    val event = MotionEvent.obtain(start, start + tick, action, x, y - distance, 0)
                    web.dispatchTouchEvent(event)
                    event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 0f, 0)
                send(MotionEvent.ACTION_MOVE, threshold * 0.5f, 20)
                assertEquals(0.5f, pull.get(), 0.05f)
                assertEquals(before, next.get())
                send(MotionEvent.ACTION_CANCEL, threshold * 0.5f, 40)
                assertEquals(0f, pull.get(), 0f)
                send(MotionEvent.ACTION_DOWN, 0f, 60)
                send(MotionEvent.ACTION_MOVE, threshold * 1.2f, 80)
                assertEquals(1f, pull.get(), 0f)
                assertEquals(before, next.get())
                send(MotionEvent.ACTION_UP, threshold * 1.2f, 100)
                assertEquals(before + 1, next.get())
                assertEquals(0f, pull.get(), 0f)
            }
        } finally {
            scenario.onActivity { visible.value = false }
            scenario.close()
            repository.remove(imported)
            db.close()
        }
    }

}
