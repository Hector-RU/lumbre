package com.hector.epubreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.ui.reader.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SpeechPlayerTest {
    @get:Rule val compose = createComposeRule()

    private fun captureOption(name: String) {
        if (InstrumentationRegistry.getArguments().getString("speechScreenshots") != "true") return
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun playerActionsAndOptionsRemainReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var toggled = false
        var skipped = 0
        var minutes = 0
        var chapterDirection = 0
        val timerChoice = androidx.compose.runtime.mutableIntStateOf(0)
        var selected = ""
        val content = "Una mañana, el principito descubrió un nuevo lugar. La luz entraba suavemente por la ventana. " +
            "Se sentó a escuchar el viento entre los árboles y comenzó a imaginar su siguiente viaje."
        val speech = SpeechState(ready = true, playing = true, content = content, offset = 50, sentenceStart = 50, sentenceEnd = 150,
            voices = listOf(SpeechVoice("test-es", "Español")), voice = "test-es")
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {}
            ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SpeechPlayer("El principito", "Capítulo I", null, speech, 0, toggle = { toggled = true }, seek = {},
                skip = { skipped = it }, rate = {}, voice = { selected = it }, timer = { minutes = it; timerChoice.intValue = it }, retry = {},
                chapterSkip = { chapterDirection = it }, canPreviousChapter = true, canNextChapter = true,
                selectedTimer = timerChoice.intValue)
            }
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.speech_pause)).performScrollTo().performClick()
        assertTrue(toggled)
        compose.onNodeWithContentDescription(context.getString(R.string.speech_forward)).performScrollTo().performClick()
        assertEquals(1, skipped)
        compose.onNodeWithContentDescription(context.getString(R.string.previous_chapter)).performScrollTo().performClick()
        assertEquals(-1, chapterDirection)
        compose.onNodeWithContentDescription(context.getString(R.string.next_chapter)).performScrollTo().performClick()
        assertEquals(1, chapterDirection)
        compose.onNodeWithText(context.getString(R.string.speech_timer)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.speech_minutes, 15)).performClick()
        assertEquals(15, minutes)
        compose.onNodeWithText(context.getString(R.string.speech_timer)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.speech_minutes, 15)).assertIsSelected()
        captureOption("speech-timer-selected.png")
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.onNodeWithText(context.getString(R.string.speech_voice)).performScrollTo().performClick()
        compose.onNodeWithText("Español\ntest-es").assertIsSelected()
        captureOption("speech-voice-selected.png")
        compose.onNodeWithText("Español\ntest-es").performClick()
        assertEquals("test-es", selected)
        if (InstrumentationRegistry.getArguments().getString("speechScreenshots") == "true") {
            compose.onNodeWithContentDescription(context.getString(R.string.speech_pause)).performScrollTo()
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            android.os.SystemClock.sleep(600)
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            java.io.File(context.getExternalFilesDir(null), "speech-player.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun miniPlayerFloatsAboveVisibleReaderSettings(): Unit = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.hector.epubreader.data.local.LibraryDatabase::class.java).build()
        val repository = com.hector.epubreader.data.BookRepository(context, db.library(), com.hector.epubreader.epub.EpubParser())
        val imported = repository.import(android.net.Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        lateinit var vm: ReaderViewModel
        val store = androidx.lifecycle.ViewModelStore()
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.runOnIdle {
            vm = ReaderViewModel(context.applicationContext as ReaderApplication, imported.id, repository, InstrumentationRegistry.getInstrumentation().context.packageName)
            store.put("reader", vm)
        }
        try {
            compose.waitUntil(15000) { vm.state.value.book != null }
            compose.setContent { MaterialTheme {
                if (visible.value) ReaderScreen(vm,
                    com.hector.epubreader.data.preferences.ReaderPreferences(palette = com.hector.epubreader.data.preferences.ReaderPalette.GRAY),
                    change = {}, back = {}, settings = {})
            } }
            compose.onNodeWithContentDescription(context.getString(R.string.read_aloud)).performClick()
            compose.waitForIdle()
            androidx.test.espresso.Espresso.pressBack()
            compose.mainClock.advanceTimeBy(800)
            compose.onNodeWithContentDescription(context.getString(R.string.speech_close)).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.contents)).assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("speechScreenshots") == "true") {
                compose.waitForIdle()
                android.os.SystemClock.sleep(600)
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                java.io.File(context.getExternalFilesDir(null), "speech-floating.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally {
            compose.runOnIdle { visible.value = false; store.clear() }
            compose.waitForIdle()
            repository.remove(imported)
            db.close()
        }
    }
}
