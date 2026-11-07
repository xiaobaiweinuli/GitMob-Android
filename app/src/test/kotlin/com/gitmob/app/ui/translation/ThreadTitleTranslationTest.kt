package com.gitmob.app.ui.translation

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.translation.*
import com.gitmob.app.ui.repodiscussions.*
import com.gitmob.app.ui.repoissues.*
import com.gitmob.app.ui.repopullrequests.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class ThreadTitleTranslationTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val originalTitle = "Original title"
    private val description = "<p>Original description</p>"

    @Test fun issueMenuSelectsOnlyTitleAndHeaderDisplaysResult() {
        val issue = RepoIssue(
            id = "I1", number = 1, title = originalTitle, body = "Original description", bodyHtml = description,
            state = IssueState.OPEN, stateReason = null, author = null, createdAt = "2026-11-07", updatedAt = "2026-11-07",
            commentCount = 0, labels = emptyList(), assignees = emptyList(), milestone = null, locked = false,
            viewerCanClose = false, viewerCanDelete = false, viewerCanLabel = false, viewerCanSetMilestone = false,
            viewerCanUpdate = false, viewerCanSubscribe = false, viewerCanReopen = false, viewerSubscription = null,
            url = "https://github.com/o/r/issues/1",
        )
        val vm = mockk<RepoIssueDetailViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(RepoIssueDetailUiState(issue = issue))
        verifyTitleAndDescriptionMenus(issue.url) {
            RepoIssueDetailScreen(
                owner = "o", name = "r", number = 1, permission = RepoPermission.READ,
                onBack = {}, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onEdit = {},
                onCompose = {}, onUserClick = {}, viewModel = vm,
            )
        }
    }

    @Test fun pullRequestMenuSelectsOnlyTitleAndHeaderDisplaysResult() {
        val pullRequest = RepoPullRequest(
            id = "P1", number = 1, title = originalTitle, body = "Original description", bodyHtml = description,
            state = RepoPullRequestState.OPEN, isDraft = false, locked = false, author = null,
            createdAt = "2026-11-07", updatedAt = "2026-11-07", baseRefName = "main", headRefName = "feature",
            headRepositoryNameWithOwner = "o/r", commentCount = 0, additions = 1, deletions = 0, changedFiles = 1,
            labels = emptyList(), assignees = emptyList(), milestone = null, mergeable = "MERGEABLE",
            mergeStateStatus = "CLEAN", reviewDecision = null, statusCheckState = null,
            viewerCanClose = false, viewerCanReopen = false, viewerCanUpdate = false, viewerCanLabel = false,
            viewerCanAssign = false, viewerCanSubscribe = false, viewerCanEnableAutoMerge = false,
            viewerCanDisableAutoMerge = false, viewerCanUpdateBranch = false, viewerSubscription = null,
            autoMergeEnabled = false, url = "https://github.com/o/r/pull/1",
        )
        val vm = mockk<RepoPullRequestDetailViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(RepoPullRequestDetailUiState(pullRequest = pullRequest))
        verifyTitleAndDescriptionMenus(pullRequest.url) {
            RepoPullRequestDetailScreen(
                owner = "o", name = "r", number = 1, permission = RepoPermission.READ,
                onBack = {}, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onEdit = {},
                onCommitClick = { _, _ -> }, onCompose = {}, onUserClick = {}, viewModel = vm,
            )
        }
    }

    @Test fun discussionMenuSelectsOnlyTitleAndHeaderDisplaysResult() {
        val discussion = RepoDiscussion(
            id = "D1", number = 1, title = originalTitle, body = "Original description", bodyHtml = description,
            state = RepoDiscussionState.OPEN, category = RepoDiscussionCategory("C1", "General", "", null, false),
            author = null, createdAt = "2026-11-07", updatedAt = "2026-11-07", commentCount = 0,
            labels = emptyList(), locked = false, answerChosenAt = null, viewerCanUpdate = false,
            viewerCanDelete = false, viewerCanClose = false, viewerCanReopen = false, viewerCanSubscribe = false,
            viewerSubscription = null, url = "https://github.com/o/r/discussions/1",
        )
        val vm = mockk<RepoDiscussionDetailViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(RepoDiscussionDetailUiState(discussion = discussion))
        verifyTitleAndDescriptionMenus(discussion.url) {
            RepoDiscussionDetailScreen(
                owner = "o", name = "r", number = 1, permission = RepoPermission.READ,
                onBack = {}, onOwnerClick = {}, onRepositoryClick = { _, _ -> }, onEdit = {},
                onCompose = {}, onUserClick = {}, viewModel = vm,
            )
        }
    }

    private fun verifyTitleAndDescriptionMenus(url: String, screen: @Composable () -> Unit) {
        val vm = mockk<TranslationViewModel>(relaxed = true)
        val preferences = TranslationPreferences(enabled = true, bilingual = false)
        val state = mutableStateOf(TranslationUiState(configuration = TranslationConfiguration(preferences)))
        val title = TranslationTarget(url + ":title", originalTitle)
        val body = TranslationTarget(url, description, html = true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state.value, vm) {}) {
                    screen()
                }
            }
        }
        compose.onNodeWithText(originalTitle).assertIsDisplayed()
        val menus = compose.onAllNodesWithContentDescription(context.getString(R.string.issue_more))
        val topMenu = menus.fetchSemanticsNodes().indices.minBy { menus[it].fetchSemanticsNode().boundsInRoot.top }
        menus[topMenu].performClick()
        compose.onNodeWithText(context.getString(R.string.common_edit)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_delete)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.translation_translate_title)).assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.open(title) }
        verify(exactly = 1) { vm.open(any()) }

        val bodyMenu = menus.fetchSemanticsNodes().indices.maxBy { menus[it].fetchSemanticsNode().boundsInRoot.top }
        menus[bodyMenu].performClick()
        compose.onNodeWithText(context.getString(R.string.common_edit)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_delete)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.translation_start)).assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.open(body) }
        verify(exactly = 2) { vm.open(any()) }

        compose.runOnIdle {
            state.value = state.value.copy(entries = mapOf(
                title.identity to TranslationEntry(
                    document = TranslationDocuments.create(title, preferences.provider),
                    translations = mapOf(0 to "译文标题"), preferences = preferences,
                ),
                body.identity to TranslationEntry(phase = TranslationPhase.REQUESTING),
            ))
        }
        compose.onNodeWithText("译文标题").assertIsDisplayed()
        compose.onNodeWithText(originalTitle).assertDoesNotExist()
    }
}
