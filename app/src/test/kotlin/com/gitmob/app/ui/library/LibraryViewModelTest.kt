package com.gitmob.app.ui.library

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.local.library.LibraryRepository
import com.gitmob.app.data.model.LibraryState
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts)

    private fun repository() = mockk<LibraryRepository> {
        every { observe(any(), any()) } returns flowOf(LibraryState(loaded = true))
    }

    @Test fun onePendingSavePreventsDuplicateAndFailureKeepsDialogOpen() = runTest {
        val repository = repository()
        val result = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.deleteVisit(any(), "R1") } coAnswers { result.await() }
        val vm = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        var closed = false
        vm.deleteVisit("R1") { closed = true }
        vm.deleteVisit("R1") { closed = true }
        assertTrue(vm.busy.value)
        result.complete(ApiResult.Failure(ApiError.NetworkError))
        assertFalse(vm.busy.value)
        assertFalse(closed)
        coVerify(exactly = 1) { repository.deleteVisit(any(), "R1") }
        vm.viewModelScope.cancel()
    }

    @Test fun openingGroupSelectsOnlyItsQueryAndRetryKeepsContent() = runTest {
        val repository = repository()
        val expected = LibraryState(loaded = true)
        val content = com.gitmob.app.data.model.LibraryContent.Group("work")
        every { repository.observe(any(), content) } returns flowOf(expected)
        val vm = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        vm.show(content)
        vm.show(content)
        io.mockk.verify(exactly = 1) { repository.observe(any(), content) }
        assertEquals(expected, vm.state.value)
        vm.load()
        assertTrue(vm.state.value.loaded)
        vm.viewModelScope.cancel()
    }

    @Test fun observationFailureRetainsPreviouslyLoadedCards() = runTest {
        val repository = repository()
        val expected = LibraryState(loaded = true)
        every { repository.observe(any(), any()) } returns flow { emit(expected); error("read failed") }
        val vm = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        vm.show(com.gitmob.app.data.model.LibraryContent.History)
        assertEquals(expected, vm.state.value)
        assertTrue(vm.failed.value)
        vm.viewModelScope.cancel()
    }

    @Test fun failedReorderReportsRollbackOnce() = runTest {
        val repository = repository()
        coEvery { repository.reorderGroups(any(), any()) } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val results = mutableListOf<Boolean>()
        vm.reorderGroups(listOf("default", "group")) { results += it }
        assertEquals(listOf(false), results)
        assertFalse(vm.busy.value)
        vm.viewModelScope.cancel()
    }
}
