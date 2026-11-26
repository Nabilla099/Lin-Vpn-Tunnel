package com.linvpn.app.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.linvpn.app.data.AppSettings

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1A0063),
    secondary = Color(0xFF006A7E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB7EBFA),
    onSecondaryContainer = Color(0xFF001F27),
    tertiary = Color(0xFF8B4A6E),
    tertiaryContainer = Color(0xFFFFD8EA),
    onTertiaryContainer = Color(0xFF390A27),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE5E0EC),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FB),
    surfaceContainer = Color(0xFFEFECF6),
    surfaceContainerHigh = Color(0xFFE9E6F0),
    surfaceContainerHighest = Color(0xFFE3E0EA),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFC9C4D0),
    error = Color(0xFFBA1A1A)
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFCABEFF),
    onPrimary = Color(0xFF2D0F9B),
    primaryContainer = Color(0xFF4325C0),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF55D6F2),
    onSecondary = Color(0xFF003642),
    secondaryContainer = Color(0xFF004E5E),
    onSecondaryContainer = Color(0xFFB7EBFA),
    tertiary = Color(0xFFFFAFD8),
    tertiaryContainer = Color(0xFF6F334F),
    onTertiaryContainer = Color(0xFFFFD8EA),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F),
    error = Color(0xFFFFB4AB)
)

@Composable
fun LinTheme(st: AppSettings, content: @Composable () -> Unit) {
    val dark = when (st.theme) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = if (st.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
