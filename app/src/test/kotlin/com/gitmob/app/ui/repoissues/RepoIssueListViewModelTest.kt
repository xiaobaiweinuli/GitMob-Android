package com.gitmob.app.ui.repoissues

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoIssueRepository
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RepoIssueListViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initial load and filter change request first page`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", RepoPermission.ADMIN, true); advanceUntilIdle()
        vm.setState(RepoIssueStateFilter.CLOSED); advanceUntilIdle()
        assertEquals(RepoIssueStateFilter.CLOSED, vm.state.value.filter.state)
        assertEquals(1, vm.state.value.items.size)
        coVerify(exactly = 2) { repository.getIssues("o", "r", any(), null) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `successful delete removes item after api success`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.deleteIssue("I1") } returns ApiResult.Success(Unit)
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", RepoPermission.ADMIN, true); advanceUntilIdle()
        vm.confirmDelete(vm.state.value.items.single()); vm.deletePending(); advanceUntilIdle()
        assertEquals(0, vm.state.value.items.size)
        coVerify(exactly = 1) { repository.deleteIssue("I1") }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `failed delete keeps item`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.deleteIssue("I1") } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", RepoPermission.ADMIN, true); advanceUntilIdle()
        vm.confirmDelete(vm.state.value.items.single()); vm.deletePending(); advanceUntilIdle()
        assertEquals(1, vm.state.value.items.size)
        assertFalse(vm.state.value.pendingDelete != null)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `template picker opens when valid templates exist`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(
            IssueTemplateLoadResult(true, listOf(IssueTemplate("Bug", "About", null, "bug.yml", emptyList(), emptyList(), emptyList()))),
        )
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.beginCreate()
        advanceUntilIdle()
        assertTrue(vm.state.value.templatePickerVisible)
        assertEquals("bug.yml", vm.state.value.templates.single().filename)
        assertFalse(vm.state.value.blankCreateRequested)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `blank issue starts directly when no templates are available`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(true, emptyList()))
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.beginCreate()
        advanceUntilIdle()
        assertTrue(vm.state.value.blankCreateRequested)
        assertFalse(vm.state.value.templatePickerVisible)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `template dialog remains visible when blank issues are disabled`() = runTest {
        val repository = repositoryMock()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.getIssueTemplates("o", "r") } returns ApiResult.Success(IssueTemplateLoadResult(false, emptyList()))
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r", RepoPermission.ADMIN, true)
        advanceUntilIdle()
        vm.beginCreate()
        advanceUntilIdle()
        assertTrue(vm.state.value.templatePickerVisible)
        assertFalse(vm.state.value.blankCreateRequested)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `search retries an initial error and refreshes only on explicit refresh`() = runTest {
        val repository = repositoryMock()
        val filter = RepoIssueFilter()
        coEvery { repository.getIssues("o", "r", filter, null) } returns ApiResult.Success(page())
        coEvery { repository.searchIssues("o", "r", "fix", filter, null) } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(page()), ApiResult.Success(page()),
        )
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r")
        vm.submitSearch("  fix ")
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        assertTrue(vm.state.value.items.isEmpty())
        vm.retry()
        vm.submitSearch("fix  ")
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.searchIssues("o", "r", "fix", filter, null) }
        assertFalse(vm.state.value.loadFailed)
        assertEquals(listOf(issue()), vm.state.value.items)
        vm.refresh()
        advanceUntilIdle()
        coVerify(exactly = 3) { repository.searchIssues("o", "r", "fix", filter, null) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `search append error retains rows and retries the same cursor`() = runTest {
        val repository = repositoryMock()
        val next = SearchContinuation(cursor = "C1", received = 1)
        val first = page().copy(totalCount = 2, hasNextPage = true, endCursor = next.cursor, searchNext = next)
        val last = page().copy(totalCount = 2, items = listOf(issue().copy(id = "I2")))
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(page())
        coEvery { repository.searchIssues("o", "r", "fix", any(), null) } returns ApiResult.Success(first)
        coEvery { repository.searchIssues("o", "r", "fix", any(), next) } returnsMany listOf(ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(last))
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r")
        vm.submitSearch("fix")
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.state.value.loadMoreFailed)
        assertEquals(first.items, vm.state.value.items)
        assertEquals(next.cursor, vm.state.value.nextCursor)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("I1", "I2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loadMoreFailed)
        assertFalse(vm.state.value.hasNextPage)
        coVerify(exactly = 2) { repository.searchIssues("o", "r", "fix", any(), next) }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `late append cannot replace results after clearing changing keywords or changing filters`() = runTest {
        val repository = repositoryMock()
        val open = RepoIssueFilter(labels = setOf("bug"))
        val closed = open.copy(state = RepoIssueStateFilter.CLOSED)
        val next = SearchContinuation(cursor = "C1", received = 1)
        val first = page().copy(hasNextPage = true, endCursor = next.cursor, searchNext = next)
        fun result(id: String) = ApiResult.Success(page().copy(items = listOf(issue().copy(id = id))))
        coEvery { repository.getIssues("o", "r", any(), null) } returns result("ordinary")
        coEvery { repository.searchIssues("o", "r", "old", open, null) } returns ApiResult.Success(first)
        coEvery { repository.searchIssues("o", "r", "new", open, null) } returns result("new")
        coEvery { repository.searchIssues("o", "r", "old", closed, null) } returns result("closed")
        lateinit var pending: Continuation<ApiResult<RepoIssuePage>>
        coEvery { repository.searchIssues("o", "r", "old", open, next) } coAnswers { suspendCoroutine { pending = it } }
        val vm = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session)
        vm.init("o", "r")
        vm.setFilter(open)
        fun checkChange(expectedId: String, change: () -> Unit) {
            vm.submitSearch("old")
            vm.loadMore()
            assertTrue(vm.state.value.isLoadingMore)
            change()
            pending.resume(result("stale"))
            assertEquals(listOf(expectedId), vm.state.value.items.map { it.id })
            assertFalse(vm.state.value.isLoadingMore)
            assertFalse(vm.state.value.hasNextPage)
            assertEquals(null, vm.state.value.nextCursor)
        }
        checkChange("ordinary") { vm.submitSearch("   ") }
        assertEquals(open, vm.state.value.filter)
        coVerify(exactly = 2) { repository.getIssues("o", "r", open, null) }
        checkChange("new") { vm.submitSearch("new") }
        checkChange("closed") { vm.setFilter(closed) }
        assertEquals(closed, vm.state.value.filter)
        assertEquals("old", vm.state.value.submittedQuery)
        vm.viewModelScope.cancel()
    }

    private fun repositoryMock(): RepoIssueRepository = mockk {
        coEvery { getLabels(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getMilestones(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getAssignableUsers(any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { getIssueTemplates(any(), any()) } returns ApiResult.Success(IssueTemplateLoadResult(true, emptyList()))
    }

    private fun page() = RepoIssuePage("R1", RepoPermission.ADMIN, RepoPermission.ADMIN.toCapabilities(), true, true, 1, listOf(issue()), false, null)
    private fun issue() = RepoIssue("I1", 1, "Title", "body", "<p>body</p>", IssueState.OPEN, null, null, "2026-01-01", "2026-01-02", 0, emptyList(), emptyList(), null, false, true, true, true, true, true, true, false, "UNSUBSCRIBED")
}
