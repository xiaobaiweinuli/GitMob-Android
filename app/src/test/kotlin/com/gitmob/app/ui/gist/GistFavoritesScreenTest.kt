package com.gitmob.app.ui.gist

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.testutil.libraryRepositoryStub
import com.gitmob.app.ui.library.AccountLibraryViewModel
import com.gitmob.app.ui.library.LibraryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GistFavoritesScreenTest {
    private val compose = createComposeRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<GistRepository>()
    private val library = libraryRepositoryStub()
    private lateinit var detailModel: GistDetailViewModel
    private lateinit var accountModel: AccountLibraryViewModel
    private lateinit var pageModel: LibraryViewModel

    @After fun tearDown() {
        if (::detailModel.isInitialized) detailModel.viewModelScope.cancel()
        if (::accountModel.isInitialized) accountModel.viewModelScope.cancel()
        if (::pageModel.isInitialized) pageModel.viewModelScope.cancel()
    }

    @Test fun otherUsersGistCanBeSavedToSelectedFavoriteGroup() {
        val detail = detail("other")
        coEvery { library.saveFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
        render(detail)
        openMenu()
        node(R.string.favorites_add).assertIsDisplayed().performClick()
        compose.onNodeWithText("Work").performClick()
        node(R.string.favorites_add).performClick()

        coVerify(exactly = 1) {
            library.saveFavorite(accounts.session.epoch, FavoriteItem.Gist(detail.cardData()), "work")
        }
        coVerify(exactly = 0) { repository.setStarred(any(), any()) }
        node(R.string.favorites_choose_group).assertDoesNotExist()
    }

    @Test fun existingGistFavoriteCanMoveAndRequiresConfirmationToRemove() {
        val detail = detail("viewer")
        val existing = FavoriteItem.Gist(detail.cardData(), "work")
        coEvery { library.saveFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
        coEvery { library.removeFavorite(any(), any()) } returns ApiResult.Success(Unit)
        render(detail, existing = existing)
        openMenu()
        node(R.string.favorites_manage).performClick()
        node(R.string.favorites_default_group).performClick()
        node(R.string.library_save).performClick()
        coVerify(exactly = 1) {
            library.saveFavorite(accounts.session.epoch, FavoriteItem.Gist(detail.cardData()), DEFAULT_FAVORITE_GROUP)
        }

        openMenu()
        node(R.string.favorites_manage).performClick()
        node(R.string.favorites_remove).performClick()
        coVerify(exactly = 0) { library.removeFavorite(any(), any()) }
        node(R.string.library_confirm).performClick()
        coVerify(exactly = 1) { library.removeFavorite(accounts.session.epoch, FavoriteItem.Gist(detail.cardData())) }
        coVerify(exactly = 0) { repository.deleteGist(any()) }
    }

    @Test fun disablingFavoritesHidesBothFavoriteMenuActions() {
        render(detail("viewer"), enabled = false)
        openMenu()
        node(R.string.favorites_add).assertDoesNotExist()
        node(R.string.favorites_manage).assertDoesNotExist()
        node(R.string.gist_download_zip).assertIsDisplayed()
        node(R.string.gist_edit_description).assertIsDisplayed()
    }

    private fun render(detail: GistDetail, existing: FavoriteItem.Gist? = null, enabled: Boolean = true) {
        coEvery { repository.getGist("abc") } returns ApiResult.Success(detail)
        coEvery { repository.getComments("abc", 1) } returns ApiResult.Success(GistCommentPage(emptyList(), null))
        coEvery { repository.getOldestVersion("abc") } returns ApiResult.Success("first-version")
        every { library.observeSettings(any()) } returns flowOf(LibrarySettings(favoritesEnabled = enabled))
        every { library.observe(any(), LibraryContent.Gist("abc")) } returns flowOf(
            LibraryState(loaded = true, favorites = listOfNotNull(existing)),
        )
        every { library.observeGroups(any()) } returns flowOf(listOf(
            FavoriteGroup(DEFAULT_FAVORITE_GROUP, "", 0, 0), FavoriteGroup("work", "Work", 1, 1),
        ))
        val errors = ErrorEventBus()
        detailModel = GistDetailViewModel(repository, mockk(), mockk(), errors, mockk(relaxed = true),
            mockk(relaxed = true), GistUpdateEventBus(), accounts.session, library)
        accountModel = AccountLibraryViewModel(library, errors, accounts.session)
        pageModel = LibraryViewModel(library, errors, accounts.session)
        compose.setContent {
            MaterialTheme {
                GistDetailScreen(
                    accountLibrary = accountModel, gistId = "abc", onBack = {}, onOwnerClick = {},
                    onForkSourceClick = {}, onRevisions = {}, onForks = {}, onStargazers = {},
                    onAddFile = {}, onEditFile = {}, onCompose = {}, onForked = {}, onDeleted = {},
                    viewModel = detailModel, libraryViewModel = pageModel,
                )
            }
        }
    }

    private fun openMenu() = compose.onNode(
        hasContentDescription(context.getString(R.string.common_more)) and
            hasAnySibling(hasContentDescription(context.getString(R.string.conversation_share))),
    ).performClick()
    private fun node(id: Int) = compose.onNodeWithText(context.getString(id))

    private fun detail(owner: String) = GistDetail(
        id = "abc", nodeId = "gist-node", url = "https://gist.github.com/$owner/abc",
        description = "sample", isPublic = true, owner = GistUser(owner, null, null),
        files = listOf(GistFile("main.txt", "text/plain", "Text", "https://example/raw", 5, false, "hello")),
        commentCount = 0, commentsEnabled = true, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-02T00:00:00Z",
        forkOf = null, latestVersion = "latest", viewerLogin = "viewer", isStarred = false, stargazerCount = 0, forkCount = 0,
    )
}
