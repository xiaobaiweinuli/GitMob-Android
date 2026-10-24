package com.gitmob.app.ui.repoforks

import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class RepoForksViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    fun `loadMore appends direct forks`() = runTest {
        val repository = mockk<RepoDetailRepository>()
        coEvery { repository.getForks("upstream", "repo", null) } returns ApiResult.Success(
            RepoList(2, listOf(repo("one")), true, "next"),
        )
        coEvery { repository.getForks("upstream", "repo", "next") } returns ApiResult.Success(
            RepoList(2, listOf(repo("two")), false, null),
        )
        val viewModel = RepoForksViewModel(repository, ErrorEventBus())

        viewModel.init("upstream", "repo")
        viewModel.loadMore()

        assertEquals(listOf("one", "two"), viewModel.state.value.repos.map { it.ownerLogin })
        assertFalse(viewModel.state.value.hasNextPage)
    }
}
