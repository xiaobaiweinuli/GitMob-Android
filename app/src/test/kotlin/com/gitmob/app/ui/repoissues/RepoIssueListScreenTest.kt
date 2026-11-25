package com.gitmob.app.ui.repoissues

import androidx.lifecycle.SavedStateHandle

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.IssueTemplate
import com.gitmob.app.data.model.IssueTemplateLoadResult
import com.gitmob.app.data.model.RepoIssuePage
import com.gitmob.app.data.repository.RepoIssueRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoIssueListScreenTest {
    private val mainDispatcherRule = MainDispatcherRule()
    private val composeRule = createComposeRule()
    private val accounts = AccountSessionRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(accounts).around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val destinations = mutableListOf<String?>()

    @Test
    fun `loading stays in create action and empty templates open blank editor directly`() {
        val pending = CompletableDeferred<ApiResult<IssueTemplateLoadResult>>()
        showList(pending)
        val create = composeRule.onNodeWithContentDescription(context.getString(R.string.issue_new))
        val picker = composeRule.onNodeWithText(context.getString(R.string.issue_template_picker_title))
        create.performClick()

        create.assertIsNotEnabled()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        picker.assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(destinations.isEmpty())
            pending.complete(ApiResult.Success(IssueTemplateLoadResult(true, emptyList())))
        }

        picker.assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf<String?>(null), destinations) }
    }

    @Test
    fun `loaded picker aligns blank and template titles and opens selected template`() {
        val pending = CompletableDeferred<ApiResult<IssueTemplateLoadResult>>()
        showList(pending)
        composeRule.onNodeWithContentDescription(context.getString(R.string.issue_new)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.issue_template_picker_title)).assertDoesNotExist()
        composeRule.runOnIdle {
            pending.complete(ApiResult.Success(IssueTemplateLoadResult(true, listOf(
                IssueTemplate("Bug report", "Describe the bug", null, "bug.yml", emptyList(), emptyList(), emptyList()),
            ))))
        }

        composeRule.onNodeWithText(context.getString(R.string.issue_template_picker_title)).assertIsDisplayed()
        val blank = composeRule.onNodeWithText(context.getString(R.string.issue_template_blank), useUnmergedTree = true)
        val template = composeRule.onNodeWithText("Bug report", useUnmergedTree = true)
        assertEquals(blank.fetchSemanticsNode().boundsInRoot.left, template.fetchSemanticsNode().boundsInRoot.left, 0.5f)
        composeRule.onNodeWithText("Bug report").performClick()
        composeRule.runOnIdle { assertEquals(listOf("bug.yml"), destinations) }
        composeRule.onNodeWithText(context.getString(R.string.issue_template_picker_title)).assertDoesNotExist()
    }

    private fun showList(pending: CompletableDeferred<ApiResult<IssueTemplateLoadResult>>) {
        val repository = io.mockk.mockk<RepoIssueRepository>()
        coEvery { repository.getIssues("o", "r", any(), null) } returns ApiResult.Success(
            RepoIssuePage("R1", RepoPermission.ADMIN, RepoPermission.ADMIN.toCapabilities(), true, true, 0, emptyList(), false, null),
        )
        coEvery { repository.getLabels("o", "r") } returns ApiResult.Success(emptyList())
        coEvery { repository.getMilestones("o", "r") } returns ApiResult.Success(emptyList())
        coEvery { repository.getAssignableUsers("o", "r") } returns ApiResult.Success(emptyList())
        coEvery { repository.getIssueTemplates("o", "r") } coAnswers { pending.await() }
        val viewModel = RepoIssueListViewModel(repository, ErrorEventBus(), RepoUpdateEventBus(), accounts.session, savedSearch = SavedStateHandle())
        composeRule.setContent {
            MaterialTheme {
                RepoIssueListScreen(
                    owner = "o",
                    name = "r",
                    permission = RepoPermission.ADMIN,
                    viewerCanCreateIssues = true,
                    onBack = {},
                    onOwnerClick = {},
                    onRepositoryClick = { _, _ -> },
                    onIssueClick = {},
                    onCreate = { destinations += it },
                    viewModel = viewModel,
                )
            }
        }
    }
}
