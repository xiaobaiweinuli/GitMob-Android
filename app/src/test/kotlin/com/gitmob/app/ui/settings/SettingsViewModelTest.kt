package com.gitmob.app.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.theme.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `外观操作委托给ThemePreferenceStore`() = runTest {
        val store = mockk<ThemePreferenceStore>(relaxed = true)
        val viewModel = viewModel(store = store)
        val blue = Color(0xFF1A73E8)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        viewModel.setUseDynamicColor(true)
        viewModel.selectCustomSeedColor(blue)
        viewModel.setUseAmoled(true)
        viewModel.setPaletteStyle(PaletteStyle.Vibrant)
        viewModel.setColorSpec(ColorSpec.SpecVersion.SPEC_2025)
        viewModel.setEnablePredictiveBack(false)
        viewModel.setPageScale(0.9f)
        advanceUntilIdle()

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

    @Test
    fun `设置写入失败时发送统一错误且不崩溃`() = runTest {
        val store = mockk<ThemePreferenceStore> {
            coEvery { setMode(ThemeMode.DARK) } throws IOException("disk failed")
        }
        val errorBus = ErrorEventBus()
        val viewModel = viewModel(store = store, errorEventBus = errorBus)
        val error = async(start = CoroutineStart.UNDISPATCHED) { errorBus.events.first() }

        viewModel.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(BannerEvent.Error(ApiError.Unknown("disk failed")), error.await())
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `退出成功后才切换登录页`() = runTest {
        val authRepository = mockk<AuthRepository> {
            coEvery { logout() } returns ApiResult.Success(Unit)
        }
        val viewModel = viewModel(authRepository = authRepository)
        var loggedOut = false

        viewModel.logout { loggedOut = true }
        advanceUntilIdle()

        assertTrue(loggedOut)
        coVerify(exactly = 1) { authRepository.logout() }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `退出失败时保留登录页并发送统一错误`() = runTest {
        val failure = ApiError.Unknown("logout failed")
        val authRepository = mockk<AuthRepository> {
            coEvery { logout() } returns ApiResult.Failure(failure)
        }
        val errorBus = ErrorEventBus()
        val viewModel = viewModel(authRepository = authRepository, errorEventBus = errorBus)
        val error = async(start = CoroutineStart.UNDISPATCHED) { errorBus.events.first() }
        var loggedOut = false

        viewModel.logout { loggedOut = true }
        advanceUntilIdle()

        assertFalse(loggedOut)
        assertEquals(BannerEvent.Error(failure), error.await())
        viewModel.viewModelScope.cancel()
    }

    private fun viewModel(
        store: ThemePreferenceStore = mockk(relaxed = true),
        authRepository: AuthRepository = mockk(relaxed = true),
        errorEventBus: ErrorEventBus = ErrorEventBus(),
    ) = SettingsViewModel(store, authRepository, errorEventBus)
}
