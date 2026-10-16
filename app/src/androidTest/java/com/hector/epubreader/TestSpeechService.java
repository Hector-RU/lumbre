package com.hector.epubreader;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A deterministic, silent, offline engine packaged only in the instrumentation APK. */
public class TestSpeechService extends TextToSpeechService {
    @Override protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }
    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return Arrays.asList("eng", "spa", "en", "es").contains(lang) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String lang, String country, String variant) { return onIsLanguageAvailable(lang, country, variant); }
    @Override public List<Voice> onGetVoices() {
        return Arrays.asList(new Voice("test-en", Locale.ENGLISH, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()),
            new Voice("test-es", new Locale("es"), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }
    @Override public int onIsValidVoiceName(String name) { return name.startsWith("test-") ? TextToSpeech.SUCCESS : TextToSpeech.ERROR; }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected void onStop() { }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        callback.rangeStart(0, 0, Math.min(3, request.getCharSequenceText().length()));
        byte[] silence = new byte[2048];
        callback.audioAvailable(silence, 0, silence.length);
        callback.done();
    }
}
