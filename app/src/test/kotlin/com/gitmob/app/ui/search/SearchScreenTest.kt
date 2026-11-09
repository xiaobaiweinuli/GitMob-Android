package com.gitmob.app.ui.search

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.Key
import android.graphics.Canvas
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.local.search.SearchHistoryStore
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.SearchRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.theme.GitMobTheme
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchScreenTest {
    private val compose = createComposeRule()
    private val accounts = AccountSessionRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repository = mockk<SearchRepository>()
    private val history = mockk<SearchHistoryStore> {
        every { observe(any()) } returns flowOf(listOf("compose", "language:kotlin"))
        coEvery { record(any(), any()) } returns ApiResult.Success(Unit)
    }
    private lateinit var contentView: View
    private var viewModel: SearchViewModel? = null
    private fun model() = SearchViewModel(repository, history, ErrorEventBus(), accounts.session, SavedStateHandle()).also { viewModel = it }
    private fun actions(repo: (String, String) -> Unit = { _, _ -> }, file: (SearchItem.Code) -> Unit = {}) =
        SearchResultActions(repo, {}, {}, file, {})
    @After fun close() { viewModel?.viewModelScope?.cancel() }

    @Test fun historyFillsInputAndCategoryClickOnlyNavigatesToChosenCategory() {
        var selected: Pair<String, SearchCategory>? = null
        val vm = model()
        compose.setContent { GitMobTheme { contentView = LocalView.current; SearchScreen({}, { q, c -> selected = q to c }, actions(), vm) } }
        compose.onNodeWithText("compose").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("compose")
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
        screenshot("input-categories.png")
        compose.onNodeWithText(context.getString(R.string.search_code)).performClick()
        assertEquals("compose" to SearchCategory.CODE, selected)
        coVerify(exactly = 1) { history.record(any(), "compose") }
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
    }

    @Test fun imeSearchSubmitsAllSixWithoutASeparateSubmitButton() {
        coEvery { repository.search(any(), any(), true, null) } returns ApiResult.Success(SearchPage(emptyList(), 0, null))
        val vm = model()
        compose.setContent { GitMobTheme { contentView = LocalView.current; SearchScreen({}, { _, _ -> }, actions(), vm) } }
        compose.onNode(hasSetTextAction()).performTextInput("compose")
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        coVerify(exactly = 6) { repository.search("compose", any(), true, null) }
        assertEquals(SearchCategory.entries.toSet(), vm.state.value.sections.keys)
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(2))
        compose.onAllNodesWithText(context.getString(R.string.search_empty))[0].assertExists()
        screenshot("overview-empty.png")
    }

    @Test fun categoryHasNoInputAndLoadsNextPageOnlyWhenTailIsVisible() {
        val next = SearchContinuation(cursor = "next", received = 20)
        fun account(i: Int) = SearchItem.Account("id$i", "person$i", "Person $i", "", "Android developer", "Earth")
        coEvery { repository.search("compose", SearchCategory.USERS, false, null) } returns ApiResult.Success(SearchPage((1..20).map(::account), 21, next))
        coEvery { repository.search("compose", SearchCategory.USERS, false, next) } returns ApiResult.Success(SearchPage(listOf(account(21)), 21, null))
        val vm = model()
        compose.setContent { GitMobTheme { contentView = LocalView.current; SearchCategoryScreen("compose", SearchCategory.USERS, {}, actions(), vm) } }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.waitForIdle()
        coVerify(exactly = 0) { repository.search("compose", SearchCategory.USERS, false, next) }
        screenshot("category-users.png")
        compose.onNode(hasScrollAction()).performScrollToIndex(20)
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.search("compose", SearchCategory.USERS, false, next) }
        assertEquals(21, vm.state.value.sections[SearchCategory.USERS]!!.page!!.items.size)
        compose.onNodeWithText(context.getString(R.string.search_recent)).assertDoesNotExist()
    }

    @Test fun codeCardHasSeparateRepositoryAndFileTargets() {
        var openedRepo: String? = null
        var openedFile: String? = null
        val file = SearchItem.Code("owner", "repo", "Main.kt", "src/Main.kt", "",
            listOf(SearchSnippet("fun main() = Unit", listOf(SearchMatch(4, 8)))), SearchFileTarget("main"))
        compose.setContent { GitMobTheme { contentView = LocalView.current; Surface { SearchResult(file, actions(
            repo = { owner, name -> openedRepo = "$owner/$name" }, file = { openedFile = it.path })) } } }
        compose.onNodeWithText("src/Main.kt", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("owner / repo").performClick()
        assertEquals("owner/repo", openedRepo)
        assertNull(openedFile)
        compose.onNodeWithText("Main.kt", substring = true).performClick()
        assertEquals("src/Main.kt", openedFile)
        screenshot("code-card.png")
    }

    @Test fun multipleCodeFragmentsStayTogetherWithCorrectHighlightsAndOneRootFileName() {
        val firstText = "1. 将数据集划分为训练数据集和测试数据集。\n2. 在训练数据上训练模型。"
        val firstMatch = firstText.indexOf("测试")
        val first = SearchSnippet(firstText, listOf(SearchMatch(firstMatch, firstMatch + 2)))
        val second = SearchSnippet("```py\n# 查看前五个测试 Y 值\nlist(y_test)[0:5]", listOf(SearchMatch(13, 15)))
        var openedFile: String? = null
        val file = SearchItem.Code("apachecn", "ds-ai-tech-notes", "11.md", "11.md", "",
            listOf(first, second), SearchFileTarget("main"))
        compose.setContent { GitMobTheme { contentView = LocalView.current; Surface {
            SearchResult(file, actions(file = { openedFile = it.path }))
        } } }
        compose.onAllNodesWithText("11.md", useUnmergedTree = true).assertCountEquals(1)
        val firstNode = compose.onNodeWithText(first.text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val secondNode = compose.onNodeWithText(second.text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(firstNode.boundsInRoot.bottom, secondNode.boundsInRoot.top, 0.5f)
        listOf(firstNode to first, secondNode to second).forEach { (node, snippet) ->
            val displayed = node.config[SemanticsProperties.Text].single()
            assertEquals(snippet.matches.map { it.start to it.end }, displayed.spanStyles.map { it.start to it.end })
            assertEquals(listOf("测试"), displayed.spanStyles.map { displayed.text.substring(it.start, it.end) })
        }
        compose.onNodeWithText("…", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(second.text, useUnmergedTree = true).performClick()
        assertEquals("11.md", openedFile)
        screenshot("code-card-multiple-matches.png")
    }

    @Test fun codePreviewDoesNotTruncateLaterMatchesWithinAFragment() {
        val text = (1..7).joinToString("\n") { "context line $it" } + "\n测试"
        val file = SearchItem.Code("owner", "repo", "11.md", "11.md", "",
            listOf(SearchSnippet(text, listOf(SearchMatch(text.length - 2, text.length)))), SearchFileTarget("main"))
        compose.setContent { GitMobTheme { Surface { SearchResult(file, actions()) } } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(8, layouts.single().lineCount)
        assertFalse(layouts.single().didOverflowHeight)
        assertFalse(layouts.single().isLineEllipsized(7))
    }

    @Test fun repositoryRootStartsEmptyAndOnlyExplicitSubmitRequestsCodeWithoutHistory() {
        coEvery { repository.search(any(), SearchCategory.CODE, false, null) } returns ApiResult.Success(SearchPage(emptyList(), 0, null))
        val vm = model()
        compose.setContent { GitMobTheme { contentView = LocalView.current; RepoCodeSearchScreen("owner", "repo", "", false, {}, {}, vm) } }
        val input = compose.onNode(hasSetTextAction())
        assertEquals("", input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        input.assertIsFocused()
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
        input.performTextInput("compose language:kotlin")
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
        input.performImeAction()
        compose.waitForIdle()
        input.assertTextEquals("compose language:kotlin")
        coVerify(exactly = 1) { repository.search("repo:owner/repo compose language:kotlin", SearchCategory.CODE, false, null) }
        input.performTextReplacement("filename:Main.kt")
        input.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.search("repo:owner/repo filename:Main.kt", SearchCategory.CODE, false, null) }
        coVerify(exactly = 0) { history.record(any(), any()) }
        verify(exactly = 0) { history.observe(any()) }
        compose.onNodeWithContentDescription(context.getString(R.string.repos_clear_search)).performClick()
        assertEquals("", input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        input.assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.repo_code_search_hint)).assertIsDisplayed()
        coVerify(exactly = 2) { repository.search(any(), any(), any(), any()) }
        screenshot("repository-root.png")
    }

    @Test fun repositoryDirectorySearchShowsPathAndActualHighlightedFragmentsAndOpensReturnedRef() {
        val snippet = SearchSnippet("fun main() = 测试()", listOf(SearchMatch(4, 8), SearchMatch(13, 15)))
        val file = SearchItem.Code("owner", "repo", "Main.kt", "app/src/main/kotlin/com/gitmob/app/ui/search/Main.kt", "",
            listOf(snippet), SearchFileTarget("result-commit"))
        coEvery { repository.search("repo:owner/repo path:app", SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage(listOf(file), 1, null))
        val opened = mutableListOf<SearchItem.Code>()
        val vm = model()
        val keyboard = mockk<SoftwareKeyboardController>(relaxed = true)
        compose.setContent { GitMobTheme {
            contentView = LocalView.current
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                RepoCodeSearchScreen("owner", "repo", "app", false, {}, { opened += it }, vm)
            }
        } }
        val input = compose.onNode(hasSetTextAction()).assertTextEquals("path:app").assertIsFocused()
        assertEquals(TextRange(8), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        verify(atLeast = 1) { keyboard.show() }
        verify(exactly = 0) { keyboard.hide() }
        coVerify(exactly = 1) { repository.search("repo:owner/repo path:app", SearchCategory.CODE, false, null) }
        val pathNode = compose.onNodeWithText(file.path, useUnmergedTree = true).assertIsDisplayed()
        val pathLayout = mutableListOf<TextLayoutResult>()
        pathNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pathLayout) }
        assertTrue(pathLayout.single().lineCount > 1)
        assertFalse(pathLayout.single().didOverflowWidth)
        assertFalse(pathLayout.single().didOverflowHeight)
        val snippetNode = compose.onNodeWithText(snippet.text, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(pathNode.fetchSemanticsNode().boundsInRoot.bottom < snippetNode.fetchSemanticsNode().boundsInRoot.top)
        val displayed = snippetNode.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(listOf(4 to 8, 13 to 15), displayed.spanStyles.map { it.start to it.end })
        compose.onNodeWithText("owner / repo").assertDoesNotExist()
        compose.onNodeWithText("Main.kt", useUnmergedTree = true).assertDoesNotExist()
        pathNode.performClick()
        snippetNode.performClick()
        assertEquals(listOf(file, file), opened)
        assertEquals("result-commit", opened.last().target?.ref)
        coVerify(exactly = 1) { repository.search(any(), any(), any(), any()) }
        screenshot("repository-directory-results.png")
    }

    @Test fun repositoryScopeConflictKeepsExistingResultsAndDoesNotRequestAnotherRepository() {
        val file = codeFile(1)
        coEvery { repository.search("repo:owner/repo path:app", SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage(listOf(file), 1, null))
        val vm = model()
        compose.setContent { GitMobTheme { RepoCodeSearchScreen("owner", "repo", "app", false, {}, {}, vm) } }
        val input = compose.onNode(hasSetTextAction())
        input.performTextReplacement("repo:elsewhere/project test")
        input.performImeAction()
        compose.onNodeWithText(context.getString(R.string.repo_code_search_scope_conflict)).assertIsDisplayed()
        compose.onNodeWithText(file.path, useUnmergedTree = true).assertIsDisplayed()
        coVerify(exactly = 1) { repository.search(any(), any(), any(), any()) }
        input.performTextReplacement("test")
        compose.onNodeWithText(context.getString(R.string.repo_code_search_scope_conflict)).assertDoesNotExist()
        coVerify(exactly = 1) { repository.search(any(), any(), any(), any()) }
    }

    @Test fun repositoryAppendOnlyRunsAtFooterAndFailureKeepsResultsUntilExplicitRetry() {
        val query = "repo:owner/repo path:app fork:true"
        val next = SearchContinuation(page = 2, received = 20)
        coEvery { repository.search(query, SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage((1..20).map(::codeFile), 21, next))
        coEvery { repository.search(query, SearchCategory.CODE, false, next) } returnsMany listOf(
            ApiResult.Failure(ApiError.Forbidden), ApiResult.Success(SearchPage(listOf(codeFile(21)), 21, null)),
        )
        val vm = model()
        compose.setContent { GitMobTheme { RepoCodeSearchScreen("owner", "repo", "app", true, {}, {}, vm) } }
        compose.waitForIdle()
        coVerify(exactly = 0) { repository.search(query, SearchCategory.CODE, false, next) }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(20)
        compose.onNodeWithText(context.getString(R.string.common_retry)).performScrollTo().assertIsDisplayed()
        assertEquals(20, vm.state.value.sections[SearchCategory.CODE]!!.page!!.items.size)
        list.performScrollToIndex(0)
        list.performScrollToIndex(20)
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.search(query, SearchCategory.CODE, false, next) }
        compose.onNodeWithText(context.getString(R.string.common_retry)).performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 2) { repository.search(query, SearchCategory.CODE, false, next) }
        assertEquals(21, vm.state.value.sections[SearchCategory.CODE]!!.page!!.items.size)
        coEvery { repository.search("repo:owner/repo path:app keyword fork:true", SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage(emptyList(), 0, null))
        compose.onNode(hasSetTextAction()).performTextReplacement("path:app keyword")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assertTextEquals("path:app keyword").assertIsNotFocused()
        coVerify(exactly = 1) { repository.search(query, SearchCategory.CODE, false, null) }
        coVerify(exactly = 1) { repository.search("repo:owner/repo path:app keyword fork:true", SearchCategory.CODE, false, null) }
    }

    @Test fun repositoryFileReturnRestoresDraftPagesAndScrollWithoutSubmittingAgain() {
        val query = "repo:owner/repo path:app"
        val next = SearchContinuation(page = 2, received = 20)
        coEvery { repository.search(query, SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage((1..20).map(::codeFile), 40, next))
        coEvery { repository.search(query, SearchCategory.CODE, false, next) } returns
            ApiResult.Success(SearchPage((21..40).map(::codeFile), 40, null))
        val visible = mutableStateOf(true)
        val vm = model()
        compose.setContent { GitMobTheme {
            val holder = rememberSaveableStateHolder()
            if (visible.value) holder.SaveableStateProvider("repository-search") {
                RepoCodeSearchScreen("owner", "repo", "app", false, {}, { visible.value = false }, vm)
            }
        } }
        compose.onNode(hasSetTextAction()).performTextReplacement("path:app unsent")
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(20)
        compose.waitForIdle()
        list.performScrollToIndex(25)
        val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithText(codeFile(26).path, useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        compose.onNode(hasSetTextAction()).assertTextEquals("path:app unsent").assertIsNotFocused()
        compose.onNodeWithText(codeFile(26).path, useUnmergedTree = true).assertIsDisplayed()
        val scrollAfter = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(scrollBefore, scrollAfter, 0.01f)
        assertEquals(40, vm.state.value.sections[SearchCategory.CODE]!!.page!!.items.size)
        coVerify(exactly = 1) { repository.search(query, SearchCategory.CODE, false, null) }
        coVerify(exactly = 1) { repository.search(query, SearchCategory.CODE, false, next) }
        coVerify(exactly = 2) { repository.search(any(), any(), any(), any()) }
    }

    @Test fun repositoryImeOverlaysResultsWhileTheSyntaxStripFollowsItsTop() {
        val query = "repo:owner/repo path:app"
        coEvery { repository.search(query, SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage((1..20).map(::codeFile), 20, null))
        val vm = model()
        compose.setContent { GitMobTheme {
            contentView = LocalView.current
            RepoCodeSearchScreen("owner", "repo", "app", false, {}, {}, vm)
        } }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val before = list.fetchSemanticsNode().boundsInRoot
        fun showIme(height: Int) = compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(contentView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        showIme(300)
        assertEquals(before, list.fetchSemanticsNode().boundsInRoot)
        val first = compose.onNodeWithText("path:").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= contentView.height - 300)
        showIme(420)
        assertEquals(before, list.fetchSemanticsNode().boundsInRoot)
        val second = compose.onNodeWithText("path:").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(first.top - 120, second.top, 1f)
        coVerify(exactly = 1) { repository.search(any(), any(), any(), any()) }
        screenshot("repository-directory-ime-overlay.png")
    }

    @Test fun repositorySyntaxFollowsImeVisibilityAndInsertsTokensWithoutSubmitting() {
        val vm = model()
        compose.setContent { GitMobTheme { contentView = LocalView.current; RepoCodeSearchScreen("owner", "repo", "", false, {}, {}, vm) } }
        compose.onNodeWithText("path:").assertDoesNotExist()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(contentView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.onNodeWithText("path:").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("path:")
        compose.onNode(hasSetTextAction()).performTextInput("app")
        compose.onNodeWithText("language:").performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("path:app language:")
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
        screenshot("repository-syntax-ime-inset.png")
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(contentView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false).build())
        }
        compose.onNodeWithText("language:").assertDoesNotExist()
    }

    @Test fun repositoryImeDoesNotSubmitWhileTextIsComposing() {
        coEvery { repository.search("repo:owner/repo 测试", SearchCategory.CODE, false, null) } returns
            ApiResult.Success(SearchPage(emptyList(), 0, null))
        val vm = model()
        compose.setContent { GitMobTheme { contentView = LocalView.current; RepoCodeSearchScreen("owner", "repo", "", false, {}, {}, vm) } }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val connection = compose.runOnIdle { requireNotNull(contentView.onCreateInputConnection(EditorInfo())) }
        compose.runOnIdle { connection.setComposingText("测试", 1) }
        compose.onNode(hasSetTextAction()).performImeAction()
        coVerify(exactly = 0) { repository.search(any(), any(), any(), any()) }
        compose.runOnIdle { connection.finishComposingText() }
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.search("repo:owner/repo 测试", SearchCategory.CODE, false, null) }
    }

    private fun codeFile(index: Int) = SearchItem.Code("owner", "repo", "File$index.kt", "app/src/File$index.kt", "",
        listOf(SearchSnippet("fun example$index() = Unit", listOf(SearchMatch(4, 11)))), SearchFileTarget("sha$index"))

    private fun screenshot(name: String) {
        val folder = File("build/reports/search-ui").apply { mkdirs() }
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
            contentView.draw(Canvas(bitmap))
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
