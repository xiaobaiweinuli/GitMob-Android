package com.gitmob.app.ui.reporeleases

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalView
import com.gitmob.app.ui.common.awaitSoraEditor
import com.gitmob.app.ui.common.awaitSoraLayout
import org.robolectric.annotation.GraphicsMode
import android.content.Context
import com.gitmob.app.ui.common.SoraEditorState
import com.gitmob.app.core.markdown.CommonMarkRenderer
import androidx.lifecycle.SavedStateHandle
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoReleaseEditorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = MutableStateFlow(RepoReleaseEditorUiState(phase = ReleaseEditorPhase.EDITABLE, draft = ReleaseEditorDraft(tag = "v1", title = "Unsaved title"), capabilities = RepoPermission.WRITE.toCapabilities()))
    private val bodyState = SoraEditorState("Unsaved notes")
    private val vm = mockk<RepoReleaseEditorViewModel>(relaxed = true) {
        every { state } returns this@RepoReleaseEditorScreenTest.state
        every { bodyState } returns this@RepoReleaseEditorScreenTest.bodyState
        every { isDirty } returns true
    }
    private lateinit var root: View
    private lateinit var activity: ComponentActivity
    private val generation = mutableStateOf(0)
    private var back = 0
    private var saved = 0
    private var ownerClicks = 0
    private var repositoryClicks = 0
    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)
    private fun show() {
        compose.setContent {
            root = LocalView.current
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

    private fun scrollToBody() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.conversation_edit_tab)))
    }

    @Test fun focusedReleaseBodyHandsOffFocusBeforeSwitchingPreviewAndRetainsSelection() {
        every { vm.selectBodyEditorTab(any()) } answers {
            assertFalse("Native focus must be released before the lazy item changes", root.findFocus() is com.gitmob.app.ui.common.SoraEditingView)
            val tab = firstArg<com.gitmob.app.ui.common.MarkdownEditorTab>()
            state.value = state.value.copy(bodyEditor = state.value.bodyEditor.copy(
                selectedTab = tab, previewHtml = "<p>Unsaved notes</p>"))
        }
        show()
        scrollToBody()
        repeat(3) {
            val editor = compose.awaitSoraEditor(root)
            compose.runOnIdle {
                editor.requestFocus()
                editor.setSelection(0, 4)
                assertTrue(editor.isFocused)
            }
            compose.onNodeWithText(text(R.string.conversation_preview_tab)).performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(editor.isReleased)
                assertEquals("Unsaved notes", bodyState.snapshot())
                assertEquals(4, bodyState.selection.start)
            }
            compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.conversation_edit_tab)).performScrollTo().performClick()
            val restored = compose.awaitSoraEditor(root, editor)
            compose.runOnIdle { assertEquals(4, restored.cursor.leftColumn) }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.runOnIdle {
            editor.requestFocus()
            editor.setSelectionRegion(0, 0, 0, 7)
        }
        compose.onAllNodesWithContentDescription(text(R.string.conversation_tool_bold)).assertCountEquals(1)
        compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold)).performClick()
        compose.runOnIdle {
            assertEquals("**Unsaved** notes", bodyState.snapshot())
            assertEquals(2, editor.cursor.leftColumn)
            assertEquals(9, editor.cursor.rightColumn)
        }
        compose.onNodeWithContentDescription(text(R.string.editor_undo)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Unsaved notes", bodyState.snapshot()) }
    }

    @Test fun releaseBodyUsesContentHeightAndThePageOwnsScrolling() {
        show()
        scrollToBody()
        val editor = compose.awaitSoraEditor(root)
        val initialHeight = compose.runOnIdle {
            val embedded = requireNotNull(editor.embeddedState)
            assertTrue(embedded.contentHeight >= editor.rowHeight * 6)
            embedded.contentHeight
        }
        val toolbarBounds = compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold)).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            bodyState.edit { replace(0, length, (1..100).joinToString("\n") { "release note $it" }) }
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertTrue(requireNotNull(editor.embeddedState).contentHeight > initialHeight)
            assertEquals(editor.layout.layoutHeight, editor.embeddedState!!.contentHeight)
            assertTrue(editor.height <= root.height)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.common_save)))
        compose.onNodeWithText(text(R.string.common_save)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold)).assertIsDisplayed()
        assertEquals(toolbarBounds, compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold)).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun releaseOptionsPrecedeBodyAndTheirLabelsToggleIndependently() {
        state.value = state.value.copy(draft = state.value.draft.copy(draft = false))
        every { vm.editDraft(any()) } answers {
            state.value = state.value.copy(draft = state.value.draft.copy(draft = firstArg()))
        }
        every { vm.editPrerelease(any()) } answers {
            state.value = state.value.copy(draft = state.value.draft.copy(prerelease = firstArg()))
        }
        show()
        val draftOption = compose.onNodeWithText(text(R.string.release_draft))
        val prereleaseOption = compose.onNodeWithText(text(R.string.release_prerelease))
        val editTab = compose.onNodeWithText(text(R.string.conversation_edit_tab))
        draftOption.performScrollTo().assertIsOff().performClick().assertIsOn()
        prereleaseOption.assertIsOff().performClick().assertIsOn()
        draftOption.assertIsOn()
        scrollToBody()
        assertTrue(draftOption.fetchSemanticsNode().boundsInRoot.bottom <= editTab.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(prereleaseOption.fetchSemanticsNode().boundsInRoot.bottom <= editTab.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(text(R.string.release_generate_notes)).performClick()
        verify(exactly = 1) { vm.generateNotes() }
        compose.runOnIdle { state.value = state.value.copy(phase = ReleaseEditorPhase.SAVING) }
        draftOption.assertIsNotEnabled()
        prereleaseOption.assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold)).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun bottomToolbarAvoidsKeyboardOnceAndTheBodyCaretStaysAboveIt() {
        bodyState.loadText((1..80).joinToString("\n") { "release note $it" })
        show()
        scrollToBody()
        val editor = compose.awaitSoraEditor(root)
        fun applyInsets(keyboardHeight: Int) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                    .setVisible(WindowInsetsCompat.Type.ime(), keyboardHeight > 0).build())
            }
            compose.awaitSoraLayout(editor)
        }
        applyInsets(0)
        compose.runOnIdle {
            editor.requestFocus()
            editor.setSelection(45, 5)
        }
        compose.awaitSoraLayout(editor)
        val toolbar = compose.onNodeWithContentDescription(text(R.string.conversation_tool_bold))
        val initialBounds = toolbar.fetchSemanticsNode().boundsInRoot
        val selection = bodyState.selection
        for (keyboardHeight in listOf(300, 420, 0)) {
            applyInsets(keyboardHeight)
            toolbar.assertIsDisplayed()
            val bounds = toolbar.fetchSemanticsNode().boundsInRoot
            assertEquals("Toolbar displacement for keyboard $keyboardHeight in root ${root.width}x${root.height}: $initialBounds -> $bounds",
                (keyboardHeight - 24).coerceAtLeast(0).toFloat(), initialBounds.top - bounds.top, 2f)
            compose.runOnIdle {
                val editorTop = IntArray(2).also(editor::getLocationInWindow)[1]
                val rootTop = IntArray(2).also(root::getLocationInWindow)[1]
                val caretBottom = editorTop - rootTop + editor.layout.getCharLayoutOffset(45, 5)[0] - editor.offsetY
                assertTrue("Caret $caretBottom must stay above toolbar $bounds", caretBottom <= bounds.top)
                assertEquals(selection, bodyState.selection)
            }
        }
    }

    @Test fun toolbarBackAndSystemBackUseTheSameDiscardConfirmation() {
        show()
        compose.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        compose.onNodeWithText(text(R.string.conversation_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, back); assertEquals("Unsaved notes", bodyState.snapshot()); activity.onBackPressedDispatcher.onBackPressed() }
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
        compose.runOnIdle { assertEquals(0, back); assertEquals("Unsaved notes", bodyState.snapshot()); assertTrue(vm.isDirty) }
    }

    @Test fun remountUsesTheSameDraftAndTextEditsGoToViewModel() {
        show()
        compose.onNodeWithText("Unsaved title").performTextReplacement("New title")
        verify { vm.editTitle("New title") }
        compose.runOnIdle { state.value = state.value.copy(draft = state.value.draft.copy(title = "New title")); generation.value++ }
        compose.onNodeWithText("New title").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Unsaved notes", bodyState.snapshot()) }
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
        every { vm.isDirty } returns false
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
                                        RepoReleaseEditorViewModel(repository, errors, updates, accounts.session, SavedStateHandle(), CommonMarkRenderer())
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
            assertEquals("Unsaved session notes", latestEditor.bodyState.snapshot())
            assertTrue(latestEditor.isDirty)
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
            assertFalse(latestEditor.isDirty)
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
            assertEquals(ReleaseEditorDraft("v1", "main", "Server title", false, false), latestEditor.state.value.draft)
            assertEquals("Server notes", latestEditor.bodyState.snapshot())
            assertFalse(latestEditor.isDirty)
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
            assertTrue(latestEditor.isDirty)
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
            assertFalse(latestEditor.isDirty)
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
