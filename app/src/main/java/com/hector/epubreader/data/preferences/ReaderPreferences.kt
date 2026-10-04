package com.hector.epubreader.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.readerDataStore by preferencesDataStore("reader_preferences")

enum class ReaderPalette(val background: String, val text: String, val link: String, val selection: String, val dark: Boolean = false) {
    WHITE("#FFFFFF", "#202124", "#235E9B", "#CBDFF8"),
    CREAM("#FAF7ED", "#302E29", "#486438", "#DCE6C1"),
    SEPIA("#EEE0C5", "#443728", "#795129", "#D5BC8B"),
    GRAY("#E1E3E3", "#242729", "#225E83", "#B6CEDC"),
    DARK("#202322", "#E0E4DF", "#B2D7BC", "#45594A", true),
    OLED("#000000", "#DDDFDD", "#B2D7BC", "#344A3A", true),
    PAPER("#F2EFE6", "#39362F", "#596444", "#D8DFC8"),
    SAND("#E9DDCA", "#443B30", "#755634", "#D6C1A1"),
    SAGE("#E3EADD", "#303B2E", "#436445", "#C6D5BC"),
    MIST("#E4E9EC", "#323D44", "#42657C", "#C5D6DF"),
    DUSK("#302727", "#E4D6C8", "#D9B99E", "#5A4540", true),
    INK("#222A33", "#D5DCE2", "#A7C5DC", "#3C5063", true)
}

data class ReaderPreferences(
    val appTheme: String = "system",
    val dynamicColors: Boolean = false,
    val interfaceColor: String = "green",
    val fontSize: Float = 20f,
    val font: String = "serif",
    val lineHeight: Float = 1.6f,
    val paragraphSpacing: Float = 0.8f,
    val horizontalMargin: Int = 24,
    val verticalMargin: Int = 24,
    val justified: Boolean = false,
    val palette: ReaderPalette = ReaderPalette.CREAM,
    val readingMode: String = "scroll",
    val volumeNavigation: Boolean = false,
    val sort: String = "recent",
    val ascending: Boolean = false,
    val listView: Boolean = false
) {
    fun validated() = copy(
        appTheme = appTheme.takeIf { it in setOf("system", "light", "dark") } ?: "system",
        interfaceColor = interfaceColor.takeIf { it in setOf("green", "blue", "purple", "coral", "amber") } ?: "green",
        fontSize = fontSize.takeIf { it.isFinite() }?.coerceIn(12f, 36f) ?: 20f,
        font = font.takeIf { it in setOf("serif", "sans-serif", "monospace", "publisher") } ?: "serif",
        lineHeight = lineHeight.takeIf { it.isFinite() }?.coerceIn(1.2f, 2.2f) ?: 1.6f,
        paragraphSpacing = paragraphSpacing.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 0.8f,
        horizontalMargin = horizontalMargin.coerceIn(8, 64), verticalMargin = verticalMargin.coerceIn(8, 64),
        sort = sort.takeIf { it in setOf("recent", "title", "author", "added", "progress") } ?: "recent",
        readingMode = readingMode.takeIf { it in setOf("scroll", "pages", "paragraphs") } ?: "scroll"
    )
}

class PreferencesRepository(context: Context) {
    private val store = context.readerDataStore
    private fun decode(p: Preferences): ReaderPreferences =
        ReaderPreferences(
            appTheme = p[stringPreferencesKey("appTheme")] ?: "system",
            dynamicColors = p[booleanPreferencesKey("dynamicColors")] ?: false,
            interfaceColor = p[stringPreferencesKey("interfaceColor")] ?: "green",
            fontSize = p[floatPreferencesKey("fontSize")] ?: 20f,
            font = p[stringPreferencesKey("font")] ?: "serif",
            lineHeight = p[floatPreferencesKey("lineHeight")] ?: 1.6f,
            paragraphSpacing = p[floatPreferencesKey("paragraphSpacing")] ?: 0.8f,
            horizontalMargin = p[intPreferencesKey("horizontalMargin")] ?: 24,
            verticalMargin = p[intPreferencesKey("verticalMargin")] ?: 24,
            justified = p[booleanPreferencesKey("justified")] ?: false,
            palette = ReaderPalette.entries.firstOrNull { it.name == p[stringPreferencesKey("palette")] } ?: ReaderPalette.CREAM,
            readingMode = p[stringPreferencesKey("readingMode")] ?: "scroll",
            volumeNavigation = p[booleanPreferencesKey("volumeNavigation")] ?: false,
            sort = p[stringPreferencesKey("sort")] ?: "recent",
            ascending = p[booleanPreferencesKey("ascending")] ?: false,
            listView = p[booleanPreferencesKey("listView")] ?: false
        ).validated()
    val preferences = store.data.map(::decode)

    suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        store.edit {
            val v = transform(decode(it)).validated()
            it[stringPreferencesKey("appTheme")] = v.appTheme
            it[booleanPreferencesKey("dynamicColors")] = v.dynamicColors
            it[stringPreferencesKey("interfaceColor")] = v.interfaceColor
            it[floatPreferencesKey("fontSize")] = v.fontSize
            it[stringPreferencesKey("font")] = v.font
            it[floatPreferencesKey("lineHeight")] = v.lineHeight
            it[floatPreferencesKey("paragraphSpacing")] = v.paragraphSpacing
            it[intPreferencesKey("horizontalMargin")] = v.horizontalMargin
            it[intPreferencesKey("verticalMargin")] = v.verticalMargin
            it[booleanPreferencesKey("justified")] = v.justified
            it[stringPreferencesKey("palette")] = v.palette.name
            it[stringPreferencesKey("readingMode")] = v.readingMode
            it[booleanPreferencesKey("volumeNavigation")] = v.volumeNavigation
            it[stringPreferencesKey("sort")] = v.sort
            it[booleanPreferencesKey("ascending")] = v.ascending
            it[booleanPreferencesKey("listView")] = v.listView
        }
    }
}
