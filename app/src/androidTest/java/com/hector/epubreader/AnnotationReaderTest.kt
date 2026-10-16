package com.hector.epubreader

import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.AnnotationEntity
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.epub.renderer.EpubText
import com.hector.epubreader.ui.reader.ReaderLocation
import com.hector.epubreader.ui.reader.ReaderWebView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AnnotationReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeSelectionCreatesAHighlightThatSurvivesReflowAndRecreation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val visible = mutableStateOf(true)
        try {
            val book = repository.open(imported.id)
            val ready = AtomicInteger()
            val selection = AtomicReference<Pair<Int, Int>>()
            val expected = AtomicReference<String>()
            val preferences = mutableStateOf(ReaderPreferences(readingMode = "pages"))
            val notes = mutableStateOf(emptyList<AnnotationEntity>())
            compose.setContent {
                if (visible.value) ReaderWebView(book, ReaderLocation(), preferences.value,
                    currentPosition = { 0f }, toggle = {}, onPosition = { ready.incrementAndGet() },
                    onLink = { _, _ -> }, onError = { error("Render failed") }, flush = {}, consumeTarget = { false },
                    modifier = Modifier.fillMaxSize(), annotations = notes.value,
                    onSelection = { start, end -> selection.set(start to end) })
            }
            compose.waitUntil(30_000) { ready.get() > 0 }
            onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                if (error != null) throw error
                val web = view as WebView
                web.evaluateJavascript("""
                    (function() {
                      var m=Array.from(document.querySelectorAll('p [data-reader-offset]')).find(function(m){return m.firstChild.length>20});
                      var r=document.createRange();r.setStart(m.firstChild,2);r.setEnd(m.firstChild,12);
                      var s=getSelection();s.removeAllRanges();s.addRange(r);
                      var start=Number(m.getAttribute('data-reader-offset'))+2;return start+'|'+(start+10);
                    })()
                """) { value ->
                    expected.set(value.removeSurrounding("\""))
                    web.startActionMode(object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                            val item = (0 until menu.size()).map { menu.getItem(it) }.single { it.title == context.getString(R.string.annotate_selection) }
                            web.post { menu.performIdentifierAction(item.itemId, 0) }
                            return false
                        }
                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                        override fun onDestroyActionMode(mode: ActionMode) {}
                    }, ActionMode.TYPE_FLOATING)
                }
            }
            compose.waitUntil(10_000) { selection.get() != null }
            val (start, end) = selection.get()
            assertEquals("$start|$end", expected.get())
            val path = book.publication.chapters[0].path
            val canonical = EpubText.text(EpubParser().readResource(book.file, path))
            val annotation = AnnotationEntity(bookId = imported.id, chapter = 0, resourcePath = path, startOffset = start, endOffset = end, quote = canonical.substring(start, end), note = "Remember this")
            repository.saveAnnotation(annotation)
            for (mode in listOf("pages", "paragraphs", "scroll")) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                compose.runOnIdle { ready.set(0); notes.value = listOf(annotation); preferences.value = ReaderPreferences(readingMode = mode, fontSize = 28f); visible.value = true }
                compose.waitUntil(30_000) { ready.get() > 0 }
                val result = AtomicReference<String>()
                onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                    if (error != null) throw error
                    (view as WebView).evaluateJavascript("""
                        (function(){var m=document.querySelector('[data-reader-offset="$start"]');return m&&getComputedStyle(m).backgroundColor==='rgb(255, 224, 130)'&&m.textContent===${org.json.JSONObject.quote(annotation.quote)};})()
                    """) { result.set(it) }
                }
                compose.waitUntil(5000) { result.get() != null }
                assertEquals("Highlight must remain visible in $mode", "true", result.get())
            }
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            repository.remove(imported); db.close()
        }
    }
}
