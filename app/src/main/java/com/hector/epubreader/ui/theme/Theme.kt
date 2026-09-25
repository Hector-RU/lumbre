package com.hector.epubreader.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

@Composable
fun EpubTheme(mode: String = "system", dynamic: Boolean = false, interfaceColor: String = "green", content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val context = LocalContext.current
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val palette = when (interfaceColor) {
        "blue" -> Accent(Color(0xFF285D91), Color(0xFFD7EAFB), Color(0xFFF8FAFD), Color(0xFFAFD1F4), Color(0xFF29445E), Color(0xFF161D25))
        "purple" -> Accent(Color(0xFF6B4D91), Color(0xFFEDE2F8), Color(0xFFFBF9FD), Color(0xFFD5B9FA), Color(0xFF463658), Color(0xFF211B29))
        "coral" -> Accent(Color(0xFFA24E45), Color(0xFFF9E3DE), Color(0xFFFDF9F7), Color(0xFFF4B4A9), Color(0xFF5C3632), Color(0xFF271C1B))
        "amber" -> Accent(Color(0xFF865D20), Color(0xFFF7E9C9), Color(0xFFFDFBF5), Color(0xFFEACB8A), Color(0xFF514124), Color(0xFF231F17))
        else -> Accent(Color(0xFF45634A), Color(0xFFDCEBDD), Color(0xFFFAFBF7), Color(0xFFB5CCB4), Color(0xFF324838), Color(0xFF171D19))
    }
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = palette.darkPrimary, onPrimary = palette.darkSurface,
            primaryContainer = palette.darkContainer, onPrimaryContainer = palette.darkPrimary,
            secondary = palette.darkPrimary, secondaryContainer = palette.darkContainer,
            background = palette.darkSurface, surface = palette.darkSurface,
            onBackground = Color(0xFFE8EAE7), onSurface = Color(0xFFE8EAE7),
            surfaceVariant = palette.darkContainer, onSurfaceVariant = Color(0xFFD2D8D1)
        )
        else -> lightColorScheme(
            primary = palette.lightPrimary, onPrimary = Color.White,
            primaryContainer = palette.lightContainer, onPrimaryContainer = palette.lightPrimary,
            secondary = palette.lightPrimary, secondaryContainer = palette.lightContainer,
            background = palette.lightSurface, surface = palette.lightSurface,
            onBackground = Color(0xFF202520), onSurface = Color(0xFF202520),
            surfaceVariant = palette.lightContainer, onSurfaceVariant = Color(0xFF485148)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private data class Accent(
    val lightPrimary: Color,
    val lightContainer: Color,
    val lightSurface: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
    val darkSurface: Color
)
