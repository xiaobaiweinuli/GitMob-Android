package com.gitmob.app.core.error

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorBannerHostTest {
    @get:Rule val composeRule = createComposeRule()
    private val session = AccountSession()
    private val bus = ErrorEventBus()
    private fun message(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `banner retains its content through exit then disappears`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialExpressiveTheme { ErrorBannerHost(bus, session.epoch) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { runBlocking { bus.emitNotice(R.string.common_retry, session.epoch) } }
        composeRule.mainClock.advanceTimeBy(2_980)
        composeRule.onNodeWithText(message(R.string.common_retry)).assertExists()
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.onNodeWithText(message(R.string.common_retry)).assertExists()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText(message(R.string.common_retry)).assertDoesNotExist()
    }

    @Test
    fun `account change clears old banner and accepts a new session event`() {
        var epoch by mutableStateOf(session.epoch)
        composeRule.setContent { MaterialExpressiveTheme { ErrorBannerHost(bus, epoch) } }
        composeRule.runOnIdle { runBlocking { bus.emitNotice(R.string.common_retry, epoch) } }
        composeRule.onNodeWithText(message(R.string.common_retry)).assertExists()
        composeRule.runOnIdle {
            session.replace(emptyList(), null)
            epoch = session.epoch
        }
        composeRule.onNodeWithText(message(R.string.common_retry)).assertDoesNotExist()
        composeRule.runOnIdle { runBlocking { bus.emitNotice(R.string.common_cancel, epoch) } }
        composeRule.onNodeWithText(message(R.string.common_cancel)).assertExists()
        composeRule.onNodeWithText(message(R.string.common_retry)).assertDoesNotExist()
    }
}
