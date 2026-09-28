package com.hector.epubreader

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.ui.reader.ReaderLocation
import com.hector.epubreader.ui.reader.ReaderWebView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

class ReaderGestureTest {
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Test fun horizontalModesLandOnMeasuredPageBoundaries(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        try {
            val book = repository.open(imported.id)
            for (mode in listOf("pages", "paragraphs")) {
                val scenario = ActivityScenario.launch(ComponentActivity::class.java)
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
                        web.evaluateJavascript("(document.getElementById('reader-flow') || document.querySelector('.reader-paragraph')).getBoundingClientRect().height") {
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

    @Test fun verticalChapterArrowTracksDragAndCommitsOnlyOnRelease(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
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
