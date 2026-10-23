package com.gitmob.app.ui.repocode

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.ExistingRepoPullRequest
import com.gitmob.app.data.model.RepoCodeTree
import com.gitmob.app.data.model.RepoForkSyncInfo
import com.gitmob.app.data.model.RepoForkSyncStatus
import com.gitmob.app.data.repository.RepoGitRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoCodeScreenTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<RepoGitRepository>()
    private val pullRequests = mockk<RepoPullRequestRepository>()
    private lateinit var viewModel: RepoCodeViewModel
    private var existingPr: Int? = null
    private var createdPr: RepoForkSyncInfo? = null
    private val comparison = RepoForkSyncInfo(
        repositoryId = "fork-id", branch = "main", currentOid = "forksha",
        upstreamRepositoryId = "upstream-id", upstreamOwner = "upstream", upstreamName = "repo", upstreamOid = "upsha",
        status = RepoForkSyncStatus.READY, aheadBy = 2, behindBy = 3,
    )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    @Test
    fun `sync dialog shows source to target and keeps merge and cancel in the footer`() {
        render()
        openSync()

        composeRule.onNodeWithText(context.getString(R.string.repo_sync_direction, "upstream", "repo", "main", "viewer", "fork"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_counts, 2, 3)).assertIsDisplayed()
        node(R.string.repo_sync_discard).assertIsDisplayed()
        node(R.string.repo_sync_pr).performScrollTo().assertIsDisplayed()
        val secondary = node(R.string.repo_sync_pr).fetchSemanticsNode().boundsInRoot
        val merge = node(R.string.repo_sync_merge).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(merge.top >= secondary.bottom)
        node(R.string.common_cancel).assertIsDisplayed().performClick()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        coVerify(exactly = 0) { repository.mergeUpstream(any(), any(), any()) }
    }

    @Test
    fun `sync PR asks for base and head confirmation before looking up an existing PR`() {
        coEvery { pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main") } returns
            ApiResult.Success(ExistingRepoPullRequest(12, "Sync", "https://example.test/pr/12"))
        render()
        openSync()
        node(R.string.repo_sync_pr).performScrollTo().performClick()

        node(R.string.repo_sync_pr_title).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_pr_base, "viewer", "fork", "main")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_pr_head, "upstream", "repo", "main")).assertIsDisplayed()
        coVerify(exactly = 0) { pullRequests.findOpenPullRequest(any(), any(), any(), any(), any()) }
        node(R.string.common_continue).performClick()

        assertEquals(12, existingPr)
        assertNull(createdPr)
        node(R.string.repo_sync_pr_title).assertDoesNotExist()
        coVerify(exactly = 1) { pullRequests.findOpenPullRequest("viewer", "fork", "main", "upstream", "main") }
    }

    @Test
    fun `discard requires the exact branch and a changed SHA resets the confirmation input`() {
        render()
        coEvery { repository.getForkSync("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Success(comparison), ApiResult.Success(comparison.copy(currentOid = "new-head")),
        )
        coEvery { repository.forceAlignFork("viewer", "fork", comparison) } returns
            ApiResult.Failure(ApiError.UserVisible(R.string.repo_sync_head_changed))
        openSync()
        node(R.string.repo_sync_discard).performScrollTo().performClick()

        node(R.string.repo_sync_discard_title).assertIsDisplayed()
        node(R.string.repo_sync_discard).assertIsNotEnabled()
        node(R.string.repo_sync_confirm_branch).performScrollTo().performTextReplacement("Main")
        node(R.string.repo_sync_discard).assertIsNotEnabled()
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
        node(R.string.repo_sync_confirm_branch).performTextReplacement("main")
        node(R.string.repo_sync_discard).assertIsEnabled().performClick()

        node(R.string.repo_sync_discard_title).assertDoesNotExist()
        node(R.string.repo_sync_discard).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.repo_sync_current_head, "new-head")).assertIsDisplayed()
        node(R.string.repo_sync_discard).assertIsNotEnabled()
        coVerify(exactly = 1) { repository.forceAlignFork(any(), any(), any()) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun `discard confirmation remains editable and cancellable in a narrow short window`() {
        render()
        openSync()
        node(R.string.repo_sync_discard).performScrollTo().performClick()

        node(R.string.repo_sync_confirm_branch).performScrollTo().assertIsDisplayed().performTextReplacement("main")
        node(R.string.repo_sync_discard).assertIsDisplayed().assertIsEnabled()
        node(R.string.common_cancel).assertIsDisplayed().performClick()

        node(R.string.repo_sync_discard_title).assertDoesNotExist()
        coVerify(exactly = 0) { repository.forceAlignFork(any(), any(), any()) }
    }

    @Test
    fun `archived fork presents status without merge discard or PR entries`() {
        render(archived = true)
        openSync()

        node(R.string.repo_sync_no_permission).assertIsDisplayed()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        node(R.string.repo_sync_discard).assertDoesNotExist()
        node(R.string.repo_sync_pr).assertDoesNotExist()
        node(R.string.common_cancel).assertIsDisplayed()
    }

    @Test
    fun `unrelated histories are labeled separately from a merge conflict`() {
        render()
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(
            comparison.copy(status = RepoForkSyncStatus.NO_COMMON_ANCESTOR),
        )
        openSync()

        node(R.string.repo_sync_no_common_ancestor).assertIsDisplayed()
        node(R.string.repo_sync_conflict).assertDoesNotExist()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        node(R.string.repo_sync_discard).assertDoesNotExist()
        node(R.string.repo_sync_pr).assertDoesNotExist()
    }

    @Test
    fun `failed comparison offers retry and restores operations only after success`() {
        render()
        coEvery { repository.getForkSync("viewer", "fork", "main") } returnsMany listOf(
            ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(comparison),
        )
        openSync()
        node(R.string.common_load_failed).assertIsDisplayed()
        node(R.string.repo_sync_merge).assertDoesNotExist()
        node(R.string.repo_sync_pr).assertDoesNotExist()
        node(R.string.common_retry).performClick()

        node(R.string.repo_sync_merge).assertIsEnabled()
        coVerify(exactly = 2) { repository.getForkSync("viewer", "fork", "main") }
    }

    private fun node(resource: Int) = composeRule.onNodeWithText(context.getString(resource))

    private fun openSync() {
        composeRule.onNodeWithContentDescription(context.getString(R.string.repo_sync_title)).performClick()
    }

    private fun render(archived: Boolean = false) {
        coEvery { repository.getCodeTree("viewer", "fork", "main", "") } returns ApiResult.Success(
            RepoCodeTree(
                repositoryId = "fork-id", permission = RepoPermission.WRITE, capabilities = RepoPermission.WRITE.toCapabilities(archived),
                isArchived = archived, ref = "main", headOid = "forksha", path = "", entries = emptyList(), isFork = true,
            ),
        )
        coEvery { repository.getForkSync("viewer", "fork", "main") } returns ApiResult.Success(comparison)
        viewModel = RepoCodeViewModel(repository, pullRequests, ErrorEventBus(), mockk(), RepoUpdateEventBus())
        composeRule.setContent {
            MaterialTheme {
                RepoCodeScreen(
                    owner = "viewer", name = "fork", ref = "main", path = "", permission = RepoPermission.WRITE,
                    onBack = {}, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onAdd = {}, onDirectoryClick = {}, onFileClick = {},
                    onCreateSyncPullRequest = { createdPr = it }, onExistingSyncPullRequest = { existingPr = it }, viewModel = viewModel,
                )
            }
        }
    }
}
