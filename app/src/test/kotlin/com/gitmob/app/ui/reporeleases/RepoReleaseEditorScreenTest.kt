package com.gitmob.app.ui.reporeleases

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoRelease
import com.gitmob.app.data.model.ReleaseNotes
import com.gitmob.app.data.repository.RepoReleaseRepository
import com.gitmob.app.navigation.*
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoReleaseEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = MutableStateFlow(RepoReleaseEditorUiState(phase = ReleaseEditorPhase.EDITABLE, draft = ReleaseEditorDraft(tag = "v1", title = "Unsaved title", body = "Unsaved notes"), capabilities = RepoPermission.WRITE.toCapabilities(), isDirty = true))
    private val vm = mockk<RepoReleaseEditorViewModel>(relaxed = true) { every { state } returns this@RepoReleaseEditorScreenTest.state }
    private lateinit var activity: ComponentActivity
    private val generation = mutableStateOf(0)
    private var back = 0
    private var saved = 0
    private var ownerClicks = 0
    private var repositoryClicks = 0
    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)
    private fun show() {
        compose.setContent {
            val currentActivity = LocalActivity.current as ComponentActivity
            SideEffect { activity = currentActivity }
            run {
                MaterialTheme {
                    key(generation.value) {
                        RepoReleaseEditorScreen("o", "r", 7, RepoPermission.WRITE, { back++ }, { ownerClicks++ }, { _, _ -> repositoryClicks++ }, { saved++ }, vm)
                    }
                }
            }
        }
    }

    @Test fun toolbarBackAndSystemBackUseTheSameDiscardConfirmation() {
        show()
        compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, back); assertEquals("Unsaved notes", state.value.draft.body); activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_discard)).performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    @Test fun cancelledBackGestureNeverDiscardsOrOpensConfirmation() {
        show()
        compose.runOnIdle {
            assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(50f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
            activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, back); assertEquals("Unsaved notes", state.value.draft.body); assertTrue(state.value.isDirty) }
    }

    @Test fun remountUsesTheSameDraftAndTextEditsGoToViewModel() {
        show()
        compose.onNodeWithText("Unsaved title").performTextReplacement("New title")
        verify { vm.editTitle("New title") }
        compose.runOnIdle { state.value = state.value.copy(draft = state.value.draft.copy(title = "New title")); generation.value++ }
        compose.onNodeWithText("New title").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Unsaved notes", state.value.draft.body) }
    }

    @Test fun loadingAndLoadFailureDoNotExposeEditableBlankDraft() {
        state.value = state.value.copy(phase = ReleaseEditorPhase.LOADING)
        show()
        compose.onNodeWithText(text(R.string.release_tag)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = ReleaseEditorPhase.LOAD_FAILED) }
        compose.onNodeWithText(text(R.string.common_retry)).performClick()
        verify(exactly = 1) { vm.retry() }
        compose.onNodeWithText(text(R.string.release_tag)).assertDoesNotExist()
    }

    @Test fun savingBlocksBackAndHeaderNavigationAndFailureDoesNotExit() {
        state.value = state.value.copy(phase = ReleaseEditorPhase.SAVING)
        show()
        compose.onNodeWithContentDescription(text(R.string.common_back)).assertIsNotEnabled()
        compose.onNodeWithText("o").performClick()
        compose.onNodeWithText("r").performClick()
        compose.runOnIdle { assertEquals(0, ownerClicks); assertEquals(0, repositoryClicks) }
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed(); assertEquals(0, back) }
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = ReleaseEditorPhase.EDITABLE); assertEquals(0, saved) }
        compose.onNodeWithText("Unsaved title").assertIsDisplayed()
    }

    @Test fun cleanDraftReturnsImmediatelyAndSuccessfulSaveUsesCompletionCallbackOnce() {
        state.value = state.value.copy(isDirty = false)
        show()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed(); assertEquals(1, back) }
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertDoesNotExist()
        val release = RepoRelease(7, "R7", "v1", "main", "Title", "", null, true, false, "", null, null, "", emptyList(), null, null)
        compose.runOnIdle { state.value = state.value.copy(savedRelease = release) }
        compose.runOnIdle { assertEquals(1, saved); assertEquals(1, back); state.value = state.value.copy(isGenerating = true) }
        compose.runOnIdle { assertEquals(1, saved) }
    }
}

/** Exercises entry-owned drafts with the production navigator and editor screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepoReleaseEditorNavigationTest {
    private val accounts = AccountSessionRule()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(accounts).around(compose)
    private val repository = mockk<RepoReleaseRepository>()
    private val errors = mockk<ErrorEventBus>(relaxed = true)
    private val updates = mockk<RepoUpdateEventBus>(relaxed = true)
    private lateinit var latestEditor: RepoReleaseEditorViewModel
    private lateinit var navState: GitMobNavState
    private lateinit var navigator: GitMobNavigator
    private lateinit var activity: ComponentActivity
    private lateinit var editorRoute: RepoReleaseEditorRoute
    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(releaseId: Long? = null) {
        editorRoute = RepoReleaseEditorRoute("o", "r", releaseId, RepoPermission.WRITE)
        navState = GitMobNavState(HomeRoute, mutableStateOf(HomeRoute), mapOf(HomeRoute to NavBackStack<NavKey>().apply { add(HomeRoute); add(editorRoute) }))
        navigator = GitMobNavigator(navState, accounts.session.epoch)
        compose.setContent {
            val currentActivity = LocalActivity.current as ComponentActivity
            SideEffect { activity = currentActivity }
            MaterialTheme {
                GitMobNavDisplay(
                    entries = navState.toDecoratedEntries { key ->
                        NavEntry(key) {
                            when (key) {
                                editorRoute -> {
                                    val editor: RepoReleaseEditorViewModel = viewModel {
                                        RepoReleaseEditorViewModel(repository, errors, updates, accounts.session)
                                    }
                                    SideEffect { latestEditor = editor }
                                    val clicks = rememberEntryNavigation(navigator, editorRoute)
                                    RepoReleaseEditorScreen("o", "r", releaseId, RepoPermission.WRITE,
                                        onBack = { clicks.goBack() },
                                        onOwnerClick = { clicks.navigate(ProfileRoute(it)) },
                                        onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                                        onSaved = {}, viewModel = editor)
                                }
                                HomeRoute -> Button(onClick = { navigator.navigate(editorRoute) }) { Text("Open editor") }
                                else -> Button(onClick = { navigator.goBack() }) { Text("Return to editor") }
                            }
                        }
                    },
                    enablePredictiveBack = false,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    popTransitionSpec = { fadeIn() togetherWith fadeOut() },
                    onBack = { navigator.goBack() },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun editDraft(): RepoReleaseEditorViewModel {
        compose.onNode(hasSetTextAction() and hasText(text(R.string.release_title))).performTextReplacement("Unsaved session title")
        compose.runOnIdle { latestEditor.editBody("Unsaved session notes") }
        return latestEditor
    }

    @Test fun ownerNavigationKeepsDraftWithoutDiscardConfirmation() = headerNavigationKeepsDraft("o", ProfileRoute("o"))

    @Test fun repositoryNavigationKeepsDraftWithoutDiscardConfirmation() = headerNavigationKeepsDraft("r", RepoDetailRoute("o", "r"))

    private fun headerNavigationKeepsDraft(header: String, destination: Route) {
        show()
        val original = editDraft()
        compose.onNodeWithText(header).performClick()
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertDoesNotExist()
        compose.onNodeWithText("Return to editor").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(HomeRoute, editorRoute, destination), navState.backStacks.getValue(HomeRoute).toList()) }
        compose.onNodeWithText("Return to editor").performClick()
        compose.onNodeWithText("Unsaved session title").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(original, latestEditor)
            assertEquals("Unsaved session notes", latestEditor.state.value.draft.body)
            assertTrue(latestEditor.state.value.isDirty)
        }
    }

    @Test fun confirmingToolbarBackDiscardsNewReleaseDraftBeforeReentry() {
        show()
        val original = editDraft()
        compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        confirmDiscardAndReopen()
        compose.runOnIdle {
            assertNotSame(original, latestEditor)
            assertEquals(ReleaseEditorDraft(), latestEditor.state.value.draft)
            assertFalse(latestEditor.state.value.isDirty)
        }
    }

    @Test fun confirmingCancelReloadsSavedReleaseInsteadOfDiscardedEdits() {
        val release = RepoRelease(7, "R7", "v1", "main", "Server title", "Server notes", null, false, false, "", null, null, "", emptyList(), null, null)
        coEvery { repository.getRelease("o", "r", 7) } returns ApiResult.Success(release)
        show(7)
        val original = editDraft()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.common_cancel)))
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        confirmDiscardAndReopen()
        compose.onNodeWithText("Server title").assertIsDisplayed()
        compose.runOnIdle {
            assertNotSame(original, latestEditor)
            assertEquals(ReleaseEditorDraft("v1", "main", "Server title", "Server notes", false, false), latestEditor.state.value.draft)
            assertFalse(latestEditor.state.value.isDirty)
        }
        coVerify(exactly = 2) { repository.getRelease("o", "r", 7) }
    }

    @Test fun dismissingDiscardConfirmationKeepsEditorAndDraft() {
        show()
        val original = editDraft()
        compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.onNodeWithText("Unsaved session title").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(HomeRoute, editorRoute), navState.backStacks.getValue(HomeRoute).toList())
            assertSame(original, latestEditor)
            assertTrue(latestEditor.state.value.isDirty)
        }
    }

    @Test fun discardingViaSystemBackCancelsGenerationAndCannotChangeReopenedEditor() {
        val response = CompletableDeferred<ApiResult<ReleaseNotes>>()
        var cancelled = false
        coEvery { repository.generateNotes(any(), any(), any(), any(), any()) } coAnswers {
            try { response.await() } finally { cancelled = true }
        }
        show()
        val original = editDraft()
        compose.runOnIdle { latestEditor.editTag("v2"); latestEditor.generateNotes() }
        compose.runOnIdle { assertTrue(latestEditor.state.value.isGenerating); activity.onBackPressedDispatcher.onBackPressed() }
        confirmDiscardAndReopen()
        compose.runOnIdle {
            assertTrue("The removed entry must cancel its generation", cancelled)
            assertNotSame(original, latestEditor)
            response.complete(ApiResult.Success(ReleaseNotes("Old title", "Old notes")))
        }
        compose.runOnIdle {
            assertEquals(ReleaseEditorDraft(), latestEditor.state.value.draft)
            assertFalse(latestEditor.state.value.isDirty)
            assertFalse(latestEditor.state.value.isGenerating)
        }
    }

    private fun confirmDiscardAndReopen() {
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_discard)).performClick()
        compose.onNodeWithText("Open editor").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(HomeRoute), navState.backStacks.getValue(HomeRoute).toList()) }
        compose.onNodeWithText("Open editor").performClick()
        compose.waitForIdle()
    }
}
