package com.hector.epubreader

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hector.epubreader.ui.reader.ReaderSpeech
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt in on a connected phone; exercises its installed engine without touching the library. */
class DeviceSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    }
    @Test fun installedSpanishVoiceReadsToCompletion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSpeech") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialized = AtomicInteger(-100)
        lateinit var engine: TextToSpeech
        compose.runOnIdle { engine = TextToSpeech(context, { initialized.set(it) }) }
        compose.waitUntil(20000) { initialized.get() != -100 }
        compose.runOnIdle {
            report("init=${initialized.get()} default=${engine.defaultEngine} language=${java.util.Locale.getDefault()}")
            report("Spanish voices=" + engine.voices.orEmpty().filter { it.locale.language in listOf("es", "spa") }
                .joinToString { "${it.name}:locale=${it.locale},network=${it.isNetworkConnectionRequired},features=${it.features}" })
            engine.shutdown()
        }
        lateinit var speech: ReaderSpeech
        val complete = AtomicBoolean(false)
        val started = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val text = "Esta es una prueba de lectura. Ahora se lee la segunda oración."
        compose.runOnIdle { speech = ReaderSpeech(context, { at, chunk -> if (chunk) started.add(at) }, { complete.set(true) }) }
        try {
            compose.waitUntil(20000) { speech.state.value.ready || speech.state.value.error }
            assertTrue("Engine initialization: ${speech.state.value}", speech.state.value.ready)
            compose.runOnIdle { speech.play(text, 0, "es") }
            compose.waitUntil(20000) { complete.get() || speech.state.value.error }
            report("Playback=${speech.state.value}")
            assertFalse("Playback failed: ${speech.state.value.failure}", speech.state.value.error)
            assertTrue(complete.get())
            assertEquals(setOf(0, text.indexOf("Ahora")), started)
            assertEquals(text.indexOf("Ahora"), speech.state.value.sentenceStart)
        } finally { compose.runOnIdle { speech.close() } }
    }
}
