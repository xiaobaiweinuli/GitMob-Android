package com.gitmob.app.ui.translation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.translation.*
import com.gitmob.app.ui.common.ConversationContentCard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class TranslationControlsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun failureRetryAndServiceActionsShareOneRowAtPhoneWidth() {
        var retries = 0
        var switches = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.padding(16.dp)) {
                    TranslationErrorRow(TranslationProblem.NETWORK, { retries++ }, { switches++ })
                }
            }
        }
        val message = compose.onNodeWithText(context.getString(R.string.translation_error_network))
        val retry = compose.onNodeWithText(context.getString(R.string.common_retry))
        val switch = compose.onNodeWithText(context.getString(R.string.translation_switch))
        val a = message.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val b = retry.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val c = switch.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(a.right <= b.left && b.right <= c.left)
        assertEquals(a.center.y, b.center.y, 1f)
        assertEquals(b.center.y, c.center.y, 1f)
        retry.performClick()
        switch.performClick()
        assertEquals(1, retries)
        assertEquals(1, switches)
    }

    @Test fun commentMenuRetainsSharingAndPermissionBoundActionsWithTranslationEnabled() {
        val vm = mockk<TranslationViewModel>(relaxed = true)
        val state = TranslationUiState(configuration = TranslationConfiguration(TranslationPreferences(enabled = true)))
        var edits = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state, vm) {}) {
                    ConversationContentCard(
                        author = null,
                        createdAt = "2026-11-07",
                        bodyHtml = "<p>Hello</p>",
                        url = "https://github.com/example/repo/issues/1#issuecomment-1",
                        authorAssociation = CommentAuthorAssociation.NONE,
                        isThreadAuthor = false,
                        onQuoteReply = {},
                        onEdit = { edits++ },
                        onDelete = null,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.issue_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.translation_start)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.conversation_share)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.conversation_quote_reply)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_delete)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_edit)).performClick()
        assertEquals(1, edits)
        compose.onNodeWithContentDescription(context.getString(R.string.issue_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.translation_start)).performClick()
        verify(exactly = 1) {
            vm.open(TranslationTarget("https://github.com/example/repo/issues/1#issuecomment-1", "<p>Hello</p>", true))
        }
    }

    @Test fun failedSettingsTestCanSwitchToAnotherService() {
        val preferences = TranslationPreferences(enabled = true)
        val vm = mockk<TranslationViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(TranslationUiState(configuration = TranslationConfiguration(preferences), testProblem = TranslationProblem.NETWORK))
        compose.setContent { MaterialTheme { TranslationSettingsScreen({}, vm) } }
        compose.onNodeWithText(context.getString(R.string.translation_switch)).performScrollTo().performClick()
        // Robolectric reports this ModalBottomSheet window root outside the screen;
        // verify its accessibility click action separately from device touch hit-testing.
        compose.onNodeWithText("Tencent Transmart").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Tencent Transmart").assertDoesNotExist()
        compose.runOnIdle { verify(exactly = 1) { vm.savePreferences(preferences.copy(provider = TranslationProvider.TRANSMART)) } }
    }

    @Test fun allFiveServicesAreAvailableWithoutAMoreSubmenu() {
        var selected: TranslationProvider? = null
        compose.setContent { MaterialTheme { TranslationProviderSheet(TranslationProvider.EDGE, { selected = it }, {}) } }
        compose.onNodeWithText("Microsoft Edge").assertIsDisplayed()
        compose.onNodeWithText("Tencent Transmart").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_baidu)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_youdao_open)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_custom)).assertIsDisplayed()
        compose.onNodeWithText("MyMemory").assertDoesNotExist()
        compose.onNodeWithText("Google gtx").assertDoesNotExist()
        compose.onNodeWithText("Tencent Transmart").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(TranslationProvider.TRANSMART, selected) }
    }
    @Test fun nativeTitlePublishesAvailableSpansWhileStillRunning() {
        val target = TranslationTarget("thread:title", "Hello https://example.test Goodbye")
        val document = TranslationDocuments.create(target, TranslationProvider.EDGE)
        val entry = TranslationEntry(document = document, phase = TranslationPhase.REQUESTING)
        val state = mutableStateOf(TranslationUiState(entries = mapOf(target.identity to entry)))
        val vm = mockk<TranslationViewModel>(relaxed = true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state.value, vm) {}) {
                    TranslationText(target, showStatus = true)
                }
            }
        }
        compose.onNodeWithText(target.content).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(entries = mapOf(target.identity to entry.copy(translations = mapOf(0 to "你好 "))))
        }
        compose.onNodeWithText("你好 https://example.test").assertIsDisplayed()
        compose.onNodeWithText(target.content).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_working, 1, 2)).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(entries = mapOf(target.identity to entry.copy(translations = mapOf(0 to "你好 ", 1 to " 再见"))))
        }
        compose.onNodeWithText("你好 https://example.test 再见").assertIsDisplayed()
    }

    @Test fun titleResultRespectsBilingualModeAndIgnoresBodyFailure() {
        val title = TranslationTarget("thread:title", "Original title")
        val body = TranslationTarget("thread", "Original body", html = true)
        val entry = TranslationEntry(
            document = TranslationDocuments.create(title, TranslationProvider.EDGE),
            translations = mapOf(0 to "译文标题"),
        )
        val state = mutableStateOf(TranslationUiState(entries = mapOf(
            title.identity to entry,
            body.identity to TranslationEntry(phase = TranslationPhase.REQUESTING),
        )))
        val vm = mockk<TranslationViewModel>(relaxed = true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state.value, vm) {}) {
                    TranslationText(title, showStatus = true)
                }
            }
        }
        compose.onNodeWithText(title.content).assertIsDisplayed()
        compose.onNodeWithText("译文标题").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_cancel)).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(entries = mapOf(
                title.identity to entry.copy(preferences = entry.preferences.copy(bilingual = false)),
                body.identity to TranslationEntry(problem = TranslationProblem.LIMIT),
            ))
        }
        compose.onNodeWithText(title.content).assertDoesNotExist()
        compose.onNodeWithText("译文标题").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_error_limit)).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(entries = state.value.entries - title.identity) }
        compose.onNodeWithText(title.content).assertIsDisplayed()
        compose.onNodeWithText("译文标题").assertDoesNotExist()
    }

    @Test fun incompleteLongTitleDoesNotDuplicateOriginalAsTranslation() {
        val target = TranslationTarget("thread:title", "A long title ".repeat(400))
        val document = TranslationDocuments.create(target, TranslationProvider.EDGE)
        assertTrue(document.parts.size > 1)
        val state = TranslationUiState(entries = mapOf(target.identity to TranslationEntry(
            document = document, translations = mapOf(0 to "部分译文"), phase = TranslationPhase.REQUESTING,
        )))
        val vm = mockk<TranslationViewModel>(relaxed = true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state, vm) {}) {
                    TranslationText(target)
                }
            }
        }
        compose.onAllNodesWithText(target.content).assertCountEquals(1)
        compose.onNodeWithText("部分译文").assertIsDisplayed()
    }

    @Test fun failedTitleKeepsOriginalAndRetriesOnlyTitle() {
        val target = TranslationTarget("thread:title", "Original title")
        val state = TranslationUiState(entries = mapOf(target.identity to TranslationEntry(problem = TranslationProblem.NETWORK)))
        val vm = mockk<TranslationViewModel>(relaxed = true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(state, vm) {}) {
                    TranslationText(target, showStatus = true)
                }
            }
        }
        compose.onNodeWithText(target.content).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_error_network)).assertIsDisplayed()
        val errorBounds = compose.onNodeWithText(context.getString(R.string.translation_error_network)).fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithText(target.content).fetchSemanticsNode().boundsInRoot
        assertTrue(errorBounds.bottom <= contentBounds.top)
        compose.onNodeWithText(context.getString(R.string.common_retry)).performClick()
        verify(exactly = 1) { vm.retry(target) }
    }


    @Test fun cancelledPreparationShowsRetryInsteadOfCompletedFooter() {
        val target = TranslationTarget("title", "Hello")
        val state = TranslationUiState(entries = mapOf(target.identity to TranslationEntry(phase = TranslationPhase.CANCELLED)))
        val vm = mockk<TranslationViewModel>(relaxed = true)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalTranslationController provides TranslationController(state, vm) {}) {
                TranslationText(target, showStatus = true)
            }
        } }
        compose.onNodeWithText(context.getString(R.string.translation_cancelled)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.translation_options)).assertDoesNotExist()
    }
}
