package com.hector.epubreader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.ui.reader.ReaderSpeech
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReaderSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pausingReaderPreservesNarratedPositionAgainstStaleWebViewCaptures(): Unit = kotlinx.coroutines.runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.hector.epubreader.data.local.LibraryDatabase::class.java).build()
        val repository = com.hector.epubreader.data.BookRepository(context, db.library(), com.hector.epubreader.epub.EpubParser())
        val book = repository.import(android.net.Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        lateinit var vm: com.hector.epubreader.ui.reader.ReaderViewModel
        val store = androidx.lifecycle.ViewModelStore()
        compose.runOnIdle {
            vm = com.hector.epubreader.ui.reader.ReaderViewModel(context.applicationContext as ReaderApplication, book.id, repository, instrumentation.context.packageName)
            store.put("reader", vm)
        }
        try {
            compose.waitUntil(15000) { vm.state.value.book != null && vm.speechState.value.ready }
            compose.runOnIdle { vm.playSpeech() }
            compose.waitUntil(15000) { (vm.currentTextOffset() ?: 0) >= 600 || vm.speechState.value.error }
            assertFalse(vm.speechState.value.error)
            var narrated = -1
            compose.runOnIdle {
                vm.pauseSpeech()
                narrated = vm.currentTextOffset()!!
                // A released, older WebView can still report its last viewport.
                vm.textPositionChanged(0)
                vm.positionChanged(0f)
                vm.flush()
            }
            assertEquals(narrated, vm.currentTextOffset())
            compose.waitUntil(5000) { kotlinx.coroutines.runBlocking { db.library().book(book.id)?.readingTextOffset == narrated } }
            assertEquals(narrated, repository.open(book.id).record.readingTextOffset)
            assertFalse(vm.speechState.value.playing)
        } finally {
            compose.runOnIdle { store.clear() }
            repository.remove(book)
            db.close()
        }
    }

    @Test fun offlinePlaybackReportsOffsetsCompletesAndRejectsUnsupportedLanguage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val completed = AtomicBoolean(false)
        val offset = AtomicInteger(-1)
        val chunks = AtomicInteger(0)
        val pauseNext = AtomicBoolean(true)
        lateinit var speech: ReaderSpeech
        compose.runOnIdle {
            speech = ReaderSpeech(instrumentation.targetContext, { position, chunk ->
                offset.set(position); if (chunk) { chunks.incrementAndGet(); if (pauseNext.getAndSet(false)) speech.pause() }
            }, { completed.set(true) }, instrumentation.context.packageName)
        }
        try {
            compose.waitUntil(15000) { speech.state.value.ready || speech.state.value.error }
            assertTrue("Test engine must initialize", speech.state.value.ready)
            val text = "Offline reading with Unicode café 😀. ".repeat(40)
            compose.runOnIdle { speech.play(text, 8, "en") }
            compose.waitUntil(10000) { chunks.get() == 1 }
            assertFalse(speech.state.value.playing)
            assertEquals(8, offset.get())
            assertFalse(completed.get())
            compose.runOnIdle { speech.play(text, offset.get(), "en") }
            try { compose.waitUntil(15000) { completed.get() || speech.state.value.error } }
            catch (error: Throwable) { throw AssertionError("Playback state=${speech.state.value}; chunks=${chunks.get()}; offset=${offset.get()}", error) }
            assertFalse(speech.state.value.error)
            assertTrue(completed.get())
            assertTrue(chunks.get() >= 2)
            assertEquals(text.length, offset.get())
            assertFalse(speech.state.value.playing)
            compose.runOnIdle { speech.play(text, 0, "zz") }
            assertTrue(speech.state.value.error)
            assertFalse(speech.state.value.playing)
            compose.runOnIdle { speech.setRate(8f) }
            assertEquals(2f, speech.state.value.rate, 0f)
        } finally { compose.runOnIdle { speech.close() } }
    }
}
