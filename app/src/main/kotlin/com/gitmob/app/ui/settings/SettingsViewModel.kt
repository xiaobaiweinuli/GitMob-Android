package com.gitmob.app.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.ui.theme.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferenceStore: ThemePreferenceStore,
    private val authRepository: AuthRepository,
    private val errorEventBus: ErrorEventBus,
) : ViewModel() {

    fun setThemeMode(mode: ThemeMode) {
        savePreference { themePreferenceStore.setMode(mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        savePreference { themePreferenceStore.setUseDynamicColor(enabled) }
    }

    fun selectCustomSeedColor(color: Color?) {
        savePreference { themePreferenceStore.selectCustomSeedColor(color) }
    }

    fun setUseAmoled(enabled: Boolean) {
        savePreference { themePreferenceStore.setUseAmoled(enabled) }
    }

    fun setPaletteStyle(style: PaletteStyle) {
        savePreference { themePreferenceStore.setPaletteStyle(style) }
    }

    fun setColorSpec(spec: ColorSpec.SpecVersion) {
        savePreference { themePreferenceStore.setColorSpec(spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        savePreference { themePreferenceStore.setEnablePredictiveBack(enabled) }
    }

    fun setPageScale(scale: Float) {
        savePreference { themePreferenceStore.setPageScale(scale) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            when (val result = authRepository.logout()) {
                is ApiResult.Success -> onLoggedOut()
                is ApiResult.Failure -> errorEventBus.emit(result.error)
            }
        }
    }

    private fun savePreference(update: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                update()
            } catch (error: IOException) {
                Log.e("GitMob-Settings", "保存设置失败", error)
                errorEventBus.emit(ApiError.Unknown(error.message ?: "Failed to save settings"))
            }
        }
    }
}
