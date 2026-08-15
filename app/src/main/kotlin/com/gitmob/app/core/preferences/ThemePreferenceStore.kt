package com.gitmob.app.core.preferences

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitmob.app.ui.theme.ThemeMode
import com.gitmob.app.ui.theme.ThemePreference
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore("theme_preference_store")

/** 主题偏好用普通明文 DataStore 即可，不是敏感数据，不需要走 TokenCipher 那套加密。 */
@Singleton
class ThemePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modeKey = stringPreferencesKey("theme_mode")
    private val useDynamicColorKey = stringPreferencesKey("use_dynamic_color")
    private val customSeedColorKey = intPreferencesKey("custom_seed_color_argb")
    private val useAmoledKey = booleanPreferencesKey("use_amoled")
    private val paletteStyleKey = stringPreferencesKey("palette_style")
    private val colorSpecKey = stringPreferencesKey("color_spec")
    private val enablePredictiveBackKey = booleanPreferencesKey("enable_predictive_back")
    private val pageScaleKey = floatPreferencesKey("page_scale")

    val preference: Flow<ThemePreference> = context.themeDataStore.data.map { prefs ->
        ThemePreference(
            isLoaded = true, // 能走到 map 就说明 DataStore 已吐出真实值（区别于数据类的构造默认值）
            mode = prefs[modeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            useDynamicColor = prefs[useDynamicColorKey]?.toBooleanStrictOrNull() ?: false,
            customSeedColor = prefs[customSeedColorKey]?.let { Color(it) },
            useAmoled = prefs[useAmoledKey] ?: false,
            paletteStyle = prefs[paletteStyleKey]
                ?.let { stored -> PaletteStyle.entries.firstOrNull { it.name == stored } }
                ?: PaletteStyle.TonalSpot,
            colorSpec = prefs[colorSpecKey]
                ?.let { stored -> ColorSpec.SpecVersion.entries.firstOrNull { it.name == stored } }
                ?: ColorSpec.SpecVersion.SPEC_2021,
            enablePredictiveBack = prefs[enablePredictiveBackKey] ?: true,
            pageScale = (prefs[pageScaleKey] ?: 1f).coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE),
        )
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[modeKey] = mode.name }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        context.themeDataStore.edit { it[useDynamicColorKey] = enabled.toString() }
    }

    suspend fun setCustomSeedColor(color: Color?) {
        context.themeDataStore.edit { prefs ->
            if (color == null) prefs.remove(customSeedColorKey)
            else prefs[customSeedColorKey] = color.toArgb()
        }
    }

    suspend fun setUseAmoled(enabled: Boolean) {
        context.themeDataStore.edit { it[useAmoledKey] = enabled }
    }

    suspend fun setPaletteStyle(style: PaletteStyle) {
        context.themeDataStore.edit { it[paletteStyleKey] = style.name }
    }

    suspend fun setColorSpec(spec: ColorSpec.SpecVersion) {
        context.themeDataStore.edit { it[colorSpecKey] = spec.name }
    }

    suspend fun setEnablePredictiveBack(enabled: Boolean) {
        context.themeDataStore.edit { it[enablePredictiveBackKey] = enabled }
    }

    suspend fun setPageScale(scale: Float) {
        context.themeDataStore.edit { it[pageScaleKey] = scale.coerceIn(MIN_PAGE_SCALE, MAX_PAGE_SCALE) }
    }

    /** 选择品牌/自定义种子色时，同步关闭壁纸取色，避免暴露中间状态。 */
    suspend fun selectCustomSeedColor(color: Color?) {
        context.themeDataStore.edit { prefs ->
            prefs[useDynamicColorKey] = false.toString()
            if (color == null) prefs.remove(customSeedColorKey)
            else prefs[customSeedColorKey] = color.toArgb()
        }
    }

    private companion object {
        const val MIN_PAGE_SCALE = 0.8f
        const val MAX_PAGE_SCALE = 1.1f
    }
}
