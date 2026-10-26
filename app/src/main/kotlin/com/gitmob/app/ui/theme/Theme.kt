package com.gitmob.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
    val isDark = preference.isDarkTheme(isSystemInDarkTheme())

    val seedColor = resolveSeedColor(preference, isDark)
    val colorScheme = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        isAmoled = isDark && preference.useAmoled,
        style = preference.paletteStyle,
        specVersion = preference.colorSpec,
    )
    val systemDensity = LocalDensity.current
    val pageScale = preference.pageScale.coerceIn(0.8f, 1.1f)
    val scaledDensity = remember(systemDensity.density, systemDensity.fontScale, pageScale) {
        Density(
            density = systemDensity.density * pageScale,
            fontScale = systemDensity.fontScale,
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
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
