package com.gitmob.app.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.theme.ThemeMode
import com.gitmob.app.ui.theme.ThemePreference
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `主题偏好跟随Store的Flow更新`() = runTest {
        val source = MutableStateFlow(ThemePreference())
        val store = mockk<ThemePreferenceStore>(relaxed = true) {
            every { preference } returns source
        }
        val viewModel = SettingsViewModel(store)

        viewModel.themePreference.test {
            assertEquals(ThemePreference(), awaitItem())

            val updated = ThemePreference(
                mode = ThemeMode.DARK,
                useDynamicColor = true,
            )
            source.value = updated
            assertEquals(updated, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `外观操作委托给ThemePreferenceStore`() = runTest {
        val source = MutableStateFlow(ThemePreference())
        val store = mockk<ThemePreferenceStore>(relaxed = true) {
            every { preference } returns source
        }
        val viewModel = SettingsViewModel(store)
        val blue = Color(0xFF1A73E8)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        viewModel.setUseDynamicColor(true)
        viewModel.selectCustomSeedColor(blue)
        viewModel.setUseAmoled(true)
        viewModel.setPaletteStyle(PaletteStyle.Vibrant)
        viewModel.setColorSpec(ColorSpec.SpecVersion.SPEC_2025)
        viewModel.setEnablePredictiveBack(false)
        viewModel.setPageScale(0.9f)

        coVerify(exactly = 1) { store.setMode(ThemeMode.LIGHT) }
        coVerify(exactly = 1) { store.setUseDynamicColor(true) }
        coVerify(exactly = 1) { store.selectCustomSeedColor(blue) }
        coVerify(exactly = 1) { store.setUseAmoled(true) }
        coVerify(exactly = 1) { store.setPaletteStyle(PaletteStyle.Vibrant) }
        coVerify(exactly = 1) { store.setColorSpec(ColorSpec.SpecVersion.SPEC_2025) }
        coVerify(exactly = 1) { store.setEnablePredictiveBack(false) }
        coVerify(exactly = 1) { store.setPageScale(0.9f) }

        viewModel.viewModelScope.cancel()
    }
}
