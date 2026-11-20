package com.gitmob.app.ui.repoforks

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.RepoForkSort
import com.gitmob.app.data.model.RepoForkSortDirection
import com.gitmob.app.data.model.RepoForkSortField
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.R
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoForksScreenTest {
    private val mainDispatcher = MainDispatcherRule()
    private val accounts = AccountSessionRule()
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(mainDispatcher).around(accounts).around(compose)
    private val repository = mockk<RepoDetailRepository>()
    private val popup = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsPopup)
    private val visible = mutableStateOf(true)
    private lateinit var inputModeManager: InputModeManager
    private lateinit var model: RepoForksViewModel

    @Test
    fun `large English type keeps the toolbar compact and the first card visible`() {
        val sort = RepoForkSort(RepoForkSortField.PUSHED, RepoForkSortDirection.ASC)
        show(fontScale = 2f, sort = sort)
        val chip = trigger(R.string.repo_forks_sort_pushed_asc).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val count = compose.onNodeWithText("10,000").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("forker/fork-0").assertIsDisplayed()
        assertTrue("Count overlaps the capsule", !chip.overlaps(count))
        val density = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.density
        assertTrue("Capsule should use at most two lines", chip.height <= 110 * density)
        assertEquals(8 * density, list.top - maxOf(chip.bottom, count.bottom), density)
        trigger(R.string.repo_forks_sort_pushed_asc).performClick()
        option(R.string.repo_forks_sort_pushed).performScrollTo().performClick()
        option(R.string.repo_forks_sort_pushed_desc).assertIsDisplayed().performClick()
        trigger(R.string.repo_forks_sort_pushed_desc).assertIsDisplayed()
        assertEquals(RepoForkSort(RepoForkSortField.PUSHED, RepoForkSortDirection.DESC), model.state.value.sort)
    }

    @Test
    fun `opening a field only expands and confirming the same order sends no request`() {
        show()
        compose.onNodeWithText("forker/fork-0").assertIsDisplayed()
        trigger().performClick()
        option(R.string.repo_forks_sort_created).performClick()
        compose.onAllNodes(popup).assertCountEquals(2)
        option(R.string.repo_forks_sort_created_desc).assertIsSelected()
        coVerify(exactly = 1) { repository.getForks(any(), any(), any(), any()) }
        option(R.string.repo_forks_sort_created_desc).performClick()
        compose.onAllNodes(popup).assertCountEquals(0)
        trigger().assertIsDisplayed()
        coVerify(exactly = 1) { repository.getForks(any(), any(), any(), any()) }
    }

    @Test
    fun `returning restores scroll while changing order returns to the first fork`() {
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val before = compose.onNodeWithText("forker/fork-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("forker/fork-12", useUnmergedTree = true).performClick()
        compose.runOnIdle { visible.value = true }
        assertEquals(before, compose.onNodeWithText("forker/fork-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        coVerify(exactly = 1) { repository.getForks(any(), any(), any(), any()) }
        trigger().performClick()
        option(R.string.repo_forks_sort_stars).performClick()
        option(R.string.repo_forks_sort_stars_desc).performClick()
        compose.onNodeWithText("forker/fork-0").assertIsDisplayed()
        coVerify(exactly = 1) { repository.getForks("upstream", "source", null, RepoForkSort(RepoForkSortField.STARS, RepoForkSortDirection.DESC)) }
    }

    @Test
    fun `LTR logical forward and back keys open and close the child`() =
        checkKeyboard(LayoutDirection.Ltr, Key.DirectionRight, Key.DirectionLeft)

    @Test
    fun `RTL logical forward and back keys open and close the child`() =
        checkKeyboard(LayoutDirection.Rtl, Key.DirectionLeft, Key.DirectionRight)

    @Test
    fun `leaving with a child menu open disposes its focus and returns with menus closed`() {
        show()
        trigger().performClick()
        option(R.string.repo_forks_sort_stars).performClick()
        compose.onAllNodes(popup).assertCountEquals(2)
        compose.runOnIdle { visible.value = false }
        compose.onAllNodes(popup).assertCountEquals(0)
        compose.runOnIdle { visible.value = true }
        compose.onAllNodes(popup).assertCountEquals(0)
        trigger().performClick()
        option(R.string.repo_forks_sort_created).performClick()
        option(R.string.repo_forks_sort_created_asc).performClick()
        trigger(R.string.repo_forks_sort_created_asc).assertIsDisplayed()
    }

    @Test
    fun `pagination continues when a page contains only already displayed forks`() {
        show(pages = listOf(
            RepoList(2, listOf(repo("one")), true, "page-two"),
            RepoList(2, listOf(repo("one")), true, "page-three"),
            RepoList(2, listOf(repo("two")), false, null),
        ))
        compose.waitForIdle()
        assertEquals(listOf("one", "two"), model.state.value.repos.map { it.name })
        coVerify(exactly = 1) { repository.getForks("upstream", "source", "page-three", RepoForkSort()) }
    }

    private fun checkKeyboard(direction: LayoutDirection, forward: Key, back: Key) {
        // Popup windows need the platform input mode, not only the activity Compose owner.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        show(direction = direction)
        compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        trigger().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        trigger().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        option(R.string.repo_forks_sort_created).assertIsFocused().performKeyInput { pressKey(forward) }
        option(R.string.repo_forks_sort_created_desc).assertIsFocused().performKeyInput { pressKey(back) }
        compose.onAllNodes(popup).assertCountEquals(1)
        option(R.string.repo_forks_sort_created).assertIsFocused().performKeyInput { pressKey(forward) }
        option(R.string.repo_forks_sort_created_desc).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        option(R.string.repo_forks_sort_created_asc).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onAllNodes(popup).assertCountEquals(0)
        trigger(R.string.repo_forks_sort_created_asc).assertIsFocused()
    }

    private fun show(
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
        sort: RepoForkSort = RepoForkSort(),
        pages: List<RepoList> = listOf(RepoList(10_000, List(30) { repo("fork-" + it) }, false, null)),
    ) {
        coEvery { repository.getForks(any(), any(), any(), any()) } returnsMany pages.map { ApiResult.Success(it) }
        model = RepoForksViewModel(repository, ErrorEventBus(), accounts.session, SavedStateHandle())
        model.selectSort(sort)
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            val holder = rememberSaveableStateHolder()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                MaterialTheme {
                    if (visible.value) holder.SaveableStateProvider("forks") {
                        RepoForksScreen(
                            owner = "upstream", name = "source", onBack = {}, onOwnerClick = {},
                            onRepositoryClick = { _, _ -> visible.value = false },
                            onForkSourceClick = { _, _ -> }, onHomepageClick = {}, viewModel = model,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun trigger(label: Int = R.string.repo_forks_sort_created_desc) =
        compose.onNodeWithContentDescription(text(R.string.work_select_filter_value, text(R.string.work_filter_sort), text(label)))

    private fun option(label: Int) = compose.onNode(hasText(text(label)) and hasAnyAncestor(popup))

    private fun repo(name: String) = RepoListItem(
        name = name, ownerLogin = "forker", description = null, homepageUrl = null,
        visibility = RepoVisibility.PUBLIC, isArchived = false, isFork = true, hasIssuesEnabled = true,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, pushedAt = null,
        forkedFromOwner = "upstream", forkedFromName = "source", languageName = null, languageColor = null,
        stargazerCount = 0, forkCount = 0, openIssueCount = 0, topics = emptyList(), defaultBranchName = null,
    )
}
