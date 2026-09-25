package com.hector.epubreader

import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.ReaderPalette
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.ui.reader.ReaderLocation
import com.hector.epubreader.ui.reader.ReaderWebView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ReaderRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersOfflineAndPreservesPositionAcrossTypographyChanges(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        try {
            val book = repository.open(imported.id)
            val position = AtomicReference(0.42f)
            val ready = AtomicInteger(0)
            val failed = AtomicBoolean(false)
            val preferences = mutableStateOf(ReaderPreferences())
            compose.setContent {
                if (visible.value) ReaderWebView(book, ReaderLocation(position = 0.42f), preferences.value,
                    currentPosition = { position.get() }, toggle = {},
                    onPosition = { position.set(it); ready.incrementAndGet() },
                    onLink = { _, _ -> }, onError = { failed.set(true) }, flush = {}, consumeTarget = { false },
                    modifier = Modifier.fillMaxSize())
            }
            compose.waitUntil(30_000) { ready.get() > 0 || failed.get() }
            assertFalse("WebView failed to load the local EPUB", failed.get())
            assertEquals(0.42f, position.get(), 0.025f)
            var firstHeight = 0
            onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                if (error != null) throw error
                val webView = view as WebView
                assertFalse(webView.settings.javaScriptEnabled)
                assertFalse(webView.settings.allowFileAccess)
                assertFalse(webView.settings.allowContentAccess)
                assertTrue(webView.settings.blockNetworkLoads)
                assertTrue("Chapter must have rendered scrollable content", webView.contentHeight > 2000)
                firstHeight = webView.contentHeight
            }
            compose.runOnIdle { ready.set(0); preferences.value = preferences.value.copy(fontSize = 28f, palette = ReaderPalette.DARK) }
            compose.waitUntil(30_000) { ready.get() > 0 || failed.get() }
            assertFalse(failed.get())
            assertEquals(0.42f, position.get(), 0.025f)
            onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                if (error != null) throw error
                assertTrue("Larger text must change chapter layout", (view as WebView).contentHeight > firstHeight)
            }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            repository.remove(imported)
            db.close()
        }
    }
}
