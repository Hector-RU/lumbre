package com.hector.epubreader.ui

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

private data class InsetsPixels(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/** Older Android versions can report zero stable insets after entering immersive mode. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun stableReaderInsets(): WindowInsets {
    val actual = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    if (Build.VERSION.SDK_INT >= 30) return actual
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    var retained by remember(orientation, density.density) { mutableStateOf(InsetsPixels()) }
    val next = InsetsPixels(
        maxOf(retained.left, actual.getLeft(density, androidx.compose.ui.platform.LocalLayoutDirection.current)),
        maxOf(retained.top, actual.getTop(density)),
        maxOf(retained.right, actual.getRight(density, androidx.compose.ui.platform.LocalLayoutDirection.current)),
        maxOf(retained.bottom, actual.getBottom(density)))
    SideEffect { retained = next }
    return WindowInsets(next.left, next.top, next.right, next.bottom)
}
