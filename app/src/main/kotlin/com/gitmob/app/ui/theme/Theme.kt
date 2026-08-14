package com.gitmob.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import com.materialkolor.rememberDynamicColorScheme

/**
 * 设计参照 KernelSU 管理器真实实现（github.com/tiann/KernelSU，
 * manager/.../ui/theme/Theme.kt + ThemeExt.kt），核心思路：
 * 取色只有一条代码路径（统一走 rememberDynamicColorScheme），
 * 不是"系统取色一套 API、品牌色另一套 API"分别处理——
 * 区别只在喂给它的种子色从哪来。
 */
@Composable
fun GitMobTheme(
    preference: ThemePreference = ThemePreference(),
    content: @Composable () -> Unit,
) {
    val isDark = when (preference.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val seedColor = resolveSeedColor(preference, isDark)
    val targetColorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        isAmoled = isDark && preference.useAmoled,
        style = preference.paletteStyle,
        specVersion = preference.colorSpec,
    )
    val colorScheme = animateColorScheme(targetColorScheme)
    val systemDensity = LocalDensity.current
    val scaledDensity = Density(
        density = systemDensity.density * preference.pageScale.coerceIn(0.8f, 1.1f),
        fontScale = systemDensity.fontScale,
    )

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    val animationSpec = tween<Color>(durationMillis = 300)
    @Composable
    fun animate(color: Color, label: String): Color = animateColorAsState(
        targetValue = color,
        animationSpec = animationSpec,
        label = label,
    ).value

    return target.copy(
        primary = animate(target.primary, "theme-primary"),
        onPrimary = animate(target.onPrimary, "theme-on-primary"),
        primaryContainer = animate(target.primaryContainer, "theme-primary-container"),
        onPrimaryContainer = animate(target.onPrimaryContainer, "theme-on-primary-container"),
        inversePrimary = animate(target.inversePrimary, "theme-inverse-primary"),
        secondary = animate(target.secondary, "theme-secondary"),
        onSecondary = animate(target.onSecondary, "theme-on-secondary"),
        secondaryContainer = animate(target.secondaryContainer, "theme-secondary-container"),
        onSecondaryContainer = animate(target.onSecondaryContainer, "theme-on-secondary-container"),
        tertiary = animate(target.tertiary, "theme-tertiary"),
        onTertiary = animate(target.onTertiary, "theme-on-tertiary"),
        tertiaryContainer = animate(target.tertiaryContainer, "theme-tertiary-container"),
        onTertiaryContainer = animate(target.onTertiaryContainer, "theme-on-tertiary-container"),
        background = animate(target.background, "theme-background"),
        onBackground = animate(target.onBackground, "theme-on-background"),
        surface = animate(target.surface, "theme-surface"),
        onSurface = animate(target.onSurface, "theme-on-surface"),
        surfaceVariant = animate(target.surfaceVariant, "theme-surface-variant"),
        onSurfaceVariant = animate(target.onSurfaceVariant, "theme-on-surface-variant"),
        surfaceTint = animate(target.surfaceTint, "theme-surface-tint"),
        inverseSurface = animate(target.inverseSurface, "theme-inverse-surface"),
        inverseOnSurface = animate(target.inverseOnSurface, "theme-inverse-on-surface"),
        error = animate(target.error, "theme-error"),
        onError = animate(target.onError, "theme-on-error"),
        errorContainer = animate(target.errorContainer, "theme-error-container"),
        onErrorContainer = animate(target.onErrorContainer, "theme-on-error-container"),
        outline = animate(target.outline, "theme-outline"),
        outlineVariant = animate(target.outlineVariant, "theme-outline-variant"),
        scrim = animate(target.scrim, "theme-scrim"),
        surfaceBright = animate(target.surfaceBright, "theme-surface-bright"),
        surfaceDim = animate(target.surfaceDim, "theme-surface-dim"),
        surfaceContainer = animate(target.surfaceContainer, "theme-surface-container"),
        surfaceContainerHigh = animate(target.surfaceContainerHigh, "theme-surface-container-high"),
        surfaceContainerHighest = animate(target.surfaceContainerHighest, "theme-surface-container-highest"),
        surfaceContainerLow = animate(target.surfaceContainerLow, "theme-surface-container-low"),
        surfaceContainerLowest = animate(target.surfaceContainerLowest, "theme-surface-container-lowest"),
        primaryFixed = animate(target.primaryFixed, "theme-primary-fixed"),
        primaryFixedDim = animate(target.primaryFixedDim, "theme-primary-fixed-dim"),
        onPrimaryFixed = animate(target.onPrimaryFixed, "theme-on-primary-fixed"),
        onPrimaryFixedVariant = animate(target.onPrimaryFixedVariant, "theme-on-primary-fixed-variant"),
        secondaryFixed = animate(target.secondaryFixed, "theme-secondary-fixed"),
        secondaryFixedDim = animate(target.secondaryFixedDim, "theme-secondary-fixed-dim"),
        onSecondaryFixed = animate(target.onSecondaryFixed, "theme-on-secondary-fixed"),
        onSecondaryFixedVariant = animate(target.onSecondaryFixedVariant, "theme-on-secondary-fixed-variant"),
        tertiaryFixed = animate(target.tertiaryFixed, "theme-tertiary-fixed"),
        tertiaryFixedDim = animate(target.tertiaryFixedDim, "theme-tertiary-fixed-dim"),
        onTertiaryFixed = animate(target.onTertiaryFixed, "theme-on-tertiary-fixed"),
        onTertiaryFixedVariant = animate(target.onTertiaryFixedVariant, "theme-on-tertiary-fixed-variant"),
    )
}

@Composable
private fun resolveSeedColor(preference: ThemePreference, isDark: Boolean): Color {
    if (preference.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // 系统壁纸取色（Material You）只是 Android 原生 API 的产物，这里只借用它的
        // primary 色当"种子色"，后续依然统一交给 MaterialKolor 生成完整 ColorScheme，
        // 不直接用 dynamicLightColorScheme()/dynamicDarkColorScheme() 的完整结果——
        // 否则和品牌色路径就是两套不一致的取色/生成逻辑了。
        val context = LocalContext.current
        return (if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    }
    return preference.customSeedColor ?: Coral
}
