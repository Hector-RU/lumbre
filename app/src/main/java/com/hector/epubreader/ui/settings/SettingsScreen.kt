package com.hector.epubreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    val context = LocalContext.current
    val licenseText by produceState(initialValue = "", licenses) {
        if (licenses) value = withContext(Dispatchers.IO) {
            listOf("licenses/NOTICE.txt", "licenses/Apache-2.0.txt", "licenses/jsoup-MIT.txt").joinToString("\n\n") { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section(stringResource(R.string.language_settings))
        ChoicePreference(
            stringResource(R.string.app_language),
            AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').substringBefore('-').ifEmpty { "es" },
            listOf("es" to R.string.spanish, "en" to R.string.english)
        ) { language -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language)) }
        Section(stringResource(R.string.appearance))
        ChoicePreference(stringResource(R.string.app_theme), preferences.appTheme, listOf("system" to R.string.system_theme, "light" to R.string.light_theme, "dark" to R.string.dark_theme)) { value -> change { it.copy(appTheme = value) } }
        TogglePreference(stringResource(R.string.dynamic_colors), preferences.dynamicColors, stringResource(R.string.dynamic_description)) { value -> change { it.copy(dynamicColors = value) } }
        ChoicePreference(stringResource(R.string.interface_color), preferences.interfaceColor, listOf("green" to R.string.color_green, "blue" to R.string.color_blue, "purple" to R.string.color_purple, "coral" to R.string.color_coral, "amber" to R.string.color_amber)) { value -> change { it.copy(interfaceColor = value, dynamicColors = false) } }
        Section(stringResource(R.string.reading))
        ReaderSettings(preferences, change)
        Section(stringResource(R.string.library))
        ChoicePreference(stringResource(R.string.sort), preferences.sort, sortChoices) { value -> change { it.copy(sort = value) } }
        TogglePreference(stringResource(R.string.ascending), preferences.ascending) { value -> change { it.copy(ascending = value) } }
        TogglePreference(stringResource(R.string.list_view), preferences.listView) { value -> change { it.copy(listView = value) } }
        Section(stringResource(R.string.data))
        ListItem(headlineContent = { Text(stringResource(R.string.clear_history)) }, modifier = Modifier.clickable { confirmHistory = true })
        Section(stringResource(R.string.about))
        ListItem(headlineContent = { Text(stringResource(R.string.about_description, BuildConfig.VERSION_NAME)) })
        ListItem(headlineContent = { Text(stringResource(R.string.licenses)) }, modifier = Modifier.clickable { licenses = true })
    }
    if (confirmHistory) AlertDialog(onDismissRequest = { confirmHistory = false }, title = { Text(stringResource(R.string.clear_history)) }, text = { Text(stringResource(R.string.clear_history_question)) }, confirmButton = { TextButton(onClick = { clearHistory(); confirmHistory = false }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmHistory = false }) { Text(stringResource(R.string.cancel)) } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.licenses)) }, text = {
        Text(licenseText + "\n\n" + stringResource(R.string.license_notice), modifier = Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
}

val sortChoices = listOf("recent" to R.string.sort_recent, "title" to R.string.sort_title, "author" to R.string.sort_author, "added" to R.string.sort_added, "progress" to R.string.sort_progress)

@Composable
fun ReaderSettings(p: ReaderPreferences, change: ((ReaderPreferences) -> ReaderPreferences) -> Unit, compact: Boolean = false) {
    SliderPreference(stringResource(R.string.font_size, p.fontSize.toInt()), p.fontSize, 12f..36f) { value -> change { it.copy(fontSize = value) } }
    ChoicePreference(stringResource(R.string.font), p.font, listOf("serif" to R.string.font_serif, "sans-serif" to R.string.font_sans, "monospace" to R.string.font_mono, "publisher" to R.string.font_publisher)) { value -> change { it.copy(font = value) } }
    ChoicePreference(stringResource(R.string.reading_mode), p.readingMode, listOf("scroll" to R.string.scroll_mode, "pages" to R.string.page_mode, "paragraphs" to R.string.paragraph_mode)) { value -> change { it.copy(readingMode = value) } }
    TogglePreference(stringResource(R.string.volume_navigation), p.volumeNavigation, stringResource(R.string.volume_navigation_description)) { value -> change { it.copy(volumeNavigation = value) } }
    if (!compact) {
        SliderPreference(stringResource(R.string.line_height, p.lineHeight), p.lineHeight, 1.2f..2.2f) { value -> change { it.copy(lineHeight = value) } }
        SliderPreference(stringResource(R.string.paragraph_spacing, p.paragraphSpacing), p.paragraphSpacing, 0f..2f) { value -> change { it.copy(paragraphSpacing = value) } }
        SliderPreference(stringResource(R.string.horizontal_margin, p.horizontalMargin), p.horizontalMargin.toFloat(), 8f..64f) { value -> change { it.copy(horizontalMargin = value.toInt()) } }
        SliderPreference(stringResource(R.string.vertical_margin, p.verticalMargin), p.verticalMargin.toFloat(), 8f..64f) { value -> change { it.copy(verticalMargin = value.toInt()) } }
        TogglePreference(stringResource(R.string.justified), p.justified) { value -> change { it.copy(justified = value) } }
    }
}

@Composable
fun Section(title: String) { Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) }

@Composable
fun TogglePreference(title: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = description?.let { { Text(it) } }, trailingContent = { Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title }) }, modifier = Modifier.clickable { onChange(!checked) })
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
    ListItem(headlineContent = { Text(title) }, supportingContent = { options.firstOrNull { it.first == selected }?.let { Text(stringResource(it.second)) } }, modifier = Modifier.clickable { show = true })
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
