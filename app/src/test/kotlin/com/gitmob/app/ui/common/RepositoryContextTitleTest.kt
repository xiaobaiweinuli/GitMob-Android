package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryContextTitleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `owner and repository are independently clickable while page title is not`() {
        val clicks = mutableListOf<String>()

        composeRule.setContent {
            RepositoryContextTitle(
                owner = "octocat",
                repository = "hello-world",
                pageTitle = "Issues",
                onOwnerClick = { clicks += "owner:$it" },
                onRepositoryClick = { owner, repository -> clicks += "repo:$owner/$repository" },
            )
        }

        composeRule.onNodeWithText("octocat").assertHasClickAction().performClick()
        composeRule.onNodeWithText("hello-world").assertHasClickAction().performClick()
        composeRule.onNodeWithText(" · Issues").assertIsDisplayed().assertHasNoClickAction()

        assertEquals(listOf("owner:octocat", "repo:octocat/hello-world"), clicks)
    }

    @Test
    fun `long repository context remains horizontally scrollable`() {
        composeRule.setContent {
            Box(Modifier.width(180.dp)) {
                RepositoryContextTitle(
                    owner = "a-very-long-repository-owner",
                    repository = "an-even-longer-repository-name",
                    pageTitle = "Commit history",
                    onOwnerClick = {},
                    onRepositoryClick = { _, _ -> },
                )
            }
        }

        composeRule.onNode(hasScrollAction()).assertExists()
        composeRule.onNodeWithText("a-very-long-repository-owner").assertExists()
        composeRule.onNodeWithText("an-even-longer-repository-name").assertExists()
    }
}
