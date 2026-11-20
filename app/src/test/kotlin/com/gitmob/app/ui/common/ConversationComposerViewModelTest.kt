package com.gitmob.app.ui.common

import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.core.markdown.MarkdownRenderer
import com.gitmob.app.data.model.IssueComment
import com.gitmob.app.data.model.RepoPullRequestReview
import com.gitmob.app.data.model.RepoPullRequestReviewEvent
import com.gitmob.app.data.repository.RepoDiscussionRepository
import com.gitmob.app.data.repository.RepoIssueRepository
import com.gitmob.app.data.repository.RepoPullRequestRepository
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.navigation.ConversationComposerTarget
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationComposerViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `preview uses shared markdown renderer`() = runTest {
        val renderer = mockk<MarkdownRenderer>()
        every { renderer.renderToHtml("# title") } returns "<h1>title</h1>"
        val viewModel = viewModel(markdownRenderer = renderer)
        init(viewModel)

        viewModel.updateText("# title")
        viewModel.selectTab(ComposerTab.PREVIEW)
        advanceTimeBy(121)
        advanceUntilIdle()

        assertEquals("<h1>title</h1>", viewModel.state.value.previewHtml)
        assertFalse(viewModel.state.value.previewFailed)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `issue comment success emits refresh event`() = runTest {
        val issueRepository = mockk<RepoIssueRepository>()
        coEvery { issueRepository.addComment("I1", "hello") } returns ApiResult.Success(mockk<IssueComment>())
        val eventBus = RepoUpdateEventBus()
        val viewModel = viewModel(issueRepository = issueRepository, eventBus = eventBus)
        init(viewModel)

        eventBus.eventsFor(accounts.session.epoch).test {
            viewModel.updateText("hello")
            viewModel.submit()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.submitted)
            assertEquals(RepoUpdateEvent.IssueCommentsChanged("owner", "repo", 7), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `failed submission keeps draft`() = runTest {
        val issueRepository = mockk<RepoIssueRepository>()
        coEvery { issueRepository.addComment("I1", "keep me") } returns ApiResult.Failure(ApiError.Unknown("failed"))
        val viewModel = viewModel(issueRepository = issueRepository)
        init(viewModel)

        viewModel.updateText("keep me")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("keep me", viewModel.state.value.text)
        assertFalse(viewModel.state.value.submitted)
        assertFalse(viewModel.state.value.isSubmitting)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pull request review can submit an empty body`() = runTest {
        val pullRequestRepository = mockk<RepoPullRequestRepository>()
        coEvery {
            pullRequestRepository.submitReview("PR1", RepoPullRequestReviewEvent.APPROVE, "")
        } returns ApiResult.Success(mockk<RepoPullRequestReview>())
        val viewModel = viewModel(pullRequestRepository = pullRequestRepository)
        viewModel.init(
            owner = "owner",
            name = "repo",
            number = 7,
            target = ConversationComposerTarget.PULL_REQUEST_REVIEW,
            subjectId = "PR1",
            initialText = "",
            commentId = null,
            replyToId = null,
            reviewEvent = RepoPullRequestReviewEvent.APPROVE.name,
        )

        viewModel.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            pullRequestRepository.submitReview("PR1", RepoPullRequestReviewEvent.APPROVE, "")
        }
        assertTrue(viewModel.state.value.submitted)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `Gist comment success emits Gist refresh event`() = runTest {
        val gistRepository = mockk<GistRepository>()
        coEvery { gistRepository.createComment("gist-id", "hello") } returns ApiResult.Success(mockk())
        val eventBus = GistUpdateEventBus()
        val viewModel = viewModel(gistRepository = gistRepository, gistEventBus = eventBus)
        viewModel.init(
            owner = "",
            name = "",
            number = 0,
            target = ConversationComposerTarget.GIST_COMMENT,
            subjectId = "gist-id",
            initialText = "",
            commentId = null,
            replyToId = null,
            reviewEvent = null,
        )

        eventBus.eventsFor(accounts.session.epoch).test {
            viewModel.updateText("hello")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("gist-id", awaitItem())
            coVerify(exactly = 1) { gistRepository.createComment("gist-id", "hello") }
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `restored draft retains text and selection instead of route initial text`() = runTest {
        val handle = SavedStateHandle()
        val original = viewModel(savedStateHandle = handle)
        init(original)
        original.state.value.textState.edit {
            replace(0, length, "unsent draft")
            selection = TextRange(2, 6)
        }
        val saved = handle.savedStateProvider().saveState()
        original.viewModelScope.cancel()

        val restored = viewModel(savedStateHandle = SavedStateHandle.createHandle(saved, null))
        init(restored)
        assertEquals("unsent draft", restored.state.value.text)
        assertEquals(TextRange(2, 6), restored.state.value.textState.selection)
        restored.viewModelScope.cancel()
    }

    @Test
    fun restoredEmptyDraftDoesNotReapplyRouteText() = runTest {
        val handle = SavedStateHandle()
        val original = viewModel(savedStateHandle = handle)
        init(original, "Original comment")
        original.state.value.textState.edit { replace(0, length, "") }
        val saved = handle.savedStateProvider().saveState()
        original.viewModelScope.cancel()
        val restored = viewModel(savedStateHandle = SavedStateHandle.createHandle(saved, null))
        init(restored, "Original comment")
        assertEquals("", restored.state.value.text)
        restored.viewModelScope.cancel()
    }

    @Test
    fun immediateSubmitReadsTextFieldStateWithoutAnObserverRoundTrip() = runTest {
        val repository = mockk<RepoIssueRepository>()
        coEvery { repository.addComment("I1", "latest input") } returns ApiResult.Success(mockk())
        val vm = viewModel(issueRepository = repository)
        init(vm)
        vm.state.value.textState.edit { replace(0, length, "latest input") }
        vm.submit()
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.addComment("I1", "latest input") }
        assertTrue(vm.state.value.submitted)
        vm.viewModelScope.cancel()
    }

    private fun init(viewModel: ConversationComposerViewModel, initialText: String = "") {
        viewModel.init(
            owner = "owner",
            name = "repo",
            number = 7,
            target = ConversationComposerTarget.ISSUE_COMMENT,
            subjectId = "I1",
            initialText = initialText,
            commentId = null,
            replyToId = null,
            reviewEvent = null,
        )
    }

    private fun viewModel(
        issueRepository: RepoIssueRepository = mockk(relaxed = true),
        pullRequestRepository: RepoPullRequestRepository = mockk(relaxed = true),
        discussionRepository: RepoDiscussionRepository = mockk(relaxed = true),
        gistRepository: GistRepository = mockk(relaxed = true),
        markdownRenderer: MarkdownRenderer = object : MarkdownRenderer {
            override fun renderToHtml(markdown: String): String = markdown
        },
        eventBus: RepoUpdateEventBus = RepoUpdateEventBus(),
        gistEventBus: GistUpdateEventBus = GistUpdateEventBus(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = ConversationComposerViewModel(
        issueRepository = issueRepository,
        pullRequestRepository = pullRequestRepository,
        discussionRepository = discussionRepository,
        gistRepository = gistRepository,
        markdownRenderer = markdownRenderer,
        errorEventBus = ErrorEventBus(),
        repoUpdateEventBus = eventBus,
        gistUpdateEventBus = gistEventBus,
        session = accounts.session,
        savedStateHandle = savedStateHandle,
    )
}
