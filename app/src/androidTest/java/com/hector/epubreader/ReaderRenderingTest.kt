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
            val textOffset = AtomicReference<Int?>(null)
            val captures = AtomicInteger(0)
            val ready = AtomicInteger(0)
            val failed = AtomicBoolean(false)
            val preferences = mutableStateOf(ReaderPreferences())
            compose.setContent {
                if (visible.value) ReaderWebView(book, ReaderLocation(position = 0.42f), preferences.value,
                    currentPosition = { position.get() }, toggle = {},
                    onPosition = { position.set(it); ready.incrementAndGet() },
                    onLink = { _, _ -> }, onError = { failed.set(true) }, flush = {}, consumeTarget = { false },
                    modifier = Modifier.fillMaxSize(), onTextPosition = { textOffset.set(it); captures.incrementAndGet() }, currentTextOffset = { textOffset.get() })
            }
            compose.waitUntil(30_000) { ready.get() > 0 || failed.get() }
            assertFalse("WebView failed to load the local EPUB", failed.get())
            assertEquals(0.42f, position.get(), 0.025f)
            var firstHeight = 0
            onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                if (error != null) throw error
                val webView = view as WebView
                assertTrue(webView.settings.javaScriptEnabled)
                assertFalse(webView.settings.allowFileAccess)
                assertFalse(webView.settings.allowContentAccess)
                assertTrue(webView.settings.blockNetworkLoads)
                assertTrue("Chapter must have rendered scrollable content", webView.contentHeight > 2000)
                firstHeight = webView.contentHeight
            }
            compose.waitUntil(5000) { textOffset.get() != null }
            val beforeOffset = textOffset.get()!!
            val capturesBefore = captures.get()
            compose.runOnIdle { ready.set(0); preferences.value = preferences.value.copy(fontSize = 28f, palette = ReaderPalette.DARK) }
            compose.waitUntil(30_000) { ready.get() > 0 || failed.get() }
            assertFalse(failed.get())
            compose.waitUntil(5000) { textOffset.get() != null }
            compose.waitUntil(5000) { captures.get() > capturesBefore }
            assertTrue("Same text must be retained after changing typography", kotlin.math.abs(textOffset.get()!! - beforeOffset) < 200)
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
    @Test fun textLocatorSurvivesRecreationModesAndAccessiblePageTurns(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        try {
            val book = repository.open(imported.id)
            val preferences = mutableStateOf(ReaderPreferences())
            val target = AtomicReference<Int?>(null)
            val observed = AtomicReference<Int?>(null)
            val ready = AtomicInteger(0)
            val failed = AtomicBoolean(false)
            compose.setContent {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                if (visible.value) ReaderWebView(book, ReaderLocation(position = 0.53f, textOffset = target.get()), preferences.value,
                    currentPosition = { 0.53f }, toggle = {}, onPosition = { ready.incrementAndGet() },
                    onLink = { _, _ -> }, onError = { failed.set(true) }, flush = {}, consumeTarget = { false },
                    modifier = Modifier.fillMaxSize(), currentTextOffset = { target.get() }, onTextPosition = { observed.set(it); if (target.get() != null) target.set(it) })
                }
            }
            compose.waitUntil(30_000) { observed.get() != null || failed.get() }
            assertFalse(failed.get())
            target.set(observed.get())
            val saved = target.get()!!
            repository.savePosition(imported.id, 0, 0.53f, 2, saved)
            assertEquals(saved, repository.open(imported.id).record.readingTextOffset)
            for (mode in listOf("paragraphs", "scroll", "pages")) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                observed.set(null); ready.set(0)
                compose.runOnIdle {
                    preferences.value = ReaderPreferences(readingMode = mode, fontSize = 30f, horizontalMargin = 48)
                    visible.value = true
                }
                compose.waitUntil(30_000) { observed.get() != null || failed.get() }
                assertFalse(failed.get())
                assertEquals("Reflow must not replace the saved character with the start of its page", saved, observed.get())
                // Pagination aligns to a whole page: the locator must remain inside that page.
                val result = AtomicReference<String>()
                onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                    if (error != null) throw error
                    val web = view as WebView
                    web.evaluateJavascript("""
                        (function() {
                          var target = $saved, spans = document.querySelectorAll('[data-reader-offset]');
                          for (var i=0;i<spans.length;i++) {
                            var node=spans[i].firstChild, start=Number(spans[i].getAttribute('data-reader-offset'));
                            if (!node || target < start || target >= start + node.length) continue;
                            var range=document.createRange(); range.setStart(node,target-start); range.setEnd(node,target-start+1);
                            var r=range.getBoundingClientRect();
                            var v=window.visualViewport || {offsetLeft:${view.scrollX}/(window.devicePixelRatio||1)+document.documentElement.getBoundingClientRect().left,offsetTop:${view.scrollY}/(window.devicePixelRatio||1)+document.documentElement.getBoundingClientRect().top,width:${view.width}/(window.devicePixelRatio||1),height:${view.height}/(window.devicePixelRatio||1),pageTop:${view.scrollY}/(window.devicePixelRatio||1)}, left=v.offsetLeft, top=v.offsetTop;
                            return r.right>left && r.left<left+(v?v.width:innerWidth) && r.bottom>top && r.top<top+(v?v.height:innerHeight);
                          }
                          return false;
                        })()
                    """) { result.set(it) }
                    assertTrue(web.createAccessibilityNodeInfo().actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                }
                compose.waitUntil(5000) { result.get() != null }
                assertEquals("Locator must be visible in $mode", "true", result.get())
                if (mode == "pages") {
                    val before = observed.get()!!
                    var beforePage = -1
                    onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                        beforePage = view.javaClass.getDeclaredField("pageIndex").apply { isAccessible = true }.getInt(view)
                        assertTrue(view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                    }
                    try {
                        compose.waitUntil(5000) { (observed.get() ?: before) > before }
                    } catch (error: Throwable) {
                        val diagnostics = AtomicReference<String>()
                        val dom = AtomicReference<String>()
                        onView(isAssignableFrom(WebView::class.java)).check { view, _ ->
                            val web = view as WebView
                            val fields = listOf("pageIndex", "pageCount", "pageStride", "restoring", "resizePosition", "capturePending").associateWith {
                                web.javaClass.getDeclaredField(it).apply { isAccessible = true }.get(web)
                            }
                            diagnostics.set("scrollX=${web.scrollX}, fields=$fields")
                            web.evaluateJavascript("""
                                (function() {
                                  var spans=Array.from(document.querySelectorAll('[data-reader-offset]'));
                                  function rect(target) {
                                    var el=spans.find(function(s) { var n=s.firstChild, start=Number(s.getAttribute('data-reader-offset')); return n && target>=start && target<start+n.length; });
                                    if(!el) return null;
                                    var node=el.firstChild, start=Number(el.getAttribute('data-reader-offset')), range=document.createRange();
                                    range.setStart(node,target-start);range.setEnd(node,target-start+1);
                                    var r=range.getBoundingClientRect();
                                    return {start:start,length:node.length,rect:[r.left,r.right,r.top,r.bottom]};
                                  }
                                  return JSON.stringify({x:scrollX,w:innerWidth,h:innerHeight,dpr:devicePixelRatio,saved:rect($saved),captured:rect(${observed.get() ?: 0})});
                                })()
                            """) { dom.set(it) }
                        }
                        compose.waitUntil(5000) { dom.get() != null }
                        throw AssertionError("Accessible turn must advance text: beforePage=$beforePage before=$before after=${observed.get()} ${diagnostics.get()} DOM=${dom.get()}", error)
                    }
                }
            }
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            repository.remove(imported); db.close()
        }
    }

    @Test fun searchOpensTheSelectedOccurrenceInsteadOfTheFirst(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        try {
            val book = repository.open(imported.id)
            val hits = repository.search(book, "esperanza").filter { it.chapter == 0 }
            assertTrue(hits.size > 1)
            val hit = hits.last()
            val observed = AtomicReference<Int?>(null)
            val failed = AtomicBoolean(false)
            compose.setContent {
                if (visible.value) ReaderWebView(book, ReaderLocation(query = hit.query, textOffset = hit.textOffset), ReaderPreferences(fontSize = 24f),
                    currentPosition = { 0f }, toggle = {}, onPosition = {},
                    onLink = { _, _ -> }, onError = { failed.set(true) }, flush = {}, consumeTarget = { true },
                    modifier = Modifier.fillMaxSize(), onTextPosition = { observed.set(it) })
            }
            compose.waitUntil(30_000) { observed.get() != null || failed.get() }
            assertFalse(failed.get())
            assertEquals(hit.textOffset, observed.get())
            assertTrue(observed.get()!! > hits.first().textOffset)
            val visibleTarget = AtomicReference<String>()
            // Native scrollTo can precede Chromium's committed visual viewport. Wait
            // for the selected character to be visible, not just for the first JS reply.
            try {
                compose.waitUntil(5000) {
                    onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                        if (error != null) throw error
                        (view as WebView).evaluateJavascript("""
                            (function() {
                              var target = ${hit.textOffset}, spans = document.querySelectorAll('[data-reader-offset]');
                              for (var i=0;i<spans.length;i++) {
                                var node=spans[i].firstChild, start=Number(spans[i].getAttribute('data-reader-offset'));
                                if (!node || target < start || target >= start + node.length) continue;
                                var range=document.createRange(); range.setStart(node,target-start); range.setEnd(node,target-start+1);
                                var r=range.getBoundingClientRect();
                                var v=window.visualViewport || {offsetLeft:${view.scrollX}/(window.devicePixelRatio||1)+document.documentElement.getBoundingClientRect().left,offsetTop:${view.scrollY}/(window.devicePixelRatio||1)+document.documentElement.getBoundingClientRect().top,width:${view.width}/(window.devicePixelRatio||1),height:${view.height}/(window.devicePixelRatio||1),pageTop:${view.scrollY}/(window.devicePixelRatio||1)}, left=v.offsetLeft, top=v.offsetTop;
                                    window.testSearchMetrics=[target,r.top,r.bottom,r.left,r.right,window.scrollY,v?v.offsetTop:0,v?v.pageTop:0,v?v.height:innerHeight,document.documentElement.scrollHeight,devicePixelRatio,getComputedStyle(spans[i]).backgroundColor].join('|');
                                    return getComputedStyle(spans[i]).backgroundColor==='rgb(255, 224, 130)' && r.right>left && r.left<left+(v?v.width:innerWidth) && r.bottom>top && r.top<top+(v?v.height:innerHeight);
                              }
                              return false;
                            })()
                        """) { visibleTarget.set(it) }
                    }
                    visibleTarget.get() == "true"
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                val metrics = AtomicReference<String>()
                onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                    if (error != null) throw error
                    val web = view as WebView
                    web.evaluateJavascript("window.testSearchMetrics") { metrics.set(it) }
                    android.util.Log.i("ReaderSearchMetrics", "native=${web.scrollY}, height=${web.height}, content=${web.contentHeight}")
                }
                compose.waitUntil(5000) { metrics.get() != null }
                throw AssertionError("Selected match not visible: ${metrics.get()}", failure)
            }
            assertEquals("Selected search match must be on screen, including near the chapter end", "true", visibleTarget.get())
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            repository.remove(imported); db.close()
        }
    }

}
