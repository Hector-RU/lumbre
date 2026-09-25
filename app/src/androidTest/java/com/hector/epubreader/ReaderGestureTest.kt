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

class ReaderGestureTest {
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Test fun chapterArrowTracksDragAndCommitsOnlyOnRelease(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            val book = repository.open(imported.id)
            val preferences = mutableStateOf(ReaderPreferences())
            val ready = AtomicBoolean(false)
            val failed = AtomicBoolean(false)
            val pull = AtomicReference(0f)
            val next = AtomicInteger(0)
            scenario.onActivity { activity -> activity.setContent {
                if (visible.value) ReaderWebView(book, ReaderLocation(position = 1f), preferences.value,
                    currentPosition = { 1f }, toggle = {}, onPosition = { ready.set(true) },
                    onLink = { _, _ -> }, onError = { failed.set(true) }, flush = {}, consumeTarget = { false },
                    modifier = Modifier.fillMaxSize(), onChapterPull = { pull.set(it) }, onNextChapter = { next.incrementAndGet() })
            }
            }
            for (mode in listOf("scroll", "pages")) {
                if (mode == "pages") scenario.onActivity { ready.set(false); preferences.value = preferences.value.copy(readingMode = mode) }
                val deadline = SystemClock.uptimeMillis() + 30_000
                while (!ready.get() && !failed.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                assertTrue("Reader did not become ready", ready.get())
                assertFalse(failed.get())
                scenario.onActivity { activity ->
                    val web = requireNotNull(findWebView(activity.window.decorView))
                    assertFalse(web.isVerticalScrollBarEnabled)
                    if (mode == "pages") assertFalse(web.isLongClickable)
                    val x = web.width * 0.75f
                    val y = web.height * 0.7f
                    val threshold = 96 * web.resources.displayMetrics.density
                    val before = next.get()
                    val start = SystemClock.uptimeMillis()
                    fun send(action: Int, distance: Float, tick: Long) {
                        val event = MotionEvent.obtain(start, start + tick, action,
                            x - if (mode == "pages") distance else 0f,
                            y - if (mode == "scroll") distance else 0f, 0)
                        web.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    send(MotionEvent.ACTION_DOWN, 0f, 0)
                    send(MotionEvent.ACTION_MOVE, threshold * 0.5f, 20)
                    assertEquals(0.5f, pull.get(), 0.05f)
                    assertEquals(before, next.get())
                    send(MotionEvent.ACTION_CANCEL, threshold * 0.5f, 40)
                    assertEquals(0f, pull.get(), 0f)
                    assertEquals(before, next.get())
                    send(MotionEvent.ACTION_DOWN, 0f, 60)
                    send(MotionEvent.ACTION_MOVE, threshold * 1.2f, 80)
                    assertEquals(1f, pull.get(), 0f)
                    assertEquals(before, next.get())
                    send(MotionEvent.ACTION_UP, threshold * 1.2f, 100)
                    assertEquals(before + 1, next.get())
                    assertEquals(0f, pull.get(), 0f)
                    if (mode == "pages") {
                        val previousY = web.scrollY
                        send(MotionEvent.ACTION_DOWN, threshold, 120)
                        send(MotionEvent.ACTION_MOVE, -threshold, 140)
                        send(MotionEvent.ACTION_UP, -threshold, 160)
                        assertTrue("A side swipe must turn back a page", web.scrollY < previousY)
                    }
                }
            }
        } finally {
            scenario.onActivity { visible.value = false }
            scenario.close()
            repository.remove(imported)
            db.close()
        }
    }

}
