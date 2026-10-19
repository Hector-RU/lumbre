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
            vm.prepareSpeech()
        }
        try {
            compose.waitUntil(15000) { vm.state.value.book != null && vm.speechState.value.ready }
            compose.runOnIdle { vm.playSpeech() }
            compose.waitUntil(15000) { (vm.currentTextOffset() ?: 0) >= 600 || vm.speechState.value.error }
            assertFalse(vm.speechState.value.error)
            var narrated = -1
            val generationBeforePause = vm.state.value.location.generation
            compose.runOnIdle {
                vm.pauseSpeech()
                narrated = vm.currentTextOffset()!!
                // A released, older WebView can still report its last viewport.
                vm.textPositionChanged(0)
                vm.positionChanged(0f)
                vm.flush()
            }
            assertEquals("Pausing must not reload the rendered chapter", generationBeforePause, vm.state.value.location.generation)
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

    @Test fun sentenceAndChapterSkipsPreservePausedPlaybackAndDoNotReloadTheSameChapter(): Unit = kotlinx.coroutines.runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.hector.epubreader.data.local.LibraryDatabase::class.java).build()
        val repository = com.hector.epubreader.data.BookRepository(context, db.library(), com.hector.epubreader.epub.EpubParser())
        val imported = repository.import(android.net.Uri.parse("content://com.hector.epubreader.test.fixture/sample")).book
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var vm: com.hector.epubreader.ui.reader.ReaderViewModel
        compose.runOnIdle {
            vm = com.hector.epubreader.ui.reader.ReaderViewModel(context.applicationContext as ReaderApplication, imported.id, repository, instrumentation.context.packageName)
            store.put("reader", vm)
            vm.prepareSpeech()
        }
        try {
            compose.waitUntil(15000) { vm.state.value.book != null && vm.speechState.value.ready }
            compose.runOnIdle { vm.skipSpeechChapter(1) }
            compose.waitUntil(10000) { vm.state.value.location.chapter == 1 && vm.speechState.value.content.isNotEmpty() }
            assertFalse(vm.speechState.value.playing)
            compose.runOnIdle { vm.skipSpeechChapter(-1) }
            compose.waitUntil(10000) { vm.state.value.location.chapter == 0 && vm.speechState.value.content.isNotEmpty() }
            assertEquals(0, vm.currentTextOffset())
            val generation = vm.state.value.location.generation
            val text = vm.speechState.value.content
            val sentences = com.hector.epubreader.ui.reader.SpeechSentences(text, java.util.Locale.forLanguageTag("es"))
            val second = sentences.adjacentStart(0, 1)!!
            val third = sentences.adjacentStart(second, 1)!!
            compose.runOnIdle { vm.skipSpeech(1) }
            assertEquals(second, vm.currentTextOffset())
            compose.runOnIdle { vm.skipSpeech(1) }
            assertEquals(third, vm.currentTextOffset())
            compose.runOnIdle { vm.skipSpeech(-1) }
            assertEquals(second, vm.currentTextOffset())
            compose.runOnIdle { vm.skipSpeech(-1) }
            assertEquals(0, vm.currentTextOffset())
            assertEquals(generation, vm.state.value.location.generation)
            assertFalse(vm.speechState.value.playing)
            compose.runOnIdle { vm.playSpeech() }
            compose.waitUntil(10000) { vm.speechState.value.playing || vm.speechState.value.error }
            assertFalse(vm.speechState.value.error)
            var nextSpoken = -1
            compose.runOnIdle {
                nextSpoken = sentences.adjacentStart(vm.speechState.value.sentenceStart, 1)!!
                vm.skipSpeech(1)
                vm.pauseSpeech()
            }
            assertEquals(nextSpoken, vm.currentTextOffset())
            assertEquals(generation, vm.state.value.location.generation)
            compose.runOnIdle { vm.speechTimer(15) }
            assertEquals(15, vm.sleepTimerSelection.value)
            compose.runOnIdle { vm.speechTimer(0) }
            assertEquals(0, vm.sleepTimerSelection.value)
        } finally {
            compose.runOnIdle { store.clear() }
            repository.remove(imported)
            db.close()
        }
    }

    @Test fun failedSynthesisFallsBackToAnotherInstalledVoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val complete = AtomicBoolean(false)
        lateinit var speech: ReaderSpeech
        compose.runOnIdle { speech = ReaderSpeech(instrumentation.targetContext, { _, _ -> }, { complete.set(true) }, instrumentation.context.packageName) }
        try {
            compose.waitUntil(15000) { speech.state.value.ready || speech.state.value.error }
            compose.runOnIdle { speech.play("Primera oración. Segunda oración.", 0, "es") }
            compose.waitUntil(15000) { complete.get() || speech.state.value.error }
            assertTrue(complete.get())
            assertFalse(speech.state.value.error)
            assertEquals("test-broken-es", speech.state.value.voices.first().name)
            assertEquals("test-es", speech.state.value.voice)
        } finally { compose.runOnIdle { speech.close() } }
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
            assertEquals(com.hector.epubreader.ui.reader.SpeechFailure.VOICE, speech.state.value.failure)
            compose.runOnIdle { speech.seek(12); speech.selectVoice("test-es") }
            assertEquals(12, speech.state.value.offset)
            completed.set(false)
            compose.runOnIdle { speech.play(text, 12, "spa") }
            compose.waitUntil(15000) { completed.get() || speech.state.value.error }
            assertFalse(speech.state.value.error)
            assertEquals("test-es", speech.state.value.voice)
            compose.runOnIdle { speech.setRate(8f) }
            assertEquals(2f, speech.state.value.rate, 0f)
        } finally { compose.runOnIdle { speech.close() } }
    }
}
