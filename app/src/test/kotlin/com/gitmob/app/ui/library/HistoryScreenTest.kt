package com.gitmob.app.ui.library

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.HistoryItem
import com.gitmob.app.data.model.LibraryState
import com.gitmob.app.data.model.RepoCardData
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import com.gitmob.app.testutil.libraryRepositoryStub
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class HistoryScreenTest {
    private val compose = createComposeRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun mixedHistoryOpensGistAndDeletesOnlyItsTypedEntryAfterConfirmation() {
        val gist = HistoryItem.Gist("same", GistListItem("node", "same", "owner", "gist history entry",
            "https://gist.github.com/owner/same", true, false, false, "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z", 0, 0, null, 1, false), 2_000)
        val repo = HistoryItem.Repository("same", RepoCardData("repo", "owner", null, null, "PUBLIC", false, false,
            null, null, null, null, 1, 2, 3, emptyList(), "main"), 1_000)
        val state = MutableStateFlow(LibraryState(loaded = true, history = listOf(gist, repo)))
        val repository = libraryRepositoryStub().also { every { it.observe(any(), any()) } returns state }
        coEvery { repository.deleteGistVisit(any(), "same") } coAnswers {
            state.value = state.value.copy(history = state.value.history.filter { it.key != gist.key })
            ApiResult.Success(Unit)
        }
        val vm = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val accountLibrary = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val opened = mutableListOf<String>()
        compose.setContent { MaterialTheme { HistoryScreen(accountLibrary, {}, { _, _ -> }, {}, { opened += it }, vm) } }
        compose.onNodeWithText("gist history entry").performClick()
        assertEquals(listOf("same"), opened)
        compose.onNodeWithContentDescription(context.getString(R.string.history_delete_description, gist.title)).performClick()
        assertEquals("The delete icon must not navigate", listOf("same"), opened)
        coVerify(exactly = 0) { repository.deleteGistVisit(any(), any()) }
        compose.onNodeWithText(context.getString(R.string.library_confirm)).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.deleteGistVisit(any(), "same") }
        coVerify(exactly = 0) { repository.deleteVisit(any(), any()) }
        compose.onNodeWithText("gist history entry").assertDoesNotExist()
        compose.onNodeWithText("owner/repo").assertExists()
        assertEquals(listOf(repo), state.value.history)
    }

    @Test fun deleteRequiresConfirmationAndKeepsSurvivingCardsAtTheirScrollPosition() {
        val rows = (1..20).map { index ->
            HistoryItem.Repository("R$index", RepoCardData("repo$index", "owner", null, null, "PUBLIC", false, false,
                null, null, null, null, 1, 2, 3, emptyList(), "main"), visitedAt = 1_000L * index)
        }
        val state = MutableStateFlow(LibraryState(loaded = true, history = rows))
        val repository = libraryRepositoryStub().also { every { it.observe(any(), any()) } returns state }
        coEvery { repository.deleteVisit(any(), "R10") } coAnswers {
            state.value = state.value.copy(history = state.value.history.filter { it.id != "R10" })
            ApiResult.Success(Unit)
        }
        val vm = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val accountLibrary = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        compose.setContent { MaterialTheme { HistoryScreen(accountLibrary, {}, { _, _ -> }, {}, {}, vm) } }
        val title = compose.onNodeWithText("owner/repo10")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        val before = title.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The list must actually be scrolled", before < 400f)
        val deleteLabel = context.getString(R.string.history_delete_description, "owner/repo10")
        compose.onNodeWithContentDescription(deleteLabel).performClick()
        compose.onNodeWithText(context.getString(R.string.library_cancel)).performClick()
        coVerify(exactly = 0) { repository.deleteVisit(any(), any()) }
        title.assertExists()
        compose.onNodeWithContentDescription(deleteLabel).performClick()
        coVerify(exactly = 0) { repository.deleteVisit(any(), any()) }
        compose.onNodeWithText(context.getString(R.string.library_confirm)).performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.deleteVisit(any(), "R10") }
        compose.onNodeWithText("owner/repo10").assertDoesNotExist()
        val next = compose.onNodeWithText("owner/repo11").fetchSemanticsNode().boundsInRoot.top
        assertEquals("The next surviving card should occupy the removed card's position", before, next, 2f)
        assertTrue(state.value.loaded)
    }
}
