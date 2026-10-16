package com.hector.epubreader.ui.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SpeechState(val ready: Boolean = false, val playing: Boolean = false, val error: Boolean = false, val rate: Float = 1f)

/** Foreground-only playback. All state and engine calls are serialized on the main looper. */
internal class ReaderSpeech(context: Context, private val progress: (Int, Boolean) -> Unit, private val finished: () -> Unit, enginePackage: String? = null, private val interrupted: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) { pause(); interrupted() } }, handler).build()
    private val mutableState = MutableStateFlow(SpeechState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private var text = ""
    private var offset = 0
    private var token = 0L
    private var activeId: String? = null
    private var chunk: SpeechChunk? = null
    private var language: String? = null
    private val engine = TextToSpeech(context.applicationContext, { status ->
        handler.post {
            if (!closed) {
                if (status == TextToSpeech.SUCCESS) configureEngine()
                mutableState.value = mutableState.value.copy(ready = status == TextToSpeech.SUCCESS, error = status != TextToSpeech.SUCCESS)
            }
        }
    }, enginePackage)

    private fun configureEngine() { engine.setAudioAttributes(attributes) }

    init {
        handler.postDelayed({ if (!closed && !state.value.ready) mutableState.value = state.value.copy(error = true) }, 15000)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = dispatch(utteranceId) { chunk?.let { offset = it.start; progress(offset, true) } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = dispatch(utteranceId) {
                chunk?.let { offset = (it.start + start).coerceIn(it.start, it.end); progress(offset, false) }
            }
            override fun onDone(utteranceId: String?) = dispatch(utteranceId) {
                offset = chunk?.end ?: offset
                progress(offset, false)
                speakNext()
            }
            @Deprecated("Legacy callback required by Android")
            override fun onError(utteranceId: String?) = dispatch(utteranceId) { pause(); mutableState.value = mutableState.value.copy(error = true) }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
        })
    }

    private fun dispatch(id: String?, action: () -> Unit) { handler.post { if (!closed && id != null && id == activeId && state.value.playing) action() } }

    fun play(content: String, start: Int, languageTag: String?) {
        if (!state.value.ready) return
        pause()
        text = content; offset = start.coerceIn(0, text.length); language = languageTag
        val locale = languageTag?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val voice = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features && it.locale.language == locale.language }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == locale }.thenBy { it.name }).firstOrNull()
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS || audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutableState.value = state.value.copy(error = true); return
        }
        engine.setSpeechRate(state.value.rate)
        mutableState.value = state.value.copy(playing = true, error = false)
        speakNext()
    }

    private fun speakNext() {
        val next = nextSpeechChunk(text, offset, minOf(600, TextToSpeech.getMaxSpeechInputLength() - 1))
        if (next == null) { pause(); finished(); return }
        chunk = next
        val id = "reader-${++token}"
        activeId = id
        if (engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            pause(); mutableState.value = state.value.copy(error = true)
        }
    }

    fun pause() {
        activeId = null
        mutableState.value = state.value.copy(playing = false)
        engine.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun fail() { pause(); mutableState.value = state.value.copy(error = true) }

    fun setRate(rate: Float) {
        mutableState.value = state.value.copy(rate = rate.coerceIn(0.5f, 2f))
        if (state.value.playing) play(text, offset, language)
    }

    fun close() { closed = true; pause(); engine.shutdown(); handler.removeCallbacksAndMessages(null) }
}
