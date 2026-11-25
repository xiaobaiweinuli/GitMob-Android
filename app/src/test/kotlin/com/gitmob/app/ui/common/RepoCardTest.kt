package com.gitmob.app.ui.common

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoCardData
import com.gitmob.app.ui.theme.GitMobTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val repo = RepoCardData(
        name = "repository", ownerLogin = "owner", description = "Repository description",
        homepageUrl = "https://example.com", visibility = "PRIVATE", isArchived = true,
        isFork = true, forkedFromOwner = "upstream", forkedFromName = "source",
        languageName = "Kotlin", languageColor = "#A97BFF", stargazerCount = 12345,
        forkCount = 6789, openIssueCount = 321, topics = listOf("android", "compose"),
        defaultBranchName = "main",
    )

    @Test
    fun nestedLinksAndTrailingActionDoNotOpenRepository() {
        var opens = 0
        var source: Pair<String, String>? = null
        var homepage: String? = null
        var actions = 0
        composeRule.setContent {
            GitMobTheme {
                RepoCard(
                    repo = repo,
                    onClick = { opens++ },
                    onForkSourceClick = { owner, name -> source = owner to name },
                    onHomepageClick = { homepage = it },
                    showOwner = true,
                    titleAction = { IconButton(onClick = { actions++ }) { Text("Action") } },
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_open_source_repo)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.common_open_homepage)).performClick()
        composeRule.onNodeWithText("Action").performClick()
        composeRule.runOnIdle {
            assertEquals("upstream" to "source", source)
            assertEquals(repo.homepageUrl, homepage)
            assertEquals(1, actions)
            assertEquals(0, opens)
        }
        composeRule.onNodeWithText("owner/repository", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun publicRepositoryKeepsBranchAtEndOfStatisticsRow() {
        composeRule.setContent {
            GitMobTheme {
                RepoCard(
                    repo = repo.copy(
                        visibility = "PUBLIC", isArchived = false, homepageUrl = null,
                        stargazerCount = 22, forkCount = 2, openIssueCount = 3,
                    ),
                    onClick = {}, onForkSourceClick = { _, _ -> }, onHomepageClick = {},
                )
            }
        }
        val branch = composeRule.onNodeWithText("main", useUnmergedTree = true)
        branch.assertIsDisplayed()
        val branchBounds = branch.fetchSemanticsNode().boundsInRoot
        for (count in listOf("22", "2", "3")) {
            val stat = composeRule.onNodeWithText(count, useUnmergedTree = true)
            stat.assertIsDisplayed()
            val statBounds = stat.fetchSemanticsNode().boundsInRoot
            assertTrue(statBounds.right <= branchBounds.left)
            assertTrue(statBounds.top < branchBounds.bottom && branchBounds.top < statBounds.bottom)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceBounds = composeRule.onNodeWithContentDescription(
            context.getString(R.string.common_open_source_repo), useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val titleBounds = composeRule.onNodeWithText("repository", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val descriptionBounds = composeRule.onNodeWithText("Repository description", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.bottom <= sourceBounds.top)
        assertTrue(sourceBounds.bottom <= descriptionBounds.top)
        assertTrue(descriptionBounds.bottom <= branchBounds.top)
    }

    @Test
    fun narrowLargeTextKeepsStatusBelowStatisticsAndTopicsScrollable() {
        val topics = List(10) { "topic-" + it }
        val branchName = "feature/repository-list-with-a-long-branch-name"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                GitMobTheme {
                    Box(Modifier.width(280.dp)) {
                        RepoCard(
                            repo = repo.copy(
                                homepageUrl = null, isFork = false, topics = topics,
                                defaultBranchName = branchName,
                            ),
                            onClick = {}, onForkSourceClick = { _, _ -> }, onHomepageClick = {},
                        )
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val privateTag = composeRule.onNodeWithText(context.getString(R.string.common_private), useUnmergedTree = true)
        privateTag.assertIsDisplayed()
        val statusBounds = privateTag.fetchSemanticsNode().boundsInRoot
        val branch = composeRule.onNodeWithText(branchName, useUnmergedTree = true)
        branch.assertIsDisplayed()
        val branchBounds = branch.fetchSemanticsNode().boundsInRoot
        for (count in listOf("12345", "6789", "321")) {
            val stat = composeRule.onNodeWithText(count, useUnmergedTree = true)
            stat.assertIsDisplayed()
            val statBounds = stat.fetchSemanticsNode().boundsInRoot
            assertTrue(statBounds.bottom <= statusBounds.top)
            assertTrue(statBounds.right <= branchBounds.left)
        }
        composeRule.onNodeWithText("topic-9", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
