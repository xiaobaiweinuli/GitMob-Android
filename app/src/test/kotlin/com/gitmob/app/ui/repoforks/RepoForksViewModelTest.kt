package com.gitmob.app.ui.repoforks

import androidx.lifecycle.SavedStateHandle
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoForkSort
import com.gitmob.app.data.model.RepoForkSortDirection
import com.gitmob.app.data.model.RepoForkSortField
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class RepoForksViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<RepoDetailRepository>()
    private val stars = RepoForkSort(RepoForkSortField.STARS, RepoForkSortDirection.DESC)
    private fun model(saved: SavedStateHandle = SavedStateHandle()) =
        RepoForksViewModel(repository, ErrorEventBus(), accounts.session, saved)

    private fun repo(owner: String) = RepoListItem(
        name = "repo",
        ownerLogin = owner,
        description = null,
        homepageUrl = null,
        visibility = RepoVisibility.PUBLIC,
        isArchived = false,
        isFork = true,
        hasIssuesEnabled = true,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        pushedAt = null,
        forkedFromOwner = "upstream",
        forkedFromName = "repo",
        languageName = null,
        languageColor = null,
        stargazerCount = 0,
        forkCount = 0,
        openIssueCount = 0,
        topics = emptyList(),
        defaultBranchName = "main",
    )

    @Test
    fun `loadMore appends unique direct forks and keeps connection count`() = runTest {
        coEvery { repository.getForks("upstream", "repo", null, any()) } returns ApiResult.Success(
            RepoList(20, listOf(repo("one")), true, "next"),
        )
        coEvery { repository.getForks("upstream", "repo", "next", any()) } returns ApiResult.Success(
            RepoList(20, listOf(repo("one"), repo("two")), false, null),
        )
        val model = model()
        model.init("upstream", "repo")
        model.loadMore()
        assertEquals(listOf("one", "two"), model.state.value.repos.map { it.ownerLogin })
        assertEquals(20, model.state.value.totalCount)
        assertFalse(model.state.value.hasNextPage)
        model.loadMore()
        coVerify(exactly = 2) { repository.getForks(any(), any(), any(), RepoForkSort()) }
    }

    @Test
    fun `changing sort cancels a pending page and resets its cursor`() = runTest {
        val pending = CompletableDeferred<ApiResult<RepoList>>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { repository.getForks("upstream", "repo", null, RepoForkSort()) } returns ApiResult.Success(
            RepoList(20, listOf(repo("old")), true, "old-next"),
        )
        coEvery { repository.getForks("upstream", "repo", "old-next", RepoForkSort()) } coAnswers {
            try { pending.await() } finally { cancelled.complete(Unit) }
        }
        coEvery { repository.getForks("upstream", "repo", null, stars) } returns ApiResult.Success(
            RepoList(20, listOf(repo("starred")), true, "stars-next"),
        )
        coEvery { repository.getForks("upstream", "repo", "stars-next", stars) } returns ApiResult.Success(
            RepoList(20, listOf(repo("next-starred")), false, null),
        )
        val model = model()
        model.init("upstream", "repo")
        model.loadMore()
        model.loadMore()
        assertTrue(model.state.value.isLoadingMore)
        model.selectSort(stars)
        assertTrue(cancelled.isCompleted)
        assertEquals(listOf("starred"), model.state.value.repos.map { it.ownerLogin })
        model.loadMore()
        assertEquals(listOf("starred", "next-starred"), model.state.value.repos.map { it.ownerLogin })
        coVerify(exactly = 1) { repository.getForks("upstream", "repo", "old-next", RepoForkSort()) }
        coVerify(exactly = 0) { repository.getForks("upstream", "repo", "old-next", stars) }
    }

    @Test
    fun `late result from a cancelled load cannot replace the new sort`() = runTest {
        val pending = CompletableDeferred<ApiResult<RepoList>>()
        coEvery { repository.getForks("upstream", "repo", null, RepoForkSort()) } coAnswers {
            withContext(NonCancellable) { pending.await() }
        }
        coEvery { repository.getForks("upstream", "repo", null, stars) } returns ApiResult.Success(
            RepoList(1, listOf(repo("new")), false, null),
        )
        val model = model()
        model.init("upstream", "repo")
        model.selectSort(stars)
        pending.complete(ApiResult.Success(RepoList(99, listOf(repo("late")), true, "stale")))
        assertEquals(listOf("new"), model.state.value.repos.map { it.ownerLogin })
        assertEquals(1, model.state.value.totalCount)
        assertFalse(model.state.value.hasNextPage)
    }

    @Test
    fun `selecting the current sort or returning to the page does not reload`() = runTest {
        coEvery { repository.getForks(any(), any(), any(), any()) } returns ApiResult.Success(
            RepoList(1, listOf(repo("one")), false, null),
        )
        val model = model()
        model.init("upstream", "repo")
        model.selectSort(RepoForkSort())
        model.init("upstream", "repo")
        coVerify(exactly = 1) { repository.getForks(any(), any(), any(), any()) }
        assertEquals("one", model.state.value.repos.single().ownerLogin)
    }

    @Test
    fun `saved ordering is used by the recreated view model`() = runTest {
        coEvery { repository.getForks(any(), any(), any(), any()) } returns ApiResult.Success(RepoList(0, emptyList(), false, null))
        val saved = SavedStateHandle()
        val first = model(saved)
        first.init("upstream", "repo")
        first.selectSort(stars)
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        restored.init("upstream", "repo")
        assertEquals(stars, restored.state.value.sort)
        coVerify(exactly = 2) { repository.getForks("upstream", "repo", null, stars) }
    }

    @Test
    fun `failed pagination retries the same cursor and ordering`() = runTest {
        coEvery { repository.getForks(any(), any(), null, any()) } returns ApiResult.Success(
            RepoList(2, listOf(repo("one")), true, "next"),
        )
        coEvery { repository.getForks(any(), any(), "next", stars) } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError),
            ApiResult.Success(RepoList(2, listOf(repo("two")), false, null)),
        )
        val model = model()
        model.selectSort(stars)
        model.init("upstream", "repo")
        model.loadMore()
        assertTrue(model.state.value.loadFailed)
        assertEquals(1, model.state.value.repos.size)
        model.loadMore()
        assertFalse(model.state.value.loadFailed)
        assertFalse(model.state.value.isLoadingMore)
        assertEquals(2, model.state.value.repos.size)
        coVerify(exactly = 2) { repository.getForks("upstream", "repo", "next", stars) }
    }
}
