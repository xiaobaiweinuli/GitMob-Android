package com.gitmob.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.gitmob.android.auth.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary          = Coral,
    onPrimary        = Color.White,
    primaryContainer = CoralDim,
    secondary        = BlueColor,
    background       = darkGmColors.bgDeep,
    surface          = darkGmColors.bgCard,
    surfaceVariant   = darkGmColors.bgItem,
    onBackground     = darkGmColors.textPrimary,
    onSurface        = darkGmColors.textPrimary,
    onSurfaceVariant = darkGmColors.textSecondary,
    outline          = darkGmColors.border,
    outlineVariant   = darkGmColors.border2,
    error            = RedColor,
)

private val LightColorScheme = lightColorScheme(
    primary          = Coral,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFFFE5DF),
    secondary        = BlueColor,
    background       = lightGmColors.bgDeep,
    surface          = lightGmColors.bgCard,
    surfaceVariant   = lightGmColors.bgItem,
    onBackground     = lightGmColors.textPrimary,
    onSurface        = lightGmColors.textPrimary,
    onSurfaceVariant = lightGmColors.textSecondary,
    outline          = lightGmColors.border,
    outlineVariant   = lightGmColors.border2,
    error            = Color(0xFFD32F2F),
)

@Composable
fun GitMobTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val gmColors = if (useDark) darkGmColors else lightGmColors

    CompositionLocalProvider(LocalGmColors provides gmColors) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
            typography  = Typography,
            content     = content,
        )
    }
}
