package com.gitmob.app

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.theme.ThemePreference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun firstFrameOnlyWaitsForAuthenticationAndTheme() = runTest {
        val session = AccountSession()
        val auth = mockk<AuthRepository>()
        val themes = mockk<ThemePreferenceStore>()
        val theme = MutableStateFlow(ThemePreference())
        val initialized = CompletableDeferred<ApiResult<Unit>>()
        every { auth.accountState } returns session.state
        every { themes.preference } returns theme
        coEvery { auth.initialize(session.epoch) } coAnswers { initialized.await() }
        val vm = StartupViewModel(auth, themes, ErrorEventBus(), session)
        assertFalse(vm.isReady.value)
        initialized.complete(ApiResult.Success(Unit))
        runCurrent()
        assertFalse(vm.isReady.value)
        theme.value = ThemePreference(isLoaded = true)
        runCurrent()
        assertTrue(vm.isReady.value)
        vm.viewModelScope.cancel()
    }
}
