package com.hector.epubreader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.data.preferences.ReaderPalette
import androidx.compose.runtime.mutableStateOf
import com.hector.epubreader.epub.EpubPublication
import com.hector.epubreader.epub.EpubChapter
import com.hector.epubreader.epub.TocEntry
import com.hector.epubreader.ui.reader.ContentsSheet
import com.hector.epubreader.ui.LibraryUiState
import com.hector.epubreader.ui.library.LibraryScreen
import com.hector.epubreader.ui.settings.SettingsScreen
import com.hector.epubreader.ui.theme.EpubTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class LibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepTestActivityVisible() {
        compose.runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            } else {
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    @Test fun emptyLibraryOffersDocumentImport() {
        var imported = false
        compose.setContent { EpubTheme { LibraryScreen(LibraryUiState(loading = false), ReaderPreferences(), false, { imported = true }, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.empty_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.add_book)).performClick()
        assertTrue(imported)
    }
    @Test fun collectionsAndReadingFiltersCanBeCombined() {
        val base = com.hector.epubreader.data.local.BookEntity("one", "Unread book", "Author", "", "hash1", null, null, null, 2, collection = "Study")
        val state = mutableStateOf(LibraryUiState(books = listOf(base, base.copy(id = "two", title = "Finished book", checksum = "hash2", progress = 1f)), loading = false))
        compose.setContent { EpubTheme { LibraryScreen(state.value, ReaderPreferences(), false, {}, {}, {}, { _, _ -> }, { book, name ->
            state.value = state.value.copy(books = state.value.books.map { if (it.id == book.id) it.copy(collection = name) else it })
        }) } }
        compose.onNodeWithText("Study").performClick().assertIsSelected()
        compose.onNodeWithText(compose.activity.getString(R.string.filter_unread)).performClick().assertIsSelected()
        compose.onNodeWithText("Unread book").assertIsDisplayed()
        compose.onNodeWithText("Finished book").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.book_options, "Unread book")).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.collection)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.collection_name)).performTextReplacement("Fiction")
        compose.onNodeWithText(compose.activity.getString(R.string.collection_save)).performClick()
        compose.onNodeWithText("Fiction").assertExists()
        assertEquals("Fiction", state.value.books.first().collection)
    }

    @Test fun themePreferenceCanBeChanged() {
        var dark = false
        compose.setContent { EpubTheme { SettingsScreen(ReaderPreferences(), { transform -> dark = transform(ReaderPreferences()).appTheme == "dark" }, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.app_theme)).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.dark_theme)) and hasAnyAncestor(isDialog())).assertIsNotSelected().performClick()
        assertTrue(dark)
    }

    @Test fun settingsSearchFindsAndSelectsAReadingTheme() {
        val preferences = mutableStateOf(ReaderPreferences())
        compose.setContent { EpubTheme { SettingsScreen(preferences.value, { transform -> preferences.value = transform(preferences.value) }, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.search_settings)).performTextInput(compose.activity.getString(R.string.reader_theme))
        compose.onNodeWithText(compose.activity.getString(R.string.app_theme)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.palette_dusk)).performScrollTo().performClick().assertIsSelected()
        assertEquals(ReaderPalette.DUSK, preferences.value.palette)
    }

    @Test fun contentsSearchPreservesSubsectionNavigation() {
        val publication = EpubPublication("Un libro", null, null, null, null,
            listOf(EpubChapter("one", "one.xhtml", "Inicio"), EpubChapter("two", "two.xhtml", "Final")),
            listOf(TocEntry("Inicio", "one.xhtml"), TocEntry("Una nota del capítulo", "one.xhtml", "note", 1), TocEntry("Final", "two.xhtml")), emptyMap())
        var destination: Pair<Int, String?>? = null
        compose.setContent { EpubTheme { ContentsSheet(publication, 0) { chapter, fragment -> destination = chapter to fragment } } }
        compose.onNodeWithText(compose.activity.getString(R.string.current_chapter)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.search_contents)).performTextInput("nota")
        compose.onNodeWithText("Final").assertDoesNotExist()
        compose.onNodeWithText("Una nota del capítulo").performClick()
        assertEquals(0 to "note", destination)
    }
}
