package com.gitmob.app.ui.update

import androidx.lifecycle.viewModelScope
import com.gitmob.app.BuildConfig
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.preferences.AppUpdatePreferenceStore
import com.gitmob.app.data.model.AppUpdate
import com.gitmob.app.data.model.AppUpdateCheck
import com.gitmob.app.data.model.AppUpdatePreferences
import com.gitmob.app.data.repository.AppUpdateRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<AppUpdateRepository>()
    private val store = mockk<AppUpdatePreferenceStore>()
    private val preferences = MutableSharedFlow<AppUpdatePreferences>(replay = 1)
    private val errors = ErrorEventBus()
    private val session = AccountSession()
    private val update = AppUpdate("0.2.0", BuildConfig.VERSION_CODE + 1, "Release title", "<p>notes</p>", "https://github.com/app.apk", 1234)
    private lateinit var vm: AppUpdateViewModel

    @Before fun setup() {
        every { store.preferences } returns preferences
        coEvery { store.recordSuccessfulCheck(any()) } returns Unit
        coEvery { repository.check(any()) } returns ApiResult.Success(AppUpdateCheck.Available(update))
        coEvery { repository.download(any()) } returns ApiResult.Success(Unit)
    }
    @After fun cleanup() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        session.epoch.job.cancel()
    }
    private fun create(prefs: AppUpdatePreferences? = AppUpdatePreferences()) {
        if (prefs != null) preferences.tryEmit(prefs)
        vm = AppUpdateViewModel(repository, store, errors)
    }

    @Test fun automaticCheckWaitsForRealPreferencesAndFirstFrameAndRunsOnce() = runTest {
        create(null)
        vm.onAppReady()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.check(any()) }
        preferences.emit(AppUpdatePreferences())
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
        vm.onAppReady()
        preferences.emit(AppUpdatePreferences())
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.check(BuildConfig.VERSION_CODE) }
    }

    @Test fun preferencesAloneDoNotDelayStartupWithAnUpdateRequest() = runTest {
        create()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.check(any()) }
        vm.onAppReady()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.check(any()) }
    }

    @Test fun recentCheckAndDisabledAutomaticDoNotRunAtStartup() = runTest {
        create(AppUpdatePreferences(lastSuccessfulCheckAt = System.currentTimeMillis()))
        vm.onAppReady()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.check(any()) }
        vm.viewModelScope.cancel()
        create(AppUpdatePreferences(automatic = false))
        vm.onAppReady()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.check(any()) }
    }

    @Test fun expiredIntervalChecksAgain() = runTest {
        create(AppUpdatePreferences(lastSuccessfulCheckAt = System.currentTimeMillis() - 86_401_000))
        vm.onAppReady()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.check(any()) }
        coVerify(exactly = 1) { store.recordSuccessfulCheck(any()) }
    }

    @Test fun manualCheckBypassesDisabledAutomaticRecentCheckAndIgnoredVersion() = runTest {
        create(AppUpdatePreferences(false, update.versionCode, System.currentTimeMillis()))
        vm.onAppReady()
        vm.checkManually(session.epoch)
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
        coVerify(exactly = 1) { repository.check(any()) }
    }

    @Test fun ignoredVersionIsSilentAutomaticallyButLaterVersionIsShown() = runTest {
        create(AppUpdatePreferences(ignoredVersionCode = update.versionCode))
        vm.onAppReady()
        advanceUntilIdle()
        assertNull(vm.state.value.update)
        vm.viewModelScope.cancel()
        create(AppUpdatePreferences(ignoredVersionCode = update.versionCode - 1))
        vm.onAppReady()
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
    }

    @Test fun manualRequestJoinsAutomaticRequestAndReceivesFeedback() = runTest {
        val response = CompletableDeferred<ApiResult<AppUpdateCheck>>()
        coEvery { repository.check(any()) } coAnswers { response.await() }
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { errors.eventsFor(session.epoch).collect { events += it } }
        create()
        vm.onAppReady()
        vm.checkManually(session.epoch)
        vm.checkManually(session.epoch)
        assertTrue(vm.state.value.checking)
        response.complete(ApiResult.Success(AppUpdateCheck.UpToDate))
        advanceUntilIdle()
        assertFalse(vm.state.value.checking)
        coVerify(exactly = 1) { repository.check(any()) }
        assertEquals(listOf(BannerEvent.Notice(R.string.app_update_up_to_date)), events)
    }

    @Test fun automaticFailureIsSilentAndDoesNotRetryInTheSameStartup() = runTest {
        coEvery { repository.check(any()) } returns ApiResult.Failure(ApiError.NetworkError)
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { errors.eventsFor(session.epoch).collect { events += it } }
        create()
        vm.onAppReady()
        advanceUntilIdle()
        vm.onAppReady()
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { store.recordSuccessfulCheck(any()) }
        coVerify(exactly = 1) { repository.check(any()) }
        vm.checkManually(session.epoch)
        advanceUntilIdle()
        assertEquals(listOf(BannerEvent.Error(ApiError.NetworkError)), events)
    }

    @Test fun disablingAutomaticDuringARequestSuppressesLateAutomaticPrompt() = runTest {
        val response = CompletableDeferred<ApiResult<AppUpdateCheck>>()
        coEvery { repository.check(any()) } coAnswers { response.await() }
        coEvery { store.setAutomatic(false) } returns AppUpdatePreferences(automatic = false)
        create()
        vm.onAppReady()
        vm.setAutomatic(false, session.epoch)
        advanceUntilIdle()
        response.complete(ApiResult.Success(AppUpdateCheck.Available(update)))
        advanceUntilIdle()
        assertNull(vm.state.value.update)
        vm.checkManually(session.epoch)
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
    }

    @Test fun accountSwitchDoesNotCancelCheckOrResetAppPreferences() = runTest {
        val response = CompletableDeferred<ApiResult<AppUpdateCheck>>()
        coEvery { repository.check(any()) } coAnswers { response.await() }
        create()
        vm.onAppReady()
        session.replace(emptyList(), null)
        response.complete(ApiResult.Success(AppUpdateCheck.Available(update)))
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
        assertEquals(AppUpdatePreferences(), vm.state.value.preferences)
    }

    @Test fun staleManualSessionDoesNotSendFeedbackIntoNewAccount() = runTest {
        val response = CompletableDeferred<ApiResult<AppUpdateCheck>>()
        coEvery { repository.check(any()) } coAnswers { response.await() }
        create(AppUpdatePreferences(automatic = false))
        vm.checkManually(session.epoch)
        session.replace(emptyList(), null)
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { errors.eventsFor(session.epoch).collect { events += it } }
        response.complete(ApiResult.Success(AppUpdateCheck.UpToDate))
        advanceUntilIdle()
        assertTrue(events.isEmpty())
        assertFalse(vm.state.value.checking)
    }

    @Test fun ignorePersistsOnlyTheCandidateAndManualCheckCanShowItAgain() = runTest {
        coEvery { store.ignore(update.versionCode) } returns AppUpdatePreferences(ignoredVersionCode = update.versionCode)
        create()
        vm.onAppReady()
        advanceUntilIdle()
        vm.ignore(session.epoch)
        advanceUntilIdle()
        assertNull(vm.state.value.update)
        assertEquals(update.versionCode, vm.state.value.preferences?.ignoredVersionCode)
        vm.checkManually(session.epoch)
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
    }

    @Test fun dismissDoesNotPersistIgnoreAndIgnoreFailureKeepsDialog() = runTest {
        coEvery { store.ignore(any()) } throws IOException("disk unavailable")
        create()
        vm.onAppReady()
        advanceUntilIdle()
        vm.dismiss()
        assertNull(vm.state.value.update)
        coVerify(exactly = 0) { store.ignore(any()) }
        vm.checkManually(session.epoch)
        advanceUntilIdle()
        vm.ignore(session.epoch)
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
        assertFalse(vm.state.value.savingIgnore)
    }

    @Test fun failedTimestampWriteDoesNotLoseValidUpdate() = runTest {
        coEvery { store.recordSuccessfulCheck(any()) } throws IOException("disk unavailable")
        create()
        vm.onAppReady()
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
    }

    @Test fun preferenceReadFailureNeverActivatesDefaultAutomaticCheckAndManualStillWorks() = runTest {
        every { store.preferences } returns flow { throw IOException("disk unavailable") }
        create(null)
        vm.onAppReady()
        advanceUntilIdle()
        assertNull(vm.state.value.preferences)
        assertTrue(vm.state.value.preferenceFailed)
        coVerify(exactly = 0) { repository.check(any()) }
        vm.checkManually(session.epoch)
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
    }

    @Test fun downloadFailureKeepsDialogAndSuccessClosesIt() = runTest {
        coEvery { repository.download(update) } returns ApiResult.Failure(ApiError.Unknown("no browser"))
        create()
        vm.onAppReady()
        advanceUntilIdle()
        vm.download(session.epoch)
        advanceUntilIdle()
        assertEquals(update, vm.state.value.update)
        coEvery { repository.download(update) } returns ApiResult.Success(Unit)
        vm.download(session.epoch)
        advanceUntilIdle()
        assertNull(vm.state.value.update)
    }

}
