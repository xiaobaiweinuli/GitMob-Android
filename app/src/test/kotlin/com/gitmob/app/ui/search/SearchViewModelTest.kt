package com.gitmob.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.local.search.SearchHistoryStore
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.SearchRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<SearchRepository>()
    private val history = mockk<SearchHistoryStore> {
        coEvery { record(any(), any()) } returns ApiResult.Success(Unit)
        every { observe(any()) } returns flowOf(emptyList())
    }
    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = SearchViewModel(repository, history, ErrorEventBus(), accounts.session, saved)
    private fun item(id: String) = SearchItem.Account(id, id, null, "", null, null)
    private fun page(id: String, next: SearchContinuation? = null) = SearchPage(listOf(item(id)), 40, next)

    @Test fun overviewFailuresAreIndependentAndSubmittingDuplicateInFlightDoesNotRepeat() = runTest {
        val code = CompletableDeferred<ApiResult<SearchPage>>()
        coEvery { repository.search(any(), any(), true, null) } returns ApiResult.Success(page("a"))
        coEvery { repository.search(any(), SearchCategory.CODE, true, null) } coAnswers { code.await() }
        val vm = vm()
        vm.submit("kotlin")
        vm.submit("kotlin")
        coVerify(exactly = 6) { repository.search(any(), any(), true, null) }
        assertNotNull(vm.state.value.sections[SearchCategory.REPOSITORIES]!!.page)
        code.complete(ApiResult.Failure(ApiError.Forbidden))
        assertNotNull(vm.state.value.sections[SearchCategory.CODE]!!.error)
        assertNotNull(vm.state.value.sections[SearchCategory.REPOSITORIES]!!.page)
        vm.viewModelScope.cancel()
    }

    @Test fun failedAppendRetainsItemsAndRetryUsesSameContinuation() = runTest {
        val next = SearchContinuation(cursor = "cursor", received = 20)
        coEvery { repository.search("q", SearchCategory.USERS, false, null) } returns ApiResult.Success(page("first", next))
        coEvery { repository.search("q", SearchCategory.USERS, false, next) } returnsMany listOf(
            ApiResult.Failure(ApiError.Forbidden), ApiResult.Success(SearchPage(listOf(item("first"), item("second")), 40, null)))
        val vm = vm()
        vm.init("q", SearchCategory.USERS)
        vm.loadMore()
        assertEquals(listOf("first"), vm.state.value.sections[SearchCategory.USERS]!!.page!!.items.map { it.key })
        vm.loadMore()
        coVerify(exactly = 1) { repository.search("q", SearchCategory.USERS, false, next) }
        vm.retry(SearchCategory.USERS)
        assertEquals(listOf("first", "second"), vm.state.value.sections[SearchCategory.USERS]!!.page!!.items.map { it.key })
        coVerify(exactly = 2) { repository.search("q", SearchCategory.USERS, false, next) }
        coVerify(exactly = 0) { history.record(any(), any()) }
        vm.viewModelScope.cancel()
    }

    @Test fun appendKeepsEarlierIncompleteCodeResultsVisibleAsIncomplete() = runTest {
        val next = SearchContinuation(page = 2, received = 20)
        coEvery { repository.search("q", SearchCategory.CODE, false, null) } returns
            ApiResult.Success(page("first", next).copy(incomplete = true))
        coEvery { repository.search("q", SearchCategory.CODE, false, next) } returns
            ApiResult.Success(page("second"))
        val vm = vm()
        vm.init("q", SearchCategory.CODE)
        vm.loadMore()
        val page = vm.state.value.sections[SearchCategory.CODE]!!.page!!
        assertEquals(listOf("first", "second"), page.items.map { it.key })
        assertTrue(page.incomplete)
        vm.viewModelScope.cancel()
    }

    @Test fun newQueryCancelsPreviousResultsAndAccountSwitchCancelsOutstandingWork() = runTest {
        val old = CompletableDeferred<ApiResult<SearchPage>>()
        val current = CompletableDeferred<ApiResult<SearchPage>>()
        coEvery { repository.search("old", SearchCategory.CODE, false, null) } coAnswers { old.await() }
        coEvery { repository.search("new", SearchCategory.CODE, false, null) } coAnswers { current.await() }
        val vm = vm()
        vm.submit("old", SearchCategory.CODE)
        vm.submit("new", SearchCategory.CODE)
        old.complete(ApiResult.Success(page("old")))
        assertNull(vm.state.value.sections[SearchCategory.CODE]!!.page)
        accounts.session.replace(emptyList(), null)
        current.complete(ApiResult.Success(page("new")))
        assertNull(vm.state.value.sections[SearchCategory.CODE]!!.page)
    }

    @Test fun restoredOverviewReloadsWithoutRecordingAnotherHistoryEntry() = runTest {
        coEvery { repository.search("saved", any(), true, null) } returns ApiResult.Success(page("a"))
        val vm = vm(SavedStateHandle(mapOf("submittedQuery" to "saved")))
        vm.init()
        vm.init()
        assertEquals("saved", vm.state.value.submittedQuery)
        coVerify(exactly = 6) { repository.search("saved", any(), true, null) }
        coVerify(exactly = 0) { history.record(any(), any()) }
        vm.viewModelScope.cancel()
    }
}
