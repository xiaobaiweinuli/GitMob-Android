package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.ConversationEditSummary
import com.gitmob.app.data.model.SimpleUser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationContentCardTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `quote markdown prefixes every line and leaves reply spacing`() {
        assertEquals("> first\n> second\n\n", quoteMarkdown("first\nsecond"))
    }

    @Test
    fun `quote markdown truncates oversized content`() {
        assertEquals("> abcd\n> …\n\n", quoteMarkdown("abcdef", maxLength = 4))
    }

    @Test
    fun `quote markdown ignores blank content`() {
        assertEquals("", quoteMarkdown("  \n "))
    }

    @Test
    fun `author badges render without wrapping`() {
        composeRule.setContent {
            Box(Modifier.width(240.dp)) {
                ConversationContentCard(
                    author = SimpleUser(
                        login = "a-very-long-user-name",
                        name = null,
                        avatarUrl = null,
                        bio = null,
                    ),
                    createdAt = "2026-08-25T00:00:00Z",
                    bodyHtml = null,
                    url = "",
                    authorAssociation = CommentAuthorAssociation.OWNER,
                    isThreadAuthor = true,
                    onQuoteReply = {},
                    editSummary = ConversationEditSummary(lastEditedAt = "2026-08-25T00:00:00Z"),
                    onEditHistoryClick = {},
                )
            }
        }

        composeRule.onNodeWithText("a-very-long-user-name").assertExists()
        composeRule.onNodeWithText(context.getString(com.gitmob.app.R.string.conversation_edited)).assertExists()
    }

    @Test
    fun `author avatar opens profile callback`() {
        var clickedLogin: String? = null
        composeRule.setContent {
            ConversationContentCard(
                author = SimpleUser("octocat", null, "https://example.com/avatar.png", null),
                createdAt = "2026-08-25T00:00:00Z",
                bodyHtml = null,
                url = "",
                authorAssociation = CommentAuthorAssociation.NONE,
                isThreadAuthor = false,
                onQuoteReply = {},
                onAuthorClick = { clickedLogin = it },
            )
        }

        composeRule.onNodeWithContentDescription("octocat").performClick()
        assertEquals("octocat", clickedLogin)
    }
}
