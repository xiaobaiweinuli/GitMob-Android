package com.gitmob.app.ui.search

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
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
            listOf(SearchSnippet("fun main() = Unit", listOf(SearchMatch(4, 8)))), SearchFileTarget("main", false))
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
            listOf(first, second), SearchFileTarget("main", false))
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
            listOf(SearchSnippet(text, listOf(SearchMatch(text.length - 2, text.length)))), SearchFileTarget("main", false))
        compose.setContent { GitMobTheme { Surface { SearchResult(file, actions()) } } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(8, layouts.single().lineCount)
        assertFalse(layouts.single().didOverflowHeight)
        assertFalse(layouts.single().isLineEllipsized(7))
    }

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
