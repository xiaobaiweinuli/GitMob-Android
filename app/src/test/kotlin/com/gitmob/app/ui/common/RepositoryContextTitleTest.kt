package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `owner is above repository and both links remain independently clickable`() {
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

        val ownerNode = composeRule.onNodeWithText("octocat").assertHasClickAction()
        val repositoryNode = composeRule.onNodeWithText("hello-world").assertHasClickAction()
        ownerNode.performClick()
        repositoryNode.performClick()
        composeRule.onNodeWithText(" · Issues").assertIsDisplayed().assertHasNoClickAction()

        assertEquals(listOf("owner:octocat", "repo:octocat/hello-world"), clicks)
        assertTrue(
            ownerNode.fetchSemanticsNode().boundsInRoot.top <
                repositoryNode.fetchSemanticsNode().boundsInRoot.top,
        )
    }

    @Test
    fun `long owner and repository title have independent horizontal scrolling`() {
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

        composeRule.onAllNodes(hasScrollAction()).assertCountEquals(2)
        composeRule.onNodeWithText("a-very-long-repository-owner").assertExists()
        composeRule.onNodeWithText("an-even-longer-repository-name").assertExists()
        composeRule.onNodeWithText(" · Commit history").assertExists()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `navigation and action stay vertically centered beside two line title`() {
        composeRule.setContent {
            Box(Modifier.width(320.dp)) {
                TopAppBar(
                    title = {
                        RepositoryContextTitle(
                            owner = "octocat",
                            repository = "hello-world",
                            pageTitle = "Issues",
                            onOwnerClick = {},
                            onRepositoryClick = { _, _ -> },
                        )
                    },
                    navigationIcon = { IconButton(onClick = {}) { Text("Back") } },
                    actions = { IconButton(onClick = {}) { Text("Action") } },
                )
            }
        }

        val ownerBounds = composeRule.onNodeWithText("octocat").fetchSemanticsNode().boundsInRoot
        val repositoryBounds = composeRule.onNodeWithText("hello-world").fetchSemanticsNode().boundsInRoot
        val titleCenterY = (ownerBounds.top + repositoryBounds.bottom) / 2f
        val backBounds = composeRule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot
        val actionBounds = composeRule.onNodeWithText("Action").fetchSemanticsNode().boundsInRoot

        assertEquals(titleCenterY, backBounds.center.y, 2f)
        assertEquals(titleCenterY, actionBounds.center.y, 2f)
    }
}
