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
            compose.activity.setShowWhenLocked(true)
            compose.activity.setTurnScreenOn(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    @Test fun emptyLibraryOffersDocumentImport() {
        var imported = false
        compose.setContent { EpubTheme { LibraryScreen(LibraryUiState(loading = false), ReaderPreferences(), false, { imported = true }, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Tu biblioteca está vacía").assertIsDisplayed()
        compose.onNodeWithText("Añadir EPUB").performClick()
        assertTrue(imported)
    }
    @Test fun themePreferenceCanBeChanged() {
        var dark = false
        compose.setContent { EpubTheme { SettingsScreen(ReaderPreferences(), { transform -> dark = transform(ReaderPreferences()).appTheme == "dark" }, {}) } }
        compose.onNodeWithText("Tema de la aplicación").performClick()
        compose.onNode(hasText("Oscuro") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(dark)
    }

    @Test fun settingsSearchFindsAndSelectsAReadingTheme() {
        val preferences = mutableStateOf(ReaderPreferences())
        compose.setContent { EpubTheme { SettingsScreen(preferences.value, { transform -> preferences.value = transform(preferences.value) }, {}) } }
        compose.onNodeWithText("Buscar en ajustes").performTextInput("Tema de lectura")
        compose.onNodeWithText("Tema de la aplicación").assertDoesNotExist()
        compose.onNodeWithText("Ocaso").performScrollTo().performClick().assertIsSelected()
        assertEquals(ReaderPalette.DUSK, preferences.value.palette)
    }

    @Test fun contentsSearchPreservesSubsectionNavigation() {
        val publication = EpubPublication("Un libro", null, null, null, null,
            listOf(EpubChapter("one", "one.xhtml", "Inicio"), EpubChapter("two", "two.xhtml", "Final")),
            listOf(TocEntry("Inicio", "one.xhtml"), TocEntry("Una nota del capítulo", "one.xhtml", "note", 1), TocEntry("Final", "two.xhtml")), emptyMap())
        var destination: Pair<Int, String?>? = null
        compose.setContent { EpubTheme { ContentsSheet(publication, 0) { chapter, fragment -> destination = chapter to fragment } } }
        compose.onNodeWithText("Leyendo ahora").assertIsDisplayed()
        compose.onNodeWithText("Buscar capítulo").performTextInput("nota")
        compose.onNodeWithText("Final").assertDoesNotExist()
        compose.onNodeWithText("Una nota del capítulo").performClick()
        assertEquals(0 to "note", destination)
    }
}
