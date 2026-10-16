package com.hector.epubreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.hector.epubreader.R
import com.hector.epubreader.data.local.AnnotationEntity
import com.hector.epubreader.data.preferences.ReaderPreferences
import com.hector.epubreader.epub.AnnotationColor
import com.hector.epubreader.epub.AnnotationStyle

private val AnnotationStyle.label: Int get() = when (this) {
    AnnotationStyle.SHADE -> R.string.annotation_shade
    AnnotationStyle.UNDERLINE -> R.string.annotation_underline
    AnnotationStyle.BOTH -> R.string.annotation_both
}
private val AnnotationColor.label: Int get() = when (this) {
    AnnotationColor.RED -> R.string.annotation_red
    AnnotationColor.YELLOW -> R.string.annotation_yellow
    AnnotationColor.GREEN -> R.string.annotation_green
    AnnotationColor.BLUE -> R.string.annotation_blue
    AnnotationColor.PURPLE -> R.string.annotation_purple
}

@Composable
private fun PassagePreview(annotation: AnnotationEntity, preferences: ReaderPreferences, maxLines: Int, overflow: (Boolean) -> Unit = {}) {
    val style = AnnotationStyle.fromId(annotation.style)
    val color = AnnotationColor.fromId(annotation.color)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val lineColor = Color((if (preferences.palette.dark && !style.shaded) color.darkLine else color.line).toColorInt())
    Surface(shape = RoundedCornerShape(16.dp), color = Color(preferences.palette.background.toColorInt())) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(
                background = if (style.shaded) Color(color.background.toColorInt()) else Color.Unspecified,
                color = if (style.shaded) Color(color.ink.toColorInt()) else Color(preferences.palette.text.toColorInt()),
                textDecoration = TextDecoration.None
            )) { append(annotation.quote) }
        }, Modifier.fillMaxWidth().padding(16.dp).drawWithContent {
            drawContent()
            if (style.underlined) layout?.let { text ->
                for (line in 0 until text.lineCount) {
                    val y = (text.getLineBaseline(line) + 2.dp.toPx()).coerceAtMost(text.getLineBottom(line))
                    drawLine(lineColor, Offset(text.getLineLeft(line), y), Offset(text.getLineRight(line), y), strokeWidth = 1.5.dp.toPx())
                }
            }
        }, fontFamily = FontFamily.Serif, onTextLayout = { layout = it; overflow(it.hasVisualOverflow) },
            style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationEditor(annotation: AnnotationEntity, preferences: ReaderPreferences, saving: Boolean, error: Boolean,
    change: (AnnotationEntity) -> Unit, dismiss: () -> Unit, save: () -> Unit) {
    val style = AnnotationStyle.fromId(annotation.style)
    val color = AnnotationColor.fromId(annotation.color)
    ModalBottomSheet(onDismissRequest = { if (!saving) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.annotation_edit_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.annotation_chapter, annotation.chapter + 1),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.annotation_style), style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnnotationStyle.entries.forEach { option ->
                        FilterChip(selected = option == style, onClick = { change(annotation.copy(style = option.id)) }, enabled = !saving,
                            label = { Text(stringResource(option.label)) }, modifier = Modifier.semantics { role = Role.RadioButton },
                            leadingIcon = { Icon(if (option == style) Icons.Outlined.Check else when (option) {
                                AnnotationStyle.SHADE -> Icons.Outlined.FormatColorFill
                                AnnotationStyle.UNDERLINE -> Icons.Outlined.FormatUnderlined
                                AnnotationStyle.BOTH -> Icons.Outlined.Layers
                            }, null, Modifier.size(18.dp)) })
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.annotation_color, stringResource(color.label)), style = MaterialTheme.typography.labelLarge)
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnnotationColor.entries.forEach { option ->
                            val label = stringResource(option.label)
                            val selected = option == color
                            Box(Modifier.size(48.dp).clip(CircleShape)
                                .selectable(selected = selected, enabled = !saving, role = Role.RadioButton, onClick = { change(annotation.copy(color = option.id)) })
                                .semantics { contentDescription = label }
                                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .padding(6.dp).background(Color(option.background.toColorInt()), CircleShape), contentAlignment = Alignment.Center) {
                                if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(22.dp), tint = Color(option.ink.toColorInt()))
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.annotation_preview), style = MaterialTheme.typography.labelLarge)
                    PassagePreview(annotation, preferences, 6)
                }
                OutlinedTextField(annotation.note, { if (it.length <= 10000) change(annotation.copy(note = it)) },
                    label = { Text(stringResource(R.string.annotation_note)) }, modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 6, enabled = !saving, shape = RoundedCornerShape(16.dp))
                if (error) Text(stringResource(R.string.annotation_error), color = MaterialTheme.colorScheme.error)
            }
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(enabled = !saving, onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                Button(enabled = !saving, onClick = save) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_annotation))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnnotationList(annotations: List<AnnotationEntity>, preferences: ReaderPreferences,
    error: Boolean, open: (AnnotationEntity) -> Unit, edit: (AnnotationEntity) -> Unit, delete: (AnnotationEntity) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.annotations), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
        Text(pluralStringResource(R.plurals.annotation_count, annotations.size, annotations.size), Modifier.padding(start = 24.dp, top = 4.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (annotations.isEmpty()) Text(stringResource(R.string.no_annotations), Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        if (error) Text(stringResource(R.string.annotation_error), Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(annotations, key = { it.id }) { annotation ->
                val color = AnnotationColor.fromId(annotation.color)
                var expanded by rememberSaveable(annotation.id) { mutableStateOf(false) }
                var passageOverflows by remember(annotation.quote) { mutableStateOf(false) }
                var noteOverflows by remember(annotation.note) { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(12.dp).background(Color(color.background.toColorInt()), CircleShape).border(1.dp, Color(color.line.toColorInt()), CircleShape))
                            Text(stringResource(R.string.annotation_chapter, annotation.chapter + 1), style = MaterialTheme.typography.labelLarge)
                            Text("· ${stringResource(AnnotationStyle.fromId(annotation.style).label)} · ${stringResource(color.label)}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PassagePreview(annotation, preferences, if (expanded) Int.MAX_VALUE else 4) { if (!expanded) passageOverflows = it }
                        if (annotation.note.isNotBlank()) {
                            Text(stringResource(R.string.annotation_note_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(annotation.note, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) noteOverflows = it.hasVisualOverflow })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { open(annotation) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.annotation_open), Modifier.fillMaxWidth()) }
                            IconButton(onClick = { edit(annotation) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit_annotation)) }
                            IconButton(onClick = { delete(annotation) }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete_annotation)) }
                        }
                        if (expanded || passageOverflows || noteOverflows) {
                            TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.annotation_show_less else R.string.annotation_show_more)) }
                        }
                    }
                }
            }
        }
    }
}
