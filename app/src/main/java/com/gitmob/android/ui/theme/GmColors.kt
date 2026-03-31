package com.gitmob.android.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** 动态主题色系——随浅/深模式切换，通过 LocalGmColors 提供给所有屏幕 */
@Stable
data class GmColors(
    val bgDeep: Color,
    val bgCard: Color,
    val bgItem: Color,
    val bgActive: Color,
    val border: Color,
    val border2: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val isDark: Boolean,
)

val darkGmColors = GmColors(
    bgDeep      = Color(0xFF0F1117),
    bgCard      = Color(0xFF161B25),
    bgItem      = Color(0xFF1E2535),
    bgActive    = Color(0xFF252D40),
    border      = Color(0xFF2A3347),
    border2     = Color(0xFF3A4560),
    textPrimary   = Color(0xFFE8EAF0),
    textSecondary = Color(0xFF9BA3BA),
    textTertiary  = Color(0xFF5C6580),
    isDark        = true,
)

val lightGmColors = GmColors(
    bgDeep      = Color(0xFFF4F6FC),
    bgCard      = Color(0xFFFFFFFF),
    bgItem      = Color(0xFFF0F2F8),
    bgActive    = Color(0xFFE6EAF5),
    border      = Color(0xFFDDE1EC),
    border2     = Color(0xFFC8CDDE),
    textPrimary   = Color(0xFF0F1117),
    textSecondary = Color(0xFF5C6475),
    textTertiary  = Color(0xFF9BA3BA),
    isDark        = false,
)

val LocalGmColors = compositionLocalOf { darkGmColors }
