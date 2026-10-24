package com.gitmob.app.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.ui.icons.OcticonBadge
import com.gitmob.app.ui.icons.OcticonName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcticonBadgeTooltipTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `developer label remains visible without long press tooltip while other badges keep it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val developer = context.getString(R.string.common_badge_developer_program)
        val bounty = context.getString(R.string.common_badge_bounty_hunter)
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.padding(64.dp), verticalArrangement = Arrangement.spacedBy(64.dp)) {
                    OcticonBadge(OcticonName.BADGE_DEVELOPER_PROGRAM, developer, enableTooltip = false)
                    OcticonBadge(OcticonName.BADGE_SECURITY_BOUNTY_HUNTER, bounty)
                }
            }
        }
        composeRule.onNodeWithContentDescription(developer).performTouchInput { longClick() }
        composeRule.onAllNodesWithText(developer).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(bounty).performTouchInput { longClick() }
        composeRule.onAllNodesWithText(bounty).assertCountEquals(2)
    }
}
