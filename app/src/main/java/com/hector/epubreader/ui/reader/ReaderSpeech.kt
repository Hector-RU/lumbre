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
import android.speech.tts.Voice

enum class SpeechFailure { ENGINE, VOICE, AUDIO, SYNTHESIS }
data class SpeechVoice(val name: String, val language: String)
data class SpeechState(
    val ready: Boolean = false, val playing: Boolean = false, val error: Boolean = false, val rate: Float = 1f,
    val failure: SpeechFailure? = null, val voices: List<SpeechVoice> = emptyList(), val voice: String? = null,
    val content: String = "", val offset: Int = 0, val sentenceStart: Int = 0, val sentenceEnd: Int = 0
)

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
    private var sentences = SpeechSentences("", speechLocale(null))
    private var candidates: List<Voice> = emptyList()
    private val failedVoices = mutableSetOf<String>()
    private val engine = TextToSpeech(context.applicationContext, { status ->
        handler.post {
            if (!closed) {
                if (status == TextToSpeech.SUCCESS) configureEngine()
                mutableState.value = mutableState.value.copy(ready = status == TextToSpeech.SUCCESS, error = status != TextToSpeech.SUCCESS, failure = if (status == TextToSpeech.SUCCESS) null else SpeechFailure.ENGINE)
            }
        }
    }, enginePackage)

    private fun configureEngine() { engine.setAudioAttributes(attributes) }

    init {
        handler.postDelayed({ if (!closed && !state.value.ready) mutableState.value = state.value.copy(error = true, failure = SpeechFailure.ENGINE) }, 15000)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = dispatch(utteranceId) { chunk?.let { offset = it.start; publishPosition(); progress(offset, true) } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = dispatch(utteranceId) {
                chunk?.let { offset = (it.start + start).coerceIn(it.start, it.end); publishPosition(); progress(offset, false) }
            }
            override fun onDone(utteranceId: String?) = dispatch(utteranceId) {
                offset = chunk?.end ?: offset
                publishPosition(); progress(offset, false)
                speakNext()
            }
            @Deprecated("Legacy callback required by Android")
            override fun onError(utteranceId: String?) = dispatch(utteranceId) { retryVoice(SpeechFailure.SYNTHESIS) }
            override fun onError(utteranceId: String?, errorCode: Int) = dispatch(utteranceId) {
                android.util.Log.w("ReaderSpeech", "Voice ${state.value.voice} failed with code $errorCode")
                if (errorCode == TextToSpeech.ERROR_OUTPUT) fail(SpeechFailure.AUDIO)
                else retryVoice(if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) SpeechFailure.VOICE else SpeechFailure.SYNTHESIS)
            }
        })
    }

    private fun dispatch(id: String?, action: () -> Unit) { handler.post { if (!closed && id != null && id == activeId && state.value.playing) action() } }

    fun play(content: String, start: Int, languageTag: String?) {
        if (!state.value.ready) return
        prepareContent(content, start, languageTag)
        val locale = speechLocale(languageTag)
        failedVoices.clear()
        // Try every installed embedded voice; a failed preferred voice must not block the others.
        val voices = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() && it.locale.language == locale.language }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.name == state.value.voice }
                .thenBy { "legacySetLanguageVoice" in it.features.orEmpty() }
                .thenByDescending { it.locale == locale }.thenByDescending { it.quality }.thenBy { it.name })
        var selected = voices.firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
        // Some engines populate their voice list only after setLanguage.
        if (selected == null && engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
            selected = engine.voice?.takeIf { !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() && it.locale.language == locale.language }
        }
        candidates = (voices + listOfNotNull(selected)).distinctBy { it.name }
        mutableState.value = state.value.copy(voices = candidates.map { SpeechVoice(it.name, it.locale.getDisplayName()) },
            voice = selected?.name, content = text, offset = offset)
        if (selected == null) { fail(SpeechFailure.VOICE); return }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail(SpeechFailure.AUDIO); return }
        engine.setSpeechRate(state.value.rate)
        mutableState.value = state.value.copy(playing = true, error = false, failure = null)
        speakNext()
    }

    private fun speakNext() {
        val next = sentences.next(offset, minOf(600, TextToSpeech.getMaxSpeechInputLength() - 1))
        if (next == null) {
            offset = text.length
            publishPosition()
            progress(offset, false)
            pause(); finished(); return
        }
        chunk = next
        publishPosition()
        val id = "reader-${++token}"
        activeId = id
        if (engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            retryVoice(SpeechFailure.SYNTHESIS)
        }
    }

    private fun retryVoice(reason: SpeechFailure) {
        state.value.voice?.let { failedVoices.add(it) }
        activeId = null
        engine.stop()
        val replacement = candidates.firstOrNull { it.name !in failedVoices && engine.setVoice(it) == TextToSpeech.SUCCESS }
        if (replacement == null) { fail(reason); return }
        mutableState.value = state.value.copy(voice = replacement.name)
        speakNext()
    }

    fun pause() {
        activeId = null
        mutableState.value = state.value.copy(playing = false)
        engine.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun fail(reason: SpeechFailure = SpeechFailure.SYNTHESIS) { pause(); mutableState.value = state.value.copy(error = true, failure = reason) }

    private fun publishPosition() {
        val (start, end) = sentences.bounds(chunk?.start ?: offset)
        mutableState.value = state.value.copy(offset = offset, sentenceStart = start, sentenceEnd = end)
    }

    fun clearContent() {
        text = ""; offset = 0; chunk = null
        mutableState.value = state.value.copy(content = "", offset = 0, sentenceStart = 0, sentenceEnd = 0)
    }

    fun prepareContent(content: String, start: Int, languageTag: String?) {
        pause()
        text = content; language = languageTag
        sentences = SpeechSentences(text, speechLocale(languageTag))
        mutableState.value = state.value.copy(content = text, error = false, failure = null)
        seek(start)
    }

    fun sentenceTarget(direction: Int): Int? = sentences.adjacentStart(state.value.sentenceStart, direction)

    fun seek(target: Int) {
        offset = target.coerceIn(0, text.length)
        chunk = sentences.next(offset)
        publishPosition()
    }

    fun selectVoice(name: String) {
        mutableState.value = state.value.copy(voice = name)
        if (state.value.playing) play(text, offset, language)
    }

    fun setRate(rate: Float) {
        mutableState.value = state.value.copy(rate = rate.coerceIn(0.5f, 2f))
        if (state.value.playing) play(text, offset, language)
    }

    fun close() { closed = true; pause(); engine.shutdown(); handler.removeCallbacksAndMessages(null) }
}
