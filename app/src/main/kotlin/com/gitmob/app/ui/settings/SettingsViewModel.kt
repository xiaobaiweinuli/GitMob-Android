package com.gitmob.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.ui.theme.ThemeMode
import com.gitmob.app.ui.theme.ThemePreference
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferenceStore: ThemePreferenceStore,
) : ViewModel() {

    val themePreference: StateFlow<ThemePreference> = themePreferenceStore.preference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePreference())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferenceStore.setMode(mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        viewModelScope.launch { themePreferenceStore.setUseDynamicColor(enabled) }
    }

    fun selectCustomSeedColor(color: Color?) {
        viewModelScope.launch { themePreferenceStore.selectCustomSeedColor(color) }
    }

    fun setUseAmoled(enabled: Boolean) {
        viewModelScope.launch { themePreferenceStore.setUseAmoled(enabled) }
    }

    fun setPaletteStyle(style: PaletteStyle) {
        viewModelScope.launch { themePreferenceStore.setPaletteStyle(style) }
    }

    fun setColorSpec(spec: ColorSpec.SpecVersion) {
        viewModelScope.launch { themePreferenceStore.setColorSpec(spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        viewModelScope.launch { themePreferenceStore.setEnablePredictiveBack(enabled) }
    }

    fun setPageScale(scale: Float) {
        viewModelScope.launch { themePreferenceStore.setPageScale(scale) }
    }
}
