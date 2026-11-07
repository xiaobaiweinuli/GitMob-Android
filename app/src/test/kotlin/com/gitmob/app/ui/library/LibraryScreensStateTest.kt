package com.gitmob.app.ui.library

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.FavoriteGroup
import com.gitmob.app.data.model.LibraryState
import com.gitmob.app.data.model.HistoryItem
import com.gitmob.app.data.model.RepoCardData
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.libraryRepositoryStub
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class LibraryScreensStateTest {
    private val compose = createComposeRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun delayedSummaryIsNotMistakenForDeletedGroupOrEmptyFavorites() {
        val repository = libraryRepositoryStub()
        val summary = CompletableDeferred<List<FavoriteGroup>>()
        every { repository.observeGroups(any()) } returns flow { emit(summary.await()) }
        val account = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val page = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        compose.setContent {
            MaterialTheme { FavoriteGroupScreen(account, "work", {}, { _, _ -> }, {}, {}, "Work", page) }
        }
        compose.onNodeWithText("Work").assertExists()
        compose.onNodeWithText(context.getString(R.string.library_group_missing)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_no_favorites)).assertDoesNotExist()
        compose.runOnIdle { summary.complete(listOf(FavoriteGroup("work", "Work", 0, 1))) }
        compose.onNodeWithText(context.getString(R.string.library_no_favorites)).assertExists()
        compose.onNodeWithText(context.getString(R.string.library_group_missing)).assertDoesNotExist()
    }

    @Test fun historyReadFailureCanRetryWhileKeepingCardsAndResumeUpdates() {
        val repository = libraryRepositoryStub()
        val card = RepoCardData("retained", "owner", null, null, "PUBLIC", false, false, null, null, null, null, 1, 2, 3, emptyList(), "main")
        val initial = LibraryState(loaded = true, history = listOf(HistoryItem.Repository("repo", card, 1_000)))
        val result = MutableStateFlow(Result.success(initial))
        val retryReady = CompletableDeferred<Unit>()
        var subscriptions = 0
        every { repository.observe(any(), any()) } returns flow {
            subscriptions++
            if (subscriptions == 2) retryReady.await()
            emitAll(result.map { it.getOrThrow() })
        }
        val account = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val page = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        compose.setContent { MaterialTheme { HistoryScreen(account, {}, { _, _ -> }, {}, {}, page) } }
        val retry = compose.onNodeWithText(context.getString(R.string.common_retry))
        compose.onNodeWithText("owner/retained").assertIsDisplayed()
        retry.assertDoesNotExist()
        compose.runOnIdle { result.value = Result.failure(IllegalStateException("read failed")) }
        compose.onNodeWithText("owner/retained").assertIsDisplayed()
        retry.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, subscriptions) }
        compose.onNodeWithText("owner/retained").assertIsDisplayed()
        retry.assertDoesNotExist()
        compose.runOnIdle {
            result.value = Result.success(initial)
            retryReady.complete(Unit)
        }
        compose.onNodeWithText("owner/retained").assertIsDisplayed()
        retry.assertDoesNotExist()
        compose.runOnIdle {
            result.value = Result.success(initial.copy(history = listOf(HistoryItem.Repository("repo", card.copy(name = "updated"), 2_000))))
        }
        compose.onNodeWithText("owner/updated").assertIsDisplayed()
        compose.runOnIdle { result.value = Result.failure(IllegalStateException("read failed again")) }
        retry.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(3, subscriptions) }
        retry.assertIsDisplayed()
        compose.onNodeWithText("owner/updated").assertIsDisplayed()
    }

    @Test fun groupReadFailureAndRetryKeepScrolledGroupsVisible() {
        val repository = libraryRepositoryStub()
        val groups = List(24) { FavoriteGroup("group-$it", "Group $it", 0, it) }
        val result = MutableStateFlow(Result.success(groups))
        every { repository.observeGroups(any()) } returns result.map { it.getOrThrow() }
        val account = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val page = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        compose.setContent { MaterialTheme { FavoritesScreen(account, {}, {}, page) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Group 12"))
        compose.onNodeWithText("Group 12").assertIsDisplayed()
        val retry = compose.onNodeWithText(context.getString(R.string.common_retry))
        compose.runOnIdle { result.value = Result.failure(IllegalStateException("groups failed")) }
        retry.assertIsDisplayed()
        compose.onNodeWithText("Group 12").assertIsDisplayed()
        compose.onNodeWithText("Group 0").assertDoesNotExist()
        compose.runOnIdle { result.value = Result.success(groups) }
        retry.performClick()
        retry.assertDoesNotExist()
        compose.onNodeWithText("Group 12").assertIsDisplayed()
        compose.onNodeWithText("Group 0").assertDoesNotExist()
    }

    @Test fun firstHistoryReadFailureShowsRetryWithoutAnEmptyState() {
        val repository = libraryRepositoryStub()
        var fail = true
        every { repository.observe(any(), any()) } returns flow {
            check(!fail) { "read failed" }
            emit(LibraryState(loaded = true))
        }
        val account = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val page = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        compose.setContent { MaterialTheme { HistoryScreen(account, {}, { _, _ -> }, {}, {}, page) } }
        compose.onNodeWithText(context.getString(R.string.library_no_history)).assertDoesNotExist()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.common_retry)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.library_no_history)).assertIsDisplayed()
    }
    @Test fun mixedFavoritesWithSameIdNavigateAndOfferGistMoveAction() {
        val repository = libraryRepositoryStub()
        val card = RepoCardData("repo", "owner", null, null, "PUBLIC", false, false, null, null, null, null, 1, 2, 3, emptyList(), "main")
        val gist = GistListItem("gist-node", "same", "owner", null, "https://gist.github.com/owner/same",
            true, false, false, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 0, 0, null, 1, false)
        every { repository.observeGroups(any()) } returns flowOf(listOf(FavoriteGroup("work", "Work", 2, 1)))
        every { repository.observe(any(), any()) } returns flowOf(LibraryState(loaded = true, favorites = listOf(
            FavoriteItem.Repository("same", card, "work"), FavoriteItem.Gist(gist, "work"),
        )))
        val account = AccountLibraryViewModel(repository, ErrorEventBus(), accounts.session)
        val page = LibraryViewModel(repository, ErrorEventBus(), accounts.session)
        var openedGist: String? = null
        compose.setContent {
            MaterialTheme { FavoriteGroupScreen(account, "work", {}, { _, _ -> }, {}, { openedGist = it }, "Work", page) }
        }
        compose.onNodeWithText("owner/repo").assertIsDisplayed()
        compose.onNodeWithText("owner / same").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("same", openedGist) }
        compose.onNodeWithText(context.getString(R.string.library_edit)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.favorites_move))[1].performClick()
        compose.onNodeWithText(context.getString(R.string.favorites_move_title)).assertIsDisplayed()
        compose.onNodeWithText("owner/same").assertIsDisplayed()
    }

}
