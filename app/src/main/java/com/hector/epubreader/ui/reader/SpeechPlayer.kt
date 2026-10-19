package com.hector.epubreader.ui.reader

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hector.epubreader.R
import java.io.File
import kotlin.math.roundToInt

@Composable
private fun SpeechCover(path: String?, modifier: Modifier = Modifier) {
    Surface(modifier.size(40.dp, 56.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        if (path != null) AsyncImage(File(path), null, contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.MenuBook, null, Modifier.padding(8.dp))
    }
}

@Composable
internal fun SpeechMiniPlayer(title: String, chapter: String, cover: String?, speech: SpeechState,
                              expand: () -> Unit, toggle: () -> Unit, skip: (Int) -> Unit, close: () -> Unit) {
    Surface(onClick = expand, shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeechCover(cover)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(chapter, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { skip(-1) }, enabled = speech.content.isNotEmpty()) { Icon(Icons.Outlined.SkipPrevious, stringResource(R.string.speech_back)) }
            FilledIconButton(onClick = toggle, enabled = speech.ready) {
                Icon(if (speech.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, stringResource(if (speech.playing) R.string.speech_pause else R.string.speech_play))
            }
            IconButton(onClick = { skip(1) }, enabled = speech.content.isNotEmpty()) { Icon(Icons.Outlined.SkipNext, stringResource(R.string.speech_forward)) }
            IconButton(onClick = close) { Icon(Icons.Outlined.Close, stringResource(R.string.speech_close)) }
        }
    }
}

private fun speechTime(characters: Int, rate: Float): String {
    val seconds = (characters / (14f * rate)).roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun SpeechPlayer(title: String, chapter: String, cover: String?, speech: SpeechState, minutes: Int,
                          toggle: () -> Unit, seek: (Float) -> Unit, skip: (Int) -> Unit, rate: (Float) -> Unit,
                          voice: (String) -> Unit, timer: (Int) -> Unit, retry: () -> Unit,
                          chapterSkip: (Int) -> Unit, canPreviousChapter: Boolean, canNextChapter: Boolean,
                          selectedTimer: Int = minutes) {
    var option by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeechCover(cover)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(chapter, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!speech.ready && !speech.error) LinearProgressIndicator(Modifier.fillMaxWidth())
        val start = speech.sentenceStart.coerceIn(0, speech.content.length)
        val end = speech.sentenceEnd.coerceIn(start, speech.content.length)
        val before = speech.content.substring(0, start).takeLast(130).substringAfter('\n')
        val current = speech.content.substring(start, end).ifBlank { stringResource(R.string.speech_start_hint) }
        val after = speech.content.substring(end).take(130).substringBefore('\n')
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (before.isNotBlank()) Text(before, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(current, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (after.isNotBlank()) Text(after, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (speech.error) {
            val message = when (speech.failure) {
                SpeechFailure.ENGINE -> R.string.speech_engine_error
                SpeechFailure.VOICE -> R.string.speech_voice_error
                SpeechFailure.AUDIO -> R.string.speech_audio_error
                else -> R.string.speech_synthesis_error
            }
            Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = {
                    val intent = Intent("com.android.settings.TTS_SETTINGS")
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                    }
                }) { Text(stringResource(R.string.speech_settings)) }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                        .onFailure { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }
                }) { Text(stringResource(R.string.speech_install)) }
            }
            if (!speech.ready) TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
        }
        var dragging by remember { mutableStateOf(false) }
        var fraction by remember { mutableFloatStateOf(0f) }
        val actual = if (speech.content.isEmpty()) 0f else speech.offset.toFloat() / speech.content.length
        LaunchedEffect(actual, dragging) { if (!dragging) fraction = actual }
        val progressLabel = stringResource(R.string.speech_progress)
        Column {
            Slider(fraction, { dragging = true; fraction = it }, onValueChangeFinished = { dragging = false; seek(fraction) },
                enabled = speech.content.isNotEmpty(), modifier = Modifier.semantics { contentDescription = progressLabel })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(speechTime((fraction * speech.content.length).toInt(), speech.rate), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.speech_estimate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("−" + speechTime(((1 - fraction) * speech.content.length).toInt(), speech.rate), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { chapterSkip(-1) }, enabled = canPreviousChapter) { Icon(Icons.Outlined.SkipPrevious, stringResource(R.string.previous_chapter)) }
            IconButton(onClick = { skip(-1) }, enabled = speech.content.isNotEmpty()) { Icon(Icons.Outlined.FastRewind, stringResource(R.string.speech_back), Modifier.size(32.dp)) }
            FilledIconButton(onClick = toggle, enabled = speech.ready, modifier = Modifier.size(72.dp)) {
                Icon(if (speech.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (speech.playing) R.string.speech_pause else R.string.speech_play), Modifier.size(40.dp))
            }
            IconButton(onClick = { skip(1) }, enabled = speech.content.isNotEmpty()) { Icon(Icons.Outlined.FastForward, stringResource(R.string.speech_forward), Modifier.size(32.dp)) }
            IconButton(onClick = { chapterSkip(1) }, enabled = canNextChapter) { Icon(Icons.Outlined.SkipNext, stringResource(R.string.next_chapter)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerOption(stringResource(R.string.speech_rate, speech.rate), Icons.Outlined.Speed, Modifier.weight(1f)) { option = "rate" }
            PlayerOption(stringResource(R.string.speech_voice), Icons.Outlined.GraphicEq, Modifier.weight(1f)) { option = "voice" }
            PlayerOption(if (minutes == 0) stringResource(R.string.speech_timer) else stringResource(R.string.speech_minutes, minutes), Icons.Outlined.Timer, Modifier.weight(1f)) { option = "timer" }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (option != null) AlertDialog(onDismissRequest = { option = null },
        title = { Text(stringResource(when (option) { "rate" -> R.string.speech_rate_label; "voice" -> R.string.speech_voice; else -> R.string.speech_timer })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (option) {
                    "rate" -> {
                        Text(stringResource(R.string.speech_rate, speech.rate))
                        Slider(speech.rate, rate, valueRange = 0.5f..2f, steps = 5)
                    }
                    "voice" -> {
                        if (speech.voices.isEmpty()) Text(stringResource(R.string.speech_start_hint))
                        speech.voices.forEach { item ->
                            SpeechChoice(item.language + "\n" + item.name, item.name == speech.voice) {
                                voice(item.name); option = null
                            }
                        }
                    }
                    else -> listOf(0, 5, 15, 30, 60).forEach { value ->
                        SpeechChoice(if (value == 0) stringResource(R.string.speech_timer_off) else stringResource(R.string.speech_minutes, value), value == selectedTimer) {
                            timer(value); option = null
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { option = null }) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
private fun PlayerOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, action: () -> Unit) {
    Surface(onClick = action, modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpeechChoice(label: String, selected: Boolean, choose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        .selectable(selected = selected, role = Role.RadioButton, onClick = choose),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            RadioButton(selected = selected, onClick = null)
        }
    }
}
