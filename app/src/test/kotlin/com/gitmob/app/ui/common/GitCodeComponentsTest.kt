package com.gitmob.app.ui.common

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gitmob.app.data.model.RepoCommitSummary
import com.gitmob.app.data.model.RepoGitActor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitCodeComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `commit row shows avatar and opens author profile`() {
        var clickedLogin: String? = null
        composeRule.setContent {
            GitCommitRow(
                commit = RepoCommitSummary(
                    oid = "1234567890",
                    abbreviatedOid = "1234567",
                    headline = "Fix docs",
                    body = "",
                    authoredDate = "2026-08-25T00:00:00Z",
                    committedDate = "2026-08-25T00:00:00Z",
                    author = RepoGitActor("octocat", "The Octocat", null, "https://example.com/avatar.png", null),
                    committer = null,
                    additions = 0,
                    deletions = 0,
                    changedFiles = null,
                ),
                onClick = {},
                onAuthorClick = { clickedLogin = it },
            )
        }

        composeRule.onNodeWithText("Fix docs").assertExists()
        composeRule.onNodeWithContentDescription("octocat").performClick()
        assertEquals("octocat", clickedLogin)
    }
}
