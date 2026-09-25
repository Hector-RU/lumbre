package com.hector.epubreader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.ui.LibraryUiState
import com.hector.epubreader.ui.library.LibraryScreen
import com.hector.epubreader.ui.settings.SettingsScreen
import com.hector.epubreader.ui.theme.EpubTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryUiTest {
    @get:Rule val compose = createComposeRule()
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
        compose.onNodeWithText("Oscuro").performClick()
        assertTrue(dark)
    }
}
