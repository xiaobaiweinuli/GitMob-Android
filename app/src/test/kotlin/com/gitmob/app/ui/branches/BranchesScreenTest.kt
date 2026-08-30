package com.gitmob.app.ui.branches

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.PagedBranches
import com.gitmob.app.data.model.RepoBranch
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BranchesScreenTest {
    private val mainDispatcherRule = MainDispatcherRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `push permission shows create button and rename for non-default branch`() {
        val viewModel = viewModelWithBranches(
            RepoBranch("R1", "main", true, "abc123"),
            RepoBranch("R2", "feature/test", false, "def456"),
        )

        composeRule.setContent {
            BranchesScreen(
                owner = "owner",
                name = "repo",
                currentRef = "main",
                canPush = true,
                canManageBranchProtection = false,
                onBack = {},
                onOwnerClick = {},
                onRepositoryClick = { _, _ -> },
                viewModel = viewModel,
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.branches_create)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.branches_more_actions)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.branches_rename)).assertIsDisplayed()
    }

    @Test
    fun `read permission hides create and branch action menus`() {
        val viewModel = viewModelWithBranches(
            RepoBranch("R1", "main", true, "abc123"),
            RepoBranch("R2", "feature/test", false, "def456"),
        )

        composeRule.setContent {
            BranchesScreen(
                owner = "owner",
                name = "repo",
                currentRef = "main",
                canPush = false,
                canManageBranchProtection = false,
                onBack = {},
                onOwnerClick = {},
                onRepositoryClick = { _, _ -> },
                viewModel = viewModel,
            )
        }

        composeRule.onAllNodesWithContentDescription(context.getString(R.string.branches_create)).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.branches_more_actions)).assertCountEquals(0)
    }

    private fun viewModelWithBranches(vararg branches: RepoBranch): BranchesViewModel {
        val repository = mockk<RepoDetailRepository>()
        coEvery { repository.getBranches("owner", "repo", null) } returns ApiResult.Success(
            PagedBranches(branches.toList(), hasNextPage = false, endCursor = null),
        )
        return BranchesViewModel(repository, RepoUpdateEventBus(), ErrorEventBus())
    }
}
