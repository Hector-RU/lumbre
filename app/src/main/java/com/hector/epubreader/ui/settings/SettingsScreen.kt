package com.hector.epubreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hector.epubreader.BuildConfig
import com.hector.epubreader.R
import com.hector.epubreader.data.preferences.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(preferences: ReaderPreferences, change: ((ReaderPreferences) -> ReaderPreferences) -> Unit, clearHistory: () -> Unit) {
    var confirmHistory by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val licenseText by produceState(initialValue = "", licenses) {
        if (licenses) value = withContext(Dispatchers.IO) {
            listOf("licenses/NOTICE.txt", "licenses/Apache-2.0.txt", "licenses/jsoup-MIT.txt").joinToString("\n\n") { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_settings)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } },
            singleLine = true, shape = RoundedCornerShape(32.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        if (matchesSettings(query, R.string.appearance, R.string.app_theme, R.string.dynamic_colors, R.string.interface_color)) {
            SettingsGroup(stringResource(R.string.appearance), Icons.Outlined.Palette) {
                ChoicePreference(stringResource(R.string.app_theme), preferences.appTheme, listOf("system" to R.string.system_theme, "light" to R.string.light_theme, "dark" to R.string.dark_theme)) { value -> change { it.copy(appTheme = value) } }
                TogglePreference(stringResource(R.string.dynamic_colors), preferences.dynamicColors, stringResource(R.string.dynamic_description)) { value -> change { it.copy(dynamicColors = value) } }
                ChoicePreference(stringResource(R.string.interface_color), preferences.interfaceColor, listOf("green" to R.string.color_green, "blue" to R.string.color_blue, "purple" to R.string.color_purple, "coral" to R.string.color_coral, "amber" to R.string.color_amber)) { value -> change { it.copy(interfaceColor = value, dynamicColors = false) } }
            }
        }
        ReaderSettings(preferences, change, query = query)
        if (matchesSettings(query, R.string.language_settings, R.string.app_language)) {
            SettingsGroup(stringResource(R.string.language_settings), Icons.Outlined.Language) {
                ChoicePreference(
                    stringResource(R.string.app_language),
                    AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-').ifEmpty { "es" },
                    listOf("es" to R.string.spanish, "en" to R.string.english)
                ) { language -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language)) }
            }
        }
        if (matchesSettings(query, R.string.library, R.string.sort, R.string.ascending, R.string.list_view)) {
            SettingsGroup(stringResource(R.string.library), Icons.Outlined.LocalLibrary) {
                ChoicePreference(stringResource(R.string.sort), preferences.sort, sortChoices) { value -> change { it.copy(sort = value) } }
                TogglePreference(stringResource(R.string.ascending), preferences.ascending) { value -> change { it.copy(ascending = value) } }
                TogglePreference(stringResource(R.string.list_view), preferences.listView) { value -> change { it.copy(listView = value) } }
            }
        }
        if (matchesSettings(query, R.string.data, R.string.clear_history)) {
            SettingsGroup(stringResource(R.string.data), Icons.Outlined.History) {
                PreferenceRow(stringResource(R.string.clear_history), onClick = { confirmHistory = true })
            }
        }
        if (matchesSettings(query, R.string.about, R.string.licenses)) {
            SettingsGroup(stringResource(R.string.about), Icons.Outlined.Info) {
                PreferenceRow(stringResource(R.string.about_description, BuildConfig.VERSION_NAME))
                PreferenceRow(stringResource(R.string.licenses), onClick = { licenses = true })
            }
        }
        if (query.isNotBlank() && !matchesSettings(query, R.string.appearance, R.string.app_theme, R.string.dynamic_colors, R.string.interface_color,
                R.string.reading, R.string.reader_theme, R.string.typography, R.string.font, R.string.font_size, R.string.page_layout,
                R.string.line_height, R.string.paragraph_spacing, R.string.horizontal_margin, R.string.vertical_margin, R.string.justified,
                R.string.navigation_settings, R.string.reading_mode, R.string.volume_navigation, R.string.language_settings, R.string.app_language,
                R.string.library, R.string.sort, R.string.ascending, R.string.list_view, R.string.data, R.string.clear_history, R.string.about, R.string.licenses)) {
            Text(stringResource(R.string.no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmHistory) AlertDialog(onDismissRequest = { confirmHistory = false }, title = { Text(stringResource(R.string.clear_history)) }, text = { Text(stringResource(R.string.clear_history_question)) }, confirmButton = { TextButton(onClick = { clearHistory(); confirmHistory = false }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text(stringResource(R.string.cancel)) } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.licenses)) }, text = {
        Text(licenseText + "\n\n" + stringResource(R.string.license_notice), modifier = Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
}

val sortChoices = listOf("recent" to R.string.sort_recent, "title" to R.string.sort_title, "author" to R.string.sort_author, "added" to R.string.sort_added, "progress" to R.string.sort_progress)

@Composable
fun ReaderSettings(p: ReaderPreferences, change: ((ReaderPreferences) -> ReaderPreferences) -> Unit, compact: Boolean = false, query: String = "") {
    if (matchesSettings(query, R.string.reading, R.string.reader_theme)) {
        SettingsGroup(stringResource(R.string.reader_theme), Icons.Outlined.Palette) {
            PalettePicker(p.palette) { value -> change { it.copy(palette = value) } }
        }
    }
    if (matchesSettings(query, R.string.reading, R.string.typography, R.string.font, R.string.font_size)) {
        SettingsGroup(stringResource(R.string.typography), Icons.Outlined.TextFields) {
            Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = Color(p.palette.background.toColorInt())) {
                Text(stringResource(R.string.reading_preview), Modifier.padding(20.dp), color = Color(p.palette.text.toColorInt()),
                    fontSize = p.fontSize.sp, lineHeight = (p.fontSize * p.lineHeight).sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    fontFamily = when (p.font) { "sans-serif" -> FontFamily.SansSerif; "monospace" -> FontFamily.Monospace; else -> FontFamily.Serif })
            }
            SliderPreference(stringResource(R.string.font_size, p.fontSize.toInt()), p.fontSize, 12f..36f) { value -> change { it.copy(fontSize = value) } }
            ChoicePreference(stringResource(R.string.font), p.font, listOf("serif" to R.string.font_serif, "sans-serif" to R.string.font_sans, "monospace" to R.string.font_mono, "publisher" to R.string.font_publisher)) { value -> change { it.copy(font = value) } }
        }
    }
    if (matchesSettings(query, R.string.reading, R.string.navigation_settings, R.string.reading_mode, R.string.volume_navigation)) {
        SettingsGroup(stringResource(R.string.navigation_settings), Icons.Outlined.Swipe) {
            ChoicePreference(stringResource(R.string.reading_mode), p.readingMode, listOf("scroll" to R.string.scroll_mode, "pages" to R.string.page_mode, "paragraphs" to R.string.paragraph_mode)) { value -> change { it.copy(readingMode = value) } }
            TogglePreference(stringResource(R.string.volume_navigation), p.volumeNavigation, stringResource(R.string.volume_navigation_description)) { value -> change { it.copy(volumeNavigation = value) } }
        }
    }
    if (!compact && matchesSettings(query, R.string.reading, R.string.page_layout, R.string.line_height, R.string.paragraph_spacing, R.string.horizontal_margin, R.string.vertical_margin, R.string.justified)) {
        SettingsGroup(stringResource(R.string.page_layout), Icons.AutoMirrored.Outlined.FormatAlignLeft) {
            SliderPreference(stringResource(R.string.line_height, p.lineHeight), p.lineHeight, 1.2f..2.2f) { value -> change { it.copy(lineHeight = value) } }
            SliderPreference(stringResource(R.string.paragraph_spacing, p.paragraphSpacing), p.paragraphSpacing, 0f..2f) { value -> change { it.copy(paragraphSpacing = value) } }
            SliderPreference(stringResource(R.string.horizontal_margin, p.horizontalMargin), p.horizontalMargin.toFloat(), 8f..64f) { value -> change { it.copy(horizontalMargin = value.toInt()) } }
            SliderPreference(stringResource(R.string.vertical_margin, p.verticalMargin), p.verticalMargin.toFloat(), 8f..64f) { value -> change { it.copy(verticalMargin = value.toInt()) } }
            TogglePreference(stringResource(R.string.justified), p.justified) { value -> change { it.copy(justified = value) } }
        }
    }
}

@Composable
private fun matchesSettings(query: String, vararg labels: Int): Boolean =
    query.isBlank() || labels.any { stringResource(it).contains(query.trim(), ignoreCase = true) }

@Composable
private fun SettingsGroup(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(28.dp), color = preferenceColor()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun preferenceColor() = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant, 0.45f)

@Composable
private fun PreferenceRow(title: String, description: String? = null, onClick: (() -> Unit)? = null) {
    val divider = MaterialTheme.colorScheme.surface
    ListItem(headlineContent = { Text(title) }, supportingContent = description?.let { { Text(it) } },
        trailingContent = onClick?.let { { Icon(Icons.Outlined.ChevronRight, null) } },
        colors = ListItemDefaults.colors(containerColor = preferenceColor()),
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 4.dp)
            .drawBehind { drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) })
}

@Composable
private fun PalettePicker(selected: ReaderPalette, onSelect: (ReaderPalette) -> Unit) {
    val labels = listOf(R.string.palette_white, R.string.palette_cream, R.string.palette_sepia, R.string.palette_gray,
        R.string.palette_dark, R.string.palette_oled, R.string.palette_paper, R.string.palette_sand, R.string.palette_sage,
        R.string.palette_mist, R.string.palette_dusk, R.string.palette_ink)
    Column(Modifier.padding(12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderPalette.entries.withIndex().toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (index, palette) ->
                    val active = selected == palette
                    Surface(Modifier.weight(1f).selectable(active, role = Role.RadioButton, onClick = { onSelect(palette) }),
                        shape = RoundedCornerShape(18.dp), color = Color(palette.background.toColorInt()),
                        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else Color(palette.text.toColorInt()).copy(alpha = 0.15f))) {
                        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Aa", fontFamily = FontFamily.Serif, fontSize = 25.sp, color = Color(palette.text.toColorInt()))
                                if (active) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = Color(palette.text.toColorInt()))
                            }
                            Text(stringResource(labels[index]), style = MaterialTheme.typography.labelMedium, color = Color(palette.text.toColorInt()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TogglePreference(title: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    val divider = MaterialTheme.colorScheme.surface
    ListItem(headlineContent = { Text(title) }, supportingContent = description?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = preferenceColor()),
        trailingContent = { Switch(checked, onCheckedChange = null,
            thumbContent = { Icon(if (checked) Icons.Outlined.Check else Icons.Outlined.Close, null, Modifier.size(16.dp)) }) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp)
            .drawBehind { drawLine(divider, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) })
}

@Composable
fun SliderPreference(title: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { change(local) }, valueRange = range, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun ChoicePreference(title: String, selected: String, options: List<Pair<String, Int>>, onSelect: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PreferenceRow(title, options.firstOrNull { it.first == selected }?.let { stringResource(it.second) }, onClick = { show = true })
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(value); show = false }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected == value, onClick = null)
                    Text(stringResource(label), Modifier.padding(12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.close)) } })
}
