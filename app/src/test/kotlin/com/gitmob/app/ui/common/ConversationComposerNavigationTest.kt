package com.gitmob.app.ui.common

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.navigation.ConversationComposerRoute
import com.gitmob.app.navigation.ConversationComposerTarget
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationComposerNavigationTest {
    @get:Rule val composeRule = createComposeRule()
    private val state = MutableStateFlow(ConversationComposerUiState())
    private val viewModel = mockk<ConversationComposerViewModel>(relaxed = true) {
        every { state } returns this@ConversationComposerNavigationTest.state
    }
    private val route = ConversationComposerRoute("octo", "repo", 7, ConversationComposerTarget.ISSUE_COMMENT, "I_7")
    private var backCount = 0
    private var submittedCount = 0

    @Test
    fun `submission during STARTED dispatches completion without invoking guarded user back`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    ConversationComposerScreen(route, { backCount++ }, {}, { _, _ -> }, viewModel, { submittedCount++ })
                }
            }
        }
        composeRule.runOnIdle { state.value = state.value.copy(submitted = true) }
        composeRule.runOnIdle {
            assertEquals(1, submittedCount)
            assertEquals(0, backCount)
        }
        // A later state update must not repeat the successful completion event.
        composeRule.runOnIdle { state.value = state.value.copy(text = "sent") }
        composeRule.runOnIdle { assertEquals(1, submittedCount) }
    }

    @Test
    fun `cancel stays on user back path and never dispatches submission`() {
        composeRule.setContent {
            MaterialTheme {
                ConversationComposerScreen(route, { backCount++ }, {}, { _, _ -> }, viewModel, { submittedCount++ })
            }
        }
        val cancel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.common_cancel)
        composeRule.onNodeWithContentDescription(cancel).performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCount)
            assertEquals(0, submittedCount)
        }
    }
}
