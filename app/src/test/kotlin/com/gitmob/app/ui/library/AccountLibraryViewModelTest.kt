package com.gitmob.app.ui.library

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.LibrarySettings
import com.gitmob.app.data.model.FavoriteGroup
import com.gitmob.app.data.model.HistoryLimitResult
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLibraryViewModelTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)

    private fun repository() = com.gitmob.app.testutil.libraryRepositoryStub()

    @Test fun lateOldAccountResultCannotPublishSuccess() = runTest {
        val repository = repository()
        val result = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.clearHistory(any()) } coAnswers { withContext(NonCancellable) { result.await() } }
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        var closed = false
        vm.clearHistory { closed = true }
        assertTrue(LibrarySettingOperation.Clear in vm.pendingSettings.value)
        val second = GitHubAccount("2", 2, "U_2", "second")
        accounts.session.replace(listOf(second), second.key)
        result.complete(ApiResult.Success(Unit))
        assertFalse(closed)
        assertTrue(vm.pendingSettings.value.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun settingSavesOnlyMarkTheirOwnControlPending() = runTest {
        val repository = repository()
        val favorites = CompletableDeferred<ApiResult<Unit>>()
        val history = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.setFavoritesEnabled(any(), false) } coAnswers { favorites.await() }
        coEvery { repository.setHistoryEnabled(any(), false) } coAnswers { history.await() }
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        vm.favoritesEnabled(false)
        vm.favoritesEnabled(false)
        assertEquals(setOf(LibrarySettingOperation.Favorites), vm.pendingSettings.value)
        vm.historyEnabled(false)
        assertEquals(setOf(LibrarySettingOperation.Favorites, LibrarySettingOperation.History), vm.pendingSettings.value)
        favorites.complete(ApiResult.Failure(ApiError.NetworkError))
        assertEquals(setOf(LibrarySettingOperation.History), vm.pendingSettings.value)
        history.complete(ApiResult.Success(Unit))
        assertTrue(vm.pendingSettings.value.isEmpty())
        coVerify(exactly = 1) { repository.setFavoritesEnabled(any(), false) }
        coVerify(exactly = 1) { repository.setHistoryEnabled(any(), false) }
        vm.viewModelScope.cancel()
    }

    @Test fun settingsHaveOneAccountObserverAndDoNotWaitForCount() = runTest {
        val repository = repository()
        val settings = MutableStateFlow(LibrarySettings(historyLimit = 37))
        every { repository.observeSettings(any()) } returns settings
        every { repository.observeHistoryCount(any()) } returns flow { awaitCancellation() }
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val home = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.settings.collect {} }
        val advanced = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.settings.collect {} }
        val count = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyCount.collect {} }
        runCurrent()
        assertEquals(37, vm.settings.value.value?.historyLimit)
        assertNull(vm.historyCount.value.value)
        assertEquals(1, settings.subscriptionCount.value)
        home.cancel(); advanced.cancel(); count.cancel()
        io.mockk.verify(exactly = 1) { repository.observeSettings(any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun summariesShareOneObserverStopAfterTimeoutAndRetainReplay() = runTest {
        val repository = repository()
        var starts = 0
        var stops = 0
        val groups = listOf(FavoriteGroup("default", "", 4, 0))
        every { repository.observeGroups(any()) } returns flow {
            starts++
            try { emit(groups); awaitCancellation() } finally { stops++ }
        }
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        runCurrent()
        assertEquals(0, starts)
        val home = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.groups.collect {} }
        val favorites = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.groups.collect {} }
        runCurrent()
        assertEquals(1, starts)
        home.cancel(); favorites.cancel()
        advanceTimeBy(4_999); runCurrent()
        assertEquals(0, stops)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, stops)
        assertEquals(groups, vm.groups.value.value)
        val revisit = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.groups.collect {} }
        runCurrent()
        assertEquals(2, starts)
        assertEquals(groups, vm.groups.value.value)
        revisit.cancel(); vm.viewModelScope.cancel()
    }

    @Test fun summaryFailureRetainsDataAndRetryRecoversWithoutEmptyState() = runTest {
        val repository = repository()
        val groups = listOf(FavoriteGroup("work", "Work", 1, 1))
        every { repository.observeGroups(any()) } returns flow { emit(groups); error("read failed") }
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.groups.collect {} }
        runCurrent()
        assertEquals(groups, vm.groups.value.value)
        assertTrue(vm.groups.value.failed)
        every { repository.observeGroups(any()) } returns flowOf(groups)
        vm.retryGroups(); runCurrent()
        assertEquals(FavoriteGroupsState(groups), vm.groups.value)
        collector.cancel(); vm.viewModelScope.cancel()
    }

    @Test fun failedCountDoesNotBlockSettingsOrUnsafeLimitCheck() = runTest {
        val repository = repository()
        every { repository.observeHistoryCount(any()) } returns flow { error("count unavailable") }
        coEvery { repository.setHistoryLimit(any(), 20, false) } returns ApiResult.Success(HistoryLimitResult.NeedsConfirmation(80))
        coEvery { repository.setHistoryLimit(any(), 20, true) } returns ApiResult.Success(HistoryLimitResult.Saved)
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.historyCount.collect {} }
        runCurrent()
        assertNotNull(vm.settings.value.value)
        assertEquals(HistoryCountState(failed = true), vm.historyCount.value)
        var result: HistoryLimitResult? = null
        vm.historyLimit(20, false) { result = it }
        assertEquals(HistoryLimitResult.NeedsConfirmation(80), result)
        vm.historyLimit(20, true) { result = it }
        assertEquals(HistoryLimitResult.Saved, result)
        collector.cancel(); vm.viewModelScope.cancel()
    }

    @Test fun unknownSettingsCannotWriteDefaults() = runTest {
        val repository = repository()
        every { repository.observeSettings(any()) } returns flow { awaitCancellation() }
        val vm = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        vm.favoritesEnabled(false)
        vm.historyLimit(10, false) { fail("Unknown settings must not be editable") }
        assertNull(vm.settings.value.value)
        coVerify(exactly = 0) { repository.setFavoritesEnabled(any(), any()) }
        coVerify(exactly = 0) { repository.setHistoryLimit(any(), any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun accountChangeCancelsSummaryObserverAndCreatesUnpopulatedNewCache() = runTest {
        val repository = repository()
        var stopped = false
        every { repository.observeGroups(any()) } returns flow {
            try { emit(listOf(FavoriteGroup("private", "Private", 1, 1))); awaitCancellation() }
            finally { stopped = true }
        }
        val old = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { old.groups.collect {} }
        runCurrent()
        val second = GitHubAccount("2", 2, "U_2", "second")
        accounts.session.replace(listOf(second), second.key)
        runCurrent()
        assertTrue(stopped)
        val fresh = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        assertNull(fresh.groups.value.value)
        old.favoritesEnabled(false)
        coVerify(exactly = 0) { repository.setFavoritesEnabled(any(), any()) }
        collector.cancel(); fresh.viewModelScope.cancel()
    }
}
