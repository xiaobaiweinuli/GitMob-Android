package com.gitmob.app.ui.repocompare

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoCompareViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val repository = mockk<RepoGitRepository>()
    private val refs = RepoComparisonRefs("o", "r", "a".repeat(40), "o", "r", "b".repeat(40))
    private val file = RepoChangedFile("src/A.kt", null, RepoChangedFileStatus.MODIFIED, 2, 1, 3, "@@ -1 +1 @@", null, null, null, null)
    private fun model() = RepoCompareViewModel(repository, ErrorEventBus(), accounts.session)
    private fun resolve() {
        coEvery { repository.resolveComparisonRefs("o", "r", "main", "tags/v1") } returns ApiResult.Success(refs)
    }
    private fun page(page: Int = 1, ids: List<String> = listOf("1"), more: Boolean = false) = RepoComparison(
        refs, "ahead", 3, 0, 3, ids.map(::commit), if (page == 1) listOf(file) else emptyList(),
        if (page == 1) 2 else 0, if (page == 1) 1 else 0, page == 1, page, more,
    )
    private fun commit(oid: String) = RepoCommitSummary(oid, oid.take(7), "Commit $oid", "", null, null, null, null, 0, 0, null, emptyList())
    private fun returnsPage(page: RepoComparison) {
        coEvery { repository.compare("o", "r", refs.baseRef, "o", "r", refs.headRef, page.commitsPage) } returns
            ApiResult.Success(RepoComparisonResult.Available(page))
    }

    @Test fun paginationUsesPinnedShasDeduplicatesAndPreservesFirstPageFiles() = runTest {
        resolve()
        returnsPage(page(ids = listOf("1", "1", "2"), more = true))
        val pending = CompletableDeferred<ApiResult<RepoComparisonResult>>()
        coEvery { repository.compare("o", "r", refs.baseRef, "o", "r", refs.headRef, 2) } coAnswers { pending.await() }
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), vm.state.value.comparison!!.commits.map { it.oid })
        vm.loadMore()
        vm.loadMore()
        runCurrent()
        coVerify(exactly = 1) { repository.compare("o", "r", refs.baseRef, "o", "r", refs.headRef, 2) }
        pending.complete(ApiResult.Success(RepoComparisonResult.Available(page(2, listOf("2", "3")))))
        advanceUntilIdle()
        val comparison = vm.state.value.comparison!!
        assertEquals(listOf("1", "2", "3"), comparison.commits.map { it.oid })
        assertEquals(listOf(file), comparison.files)
        assertEquals(2, comparison.additions)
        assertEquals(1, comparison.deletions)
        assertTrue(comparison.filesTruncated)
        assertFalse(comparison.commitsHasNextPage)
        vm.loadMore()
        coVerify(exactly = 1) { repository.resolveComparisonRefs(any(), any(), any(), any()) }
        coVerify(exactly = 2) { repository.compare(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun pageFailureKeepsContentAndRequiresExplicitRetryOfTheSamePage() = runTest {
        resolve()
        returnsPage(page(more = true))
        coEvery { repository.compare("o", "r", refs.baseRef, "o", "r", refs.headRef, 2) } returns
            ApiResult.Failure(ApiError.NetworkError)
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.state.value.loadMoreFailed)
        assertEquals(1, vm.state.value.comparison!!.commitsPage)
        assertEquals(listOf(file), vm.state.value.comparison!!.files)
        vm.loadMore()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.compare("o", "r", refs.baseRef, "o", "r", refs.headRef, 2) }
        returnsPage(page(2, listOf("2")))
        vm.retryMore()
        advanceUntilIdle()
        assertFalse(vm.state.value.loadMoreFailed)
        assertEquals(2, vm.state.value.comparison!!.commitsPage)
        coVerify(exactly = 1) { repository.resolveComparisonRefs(any(), any(), any(), any()) }
    }

    @Test fun firstPageFailureRetriesWithoutResolvingMovingBranchesAgain() = runTest {
        resolve()
        coEvery { repository.compare("o", "r", refs.baseRef, "o", "r", refs.headRef, 1) } returns ApiResult.Failure(ApiError.NetworkError)
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        returnsPage(page())
        vm.retry()
        advanceUntilIdle()
        assertNotNull(vm.state.value.comparison)
        assertFalse(vm.state.value.loadFailed)
        coVerify(exactly = 1) { repository.resolveComparisonRefs(any(), any(), any(), any()) }
    }

    @Test fun resolutionFailureCanRetryAndDoesNotIssueAComparison() = runTest {
        coEvery { repository.resolveComparisonRefs(any(), any(), any(), any()) } returns ApiResult.Failure(ApiError.Forbidden)
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        advanceUntilIdle()
        assertTrue(vm.state.value.loadFailed)
        coVerify(exactly = 0) { repository.compare(any(), any(), any(), any(), any(), any(), any()) }
        resolve()
        returnsPage(page())
        vm.retry()
        advanceUntilIdle()
        assertNotNull(vm.state.value.comparison)
    }

    @Test fun identicalAndBehindComparisonsRemainAvailable() = runTest {
        resolve()
        for (status in listOf("identical", "behind", "diverged")) {
            returnsPage(page(ids = emptyList()).copy(status = status, aheadBy = 0, totalCommits = 0))
            val vm = model()
            vm.init("o", "r", "main", "tags/v1")
            advanceUntilIdle()
            assertEquals(status, vm.state.value.comparison!!.status)
            assertFalse(vm.state.value.loadFailed)
        }
    }

    @Test fun noCommonAncestorIsShownWithoutInventingAnEmptyComparison() = runTest {
        resolve()
        coEvery { repository.compare(any(), any(), any(), any(), any(), any(), any()) } returns ApiResult.Success(RepoComparisonResult.NoCommonAncestor)
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        advanceUntilIdle()
        assertTrue(vm.state.value.noCommonAncestor)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.loadFailed)
        assertNull(vm.state.value.comparison)
    }

    @Test fun emptyPageStopsPagingEvenIfServerStillClaimsMore() = runTest {
        resolve()
        returnsPage(page(more = true))
        returnsPage(page(2, emptyList(), more = true))
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertFalse(vm.state.value.comparison!!.commitsHasNextPage)
        assertEquals(listOf(file), vm.state.value.comparison!!.files)
    }

    @Test fun accountChangeCancelsPendingRequestsAndBlocksFurtherLoads() = runTest {
        val pending = CompletableDeferred<ApiResult<RepoComparisonRefs>>()
        coEvery { repository.resolveComparisonRefs(any(), any(), any(), any()) } coAnswers { pending.await() }
        val vm = model()
        vm.init("o", "r", "main", "tags/v1")
        runCurrent()
        accounts.session.replace(emptyList(), null)
        pending.complete(ApiResult.Success(refs))
        advanceUntilIdle()
        vm.retry()
        vm.loadMore()
        advanceUntilIdle()
        assertNull(vm.state.value.comparison)
        coVerify(exactly = 1) { repository.resolveComparisonRefs(any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.compare(any(), any(), any(), any(), any(), any(), any()) }
    }
}
