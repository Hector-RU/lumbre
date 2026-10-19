package com.hector.epubreader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import com.hector.epubreader.ui.reader.speechHighlightScript
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SpeechHighlightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyCurrentSentenceIsShadedAndClearingPreservesBookMarkup() {
        val first = "Primera oración."
        val second = "Segunda oración."
        val content = "$first $second"
        val ready = AtomicBoolean(false)
        lateinit var web: WebView
        compose.setContent { AndroidView(factory = { context ->
            WebView(context).also {
                web = it
                it.settings.javaScriptEnabled = true
                it.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { ready.set(true) }
                }
                it.loadDataWithBaseURL("https://test.invalid", "<html><body><lumbre-text data-reader-offset='0' style='color:rgb(1,2,3)'>$content</lumbre-text></body></html>", "text/html", "utf-8", null)
            }
        }) }
        compose.waitUntil(10000) { ready.get() }
        fun evaluate(script: String): String {
            val value = AtomicReference<String?>(null)
            compose.runOnIdle { web.evaluateJavascript(script) { value.set(it) } }
            compose.waitUntil(5000) { value.get() != null }
            return value.get()!!
        }
        fun shade(start: Int?, end: Int?) = org.json.JSONTokener(evaluate(speechHighlightScript(start, end))).nextValue()
        assertEquals(first, shade(0, first.length))
        val firstLeft = evaluate("document.querySelector('[data-lumbre-speech-overlay]').style.left")
        assertEquals(second, shade(first.length + 1, content.length))
        assertNotEquals(firstLeft, evaluate("document.querySelector('[data-lumbre-speech-overlay]').style.left"))
        shade(null, null)
        assertEquals("0", evaluate("document.querySelectorAll('[data-lumbre-speech-overlay]').length"))
        // The same sentence rectangles work without the CSS Highlights API.
        evaluate("window.Highlight=undefined")
        evaluate(speechHighlightScript(0, first.length))
        assertTrue(evaluate("document.querySelectorAll('[data-lumbre-speech-overlay]').length").toInt() > 0)
        assertEquals("true", evaluate("document.querySelector('[data-lumbre-speech-overlay]').getBoundingClientRect().width < document.querySelector('lumbre-text').getBoundingClientRect().width"))
        evaluate(speechHighlightScript(first.length + 1, content.length))
        assertEquals(content, org.json.JSONTokener(evaluate("document.querySelector('lumbre-text').innerHTML")).nextValue())
        evaluate(speechHighlightScript(null, null))
        assertEquals("0", evaluate("document.querySelectorAll('[data-lumbre-speech-overlay]').length"))
        assertEquals("\"color:rgb(1,2,3)\"", evaluate("document.querySelector('lumbre-text').getAttribute('style')"))
    }

    @Test fun renderedBookKeepsItsDocumentWhileNarrationMovesAndPauses(): Unit = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.hector.epubreader.data.local.LibraryDatabase::class.java).build()
        val repository = com.hector.epubreader.data.BookRepository(context, db.library(), com.hector.epubreader.epub.EpubParser())
        val imported = repository.import(android.net.Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val range = androidx.compose.runtime.mutableStateOf<Pair<Int?, Int?>>(20 to 120)
        val ready = AtomicBoolean(false)
        try {
            val book = repository.open(imported.id)
            compose.setContent {
                if (visible.value) com.hector.epubreader.ui.reader.ReaderWebView(book,
                    com.hector.epubreader.ui.reader.ReaderLocation(), com.hector.epubreader.data.preferences.ReaderPreferences(),
                    currentPosition = { 0f }, toggle = {}, onPosition = { ready.set(true) }, onLink = { _, _ -> },
                    onError = { throw AssertionError("Book failed to render") }, flush = {}, consumeTarget = { false },
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    speechStart = range.value.first, speechEnd = range.value.second, speechOffset = range.value.first)
            }
            compose.waitUntil(30000) { ready.get() }
            lateinit var web: com.hector.epubreader.ui.reader.ReadingWebView
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(WebView::class.java)).check { view, error ->
                if (error != null) throw error
                web = view as com.hector.epubreader.ui.reader.ReadingWebView
            }
            fun evaluate(script: String): String {
                val result = AtomicReference<String?>(null)
                compose.runOnIdle { web.evaluateJavascript(script) { result.set(it) } }
                compose.waitUntil(5000) { result.get() != null }
                return result.get()!!
            }
            compose.waitUntil(5000) { web.lastSpeechRange == (20 to 120) }
            evaluate("window.narrationDocumentSentinel = 'same-document'")
            assertTrue(evaluate("document.querySelectorAll('[data-lumbre-speech-overlay]').length").toInt() > 0)
            compose.runOnIdle { range.value = 800 to 900 }
            compose.waitUntil(5000) { web.lastSpeechRange == (800 to 900) }
            assertTrue(evaluate("document.querySelectorAll('[data-lumbre-speech-overlay]').length").toInt() > 0)
            assertEquals("\"same-document\"", evaluate("window.narrationDocumentSentinel"))
            compose.runOnIdle { range.value = null to null }
            compose.waitUntil(5000) { web.lastSpeechRange == (null to null) }
            assertEquals("0", evaluate("document.querySelectorAll('[data-lumbre-speech-overlay]').length"))
            assertEquals("\"same-document\"", evaluate("window.narrationDocumentSentinel"))
            assertFalse(web.restoring)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            repository.remove(imported)
            db.close()
        }
    }
}
