package com.hector.epubreader

import android.net.Uri
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.data.BookRepository
import com.hector.epubreader.data.local.LibraryDatabase
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.EpubParser
import com.hector.epubreader.ui.reader.ReaderScreen
import com.hector.epubreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AnnotationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun screenshot(name: String) {
        // UiAutomation attaches an accessibility service. Capture only in a separate
        // opt-in visual run so gesture regressions keep their original environment.
        if (InstrumentationRegistry.getArguments().getString("annotationScreenshots") != "true") return
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        android.os.SystemClock.sleep(600) // Let the window/compositor finish the sheet transition.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(bitmap).let { image ->
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    @Test fun appearanceControlsAndSaveRemainReachableWithLargeText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val draft = mutableStateOf(com.hector.epubreader.data.local.AnnotationEntity(bookId = "preview", chapter = 0, resourcePath = "chapter.xhtml", startOffset = 0, endOffset = 20,
            quote = "Un fragmento de lectura para comprobar las marcas de color y su legibilidad.", note = "", style = "shade"))
        val saved = java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                MaterialTheme {
                    com.hector.epubreader.ui.reader.AnnotationEditor(draft.value,
                        ReaderPreferences(palette = com.hector.epubreader.data.preferences.ReaderPalette.DARK), false, false,
                        change = { draft.value = it }, dismiss = {}, save = { saved.set(true) })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.annotation_both)).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.annotation_green)).performScrollTo().performClick().assertIsSelected()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Una nota con texto grande")
        compose.onNodeWithText(context.getString(R.string.save_annotation)).assertIsDisplayed().performClick()
        assertTrue(saved.get())
        assertEquals("both", draft.value.style)
        assertEquals("green", draft.value.color)
        screenshot("annotation-style-large-text.png")
    }

    @Test fun longPressOpensEditorAndNotesCanBeSavedEditedAndDeleted(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val repository = BookRepository(context, db.library(), EpubParser())
        val imported = repository.import(Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val vm = ReaderViewModel(context.applicationContext as ReaderApplication, imported.id, repository)
        val store = ViewModelStore().apply { put("reader", vm) }
        val visible = mutableStateOf(true)
        try {
            compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(vm, ReaderPreferences(readingMode = "pages"), {}, {}, {}) } }
            compose.waitUntil(30_000) { vm.state.value.book != null }
            compose.waitUntil(30_000) { vm.currentTextOffset() != null }
            val point = AtomicReference<Pair<Float, Float>>()
            val readerWeb = AtomicReference<WebView>()
            // Wait for the reader's first completed layout, then long press an actual word.
            compose.waitUntil(30_000) {
                onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                    if (error != null) throw error
                    readerWeb.set(view as WebView)
                    (view as WebView).evaluateJavascript("""
                        (function(){var m=document.querySelector('p [data-reader-offset]');if(!m)return '';var r=document.createRange();r.setStart(m.firstChild,2);r.setEnd(m.firstChild,3);var b=r.getBoundingClientRect();return ((b.left+b.right)/2*devicePixelRatio)+'|'+((b.top+b.bottom)/2*devicePixelRatio)})()
                    """) { value ->
                        val parts = value.removeSurrounding("\"").split('|')
                        val x = parts.getOrNull(0)?.toFloatOrNull(); val y = parts.getOrNull(1)?.toFloatOrNull()
                        if (x != null && y != null && y > 0) point.set(x to y)
                    }
                }
                point.get() != null
            }
            onView(isAssignableFrom(WebView::class.java)).perform(GeneralClickAction(Tap.LONG, { view ->
                val origin = IntArray(2); view.getLocationOnScreen(origin)
                floatArrayOf(origin[0] + point.get().first, origin[1] + point.get().second)
            }, Press.FINGER))
            // The selected-text action also belongs to the reader's accessibility node,
            // avoiding Android 17's remote floating-toolbar surface and its coordinates.
            val action = AtomicReference<Int>()
            compose.waitUntil(10_000) {
                onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                    if (error != null) throw error
                    action.set(view.createAccessibilityNodeInfo().actionList.firstOrNull { it.label == context.getString(R.string.annotate_selection) }?.id)
                }
                action.get() != null
            }
            onView(isAssignableFrom(WebView::class.java)).check { view, error ->
                if (error != null) throw error
                assertTrue(view.performAccessibilityAction(action.get(), null))
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(context.getString(R.string.annotation_underline)).performClick().assertIsSelected()
            compose.onNodeWithContentDescription(context.getString(R.string.annotation_purple)).performClick().assertIsSelected()
            screenshot("annotation-style-editor.png")
            compose.onNode(hasSetTextAction()).performTextInput("First note")
            compose.onNodeWithText(context.getString(R.string.save_annotation)).performClick()
            compose.waitUntil(10_000) { vm.annotations.value.singleOrNull()?.note == "First note" }
            assertEquals("First note", db.library().annotations(imported.id).first().single().note)
            assertEquals("underline", vm.annotations.value.single().style)
            assertEquals("purple", vm.annotations.value.single().color)
            fun renderedAppearance(background: String, decoration: String) {
                val matches = java.util.concurrent.atomic.AtomicBoolean()
                val start = vm.annotations.value.single().startOffset
                compose.waitUntil(10_000) {
                    // The annotation sheet owns window focus. Inspect the already
                    // mounted reader directly without waiting for its window to focus.
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        readerWeb.get().evaluateJavascript("""(function(){var m=document.querySelector('[data-reader-offset="$start"]');if(!m)return false;var s=getComputedStyle(m);return s.backgroundColor==='$background'&&s.textDecorationLine==='$decoration'})()""") { matches.set(it == "true") }
                    }
                    matches.get()
                }
            }
            renderedAppearance("rgba(0, 0, 0, 0)", "underline")
            compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
            compose.onNodeWithText(context.getString(R.string.annotations)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.edit_annotation)).performClick()
            compose.onNodeWithText(context.getString(R.string.annotation_underline)).assertIsSelected()
            compose.onNodeWithContentDescription(context.getString(R.string.annotation_purple)).assertIsSelected()
            compose.onNodeWithText(context.getString(R.string.annotation_shade)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.annotation_blue)).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Edited note")
            compose.onNodeWithText(context.getString(R.string.save_annotation)).performClick()
            compose.waitUntil(10_000) { vm.annotations.value.singleOrNull()?.note == "Edited note" }
            assertEquals("shade", vm.annotations.value.single().style)
            assertEquals("blue", vm.annotations.value.single().color)
            renderedAppearance("rgb(188, 220, 255)", "none")
            screenshot("annotation-style-list.png")
            compose.onNodeWithContentDescription(context.getString(R.string.delete_annotation)).performClick()
            compose.waitUntil(10_000) { vm.annotations.value.isEmpty() }
            assertTrue(db.library().annotations(imported.id).first().isEmpty())
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            store.clear()
            repository.remove(imported); db.close()
        }
    }
}
