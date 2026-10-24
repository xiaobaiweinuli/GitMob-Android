package com.gitmob.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.GistFilePreview
import com.gitmob.app.data.model.GistListItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GistCardTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `复刻来源只显示用户并用ID跳转`() {
        val gist = GistListItem(
            id = "node-id",
            apiName = "fork-id",
            ownerLogin = "viewer",
            description = null,
            url = "https://gist.github.com/viewer/fork-id",
            isPublic = true,
            isFork = true,
            isOwnedByViewer = true,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            stargazerCount = 0,
            commentCount = 0,
            previewFile = GistFilePreview("fork.txt", null, 0, false, false, "Text", null),
            fileCount = 1,
            isFileCountCapped = false,
            sourceId = "source-id",
            sourceOwnerLogin = "octocat",
            sourceUrl = "https://gist.github.com/octocat/source-id",
        )
        var openedSource: String? = null
        composeRule.setContent {
            MaterialTheme {
                GistCard(gist = gist, onClick = {}, onForkSourceClick = { openedSource = it })
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.gist_forked_from, "octocat"),
        ).performClick()

        assertEquals("source-id", openedSource)
        composeRule.onNodeWithText("source-id").assertDoesNotExist()
    }
}
