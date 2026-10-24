package com.gitmob.app.ui.repos

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryListOwnerContext
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h740dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReposScreenTest {
    private val mainDispatcherRule = MainDispatcherRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

    private val repository = mockk<RepoRepository>(relaxUnitFun = true).also {
        coEvery { it.getRepos(any(), any(), any(), any()) } returns ApiResult.Success(page())
    }
    private val models = mutableListOf<ReposViewModel>()
    private val popup = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsPopup)

    @After
    fun tearDown() = models.forEach { it.viewModelScope.cancel() }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h740dp")
    fun `简体窄屏菜单顺序筛选交集及互斥排序`() = assertMenusAndMutualSort()

    @Test
    @Config(qualifiers = "zh-rTW-w320dp-h740dp")
    fun `繁体窄屏菜单顺序筛选交集及互斥排序`() = assertMenusAndMutualSort()

    @Test
    @Config(qualifiers = "en-w320dp-h740dp")
    fun `英文窄屏菜单顺序筛选交集及互斥排序`() = assertMenusAndMutualSort()

    @Test
    fun `本人Tab保留主体新建且搜索在同一标题栏展开`() =
        assertEntry(null, RepositoryCreateOwner("u1", "me", "My profile", null, RepositoryCreateOwnerType.USER, true), false)

    @Test
    fun `本人主页入口保留返回和新建`() =
        assertEntry("me", RepositoryCreateOwner("u1", "me", "My profile", null, RepositoryCreateOwnerType.USER, true), true)

    @Test
    fun `他人主页缺少名称时显示login且不出现新建占位`() =
        assertEntry("other", RepositoryCreateOwner("u2", "other", null, null, RepositoryCreateOwnerType.USER, false), true)

    @Test
    fun `组织入口沿用服务端创建权限`() =
        assertEntry("org", RepositoryCreateOwner("o1", "org", "Organization", null, RepositoryCreateOwnerType.ORGANIZATION, false), true)

    @Test
    fun `route login title stays identical before and after owner name loads`() {
        val pending = CompletableDeferred<ApiResult<RepoList>>()
        coEvery { repository.getRepos(any(), any(), any(), any()) } coAnswers { pending.await() }
        setContent(viewModel(), login = "other", onBack = {})
        val title = text(R.string.repos_owner_title, "other")
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.runOnIdle {
            pending.complete(ApiResult.Success(page(owner = RepositoryCreateOwner("u2", "other", "Display name", null, RepositoryCreateOwnerType.USER, false))))
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText("Display name").assertDoesNotExist()
    }

    @Test
    fun `清空按钮只清搜索词并保留四维筛选及排序`() {
        val vm = viewModel(SavedStateHandle(mapOf(
            "repos.query" to " needle ", "repos.visibility" to RepoVisibility.PRIVATE.name,
            "repos.archived" to true, "repos.fork" to false, "repos.issues" to false,
            "repos.sort" to RepoSort.STARS_DESC.name,
        )))
        setContent(vm)
        val before = vm.state.value.filters
        openSearch(before.query)

        composeRule.onNodeWithContentDescription(text(R.string.repos_clear_search)).performClick()

        assertEquals(before.copy(query = ""), vm.state.value.filters)
        composeRule.onNodeWithContentDescription(text(R.string.repos_clear_search)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.repos_reset)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        coVerify(exactly = 1) { repository.getRepos(null, null, before.copy(query = ""), false) }
    }

    @Test
    fun `首次等待时可以展开标题栏搜索且输入法动作只释放焦点`() {
        val pending = CompletableDeferred<ApiResult<RepoList>>()
        coEvery { repository.getRepos(any(), any(), any(), any()) } coAnswers { pending.await() }
        val vm = viewModel()
        setContent(vm)

        composeRule.onNodeWithText(text(R.string.repos_empty)).assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val filterTop = capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot.top
        openSearch()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeRule.onNode(hasSetTextAction()).assertIsFocused().performTextInput("needle")
        composeRule.onNodeWithContentDescription(text(R.string.repos_search)).assertDoesNotExist()
        assertEquals(filterTop, capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot.top, 1f)
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("needle", vm.state.value.filters.query)
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.repos_reset)).assertDoesNotExist()
    }

    @Test
    fun `首次失败保留筛选控件且列表重试能恢复`() {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns
            ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(page(repo("recovered")))
        val vm = viewModel()
        setContent(vm)

        capsule(R.string.work_filter_visibility, R.string.common_all).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.repos_search)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.common_retry)).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("recovered").assertIsDisplayed()
        assertFalse(vm.state.value.loadFailed)
        coVerify(exactly = 2) { repository.getRepos(null, null, RepoFilters(), false) }
    }

    @Test
    fun `连续空批次逐次手动继续末页才显示最终空态`() {
        val filters = RepoFilters(query = "needle")
        coEvery { repository.getRepos(null, null, filters, false) } returns
            ApiResult.Success(page(next = "c1", manual = true, total = 300))
        coEvery { repository.getRepos(null, "c1", filters, false) } returns
            ApiResult.Success(page(next = "c2", manual = true, total = 300))
        coEvery { repository.getRepos(null, "c2", filters, false) } returns
            ApiResult.Success(page(total = 300))
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to "needle")))
        setContent(vm)

        composeRule.onNodeWithText(text(R.string.repos_empty_batch)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.repos_empty)).assertDoesNotExist()
        coVerify(exactly = 0) { repository.getRepos(null, "c1", filters, false) }

        composeRule.onNodeWithText(text(R.string.repos_continue_search)).performClick()
        assertEquals("c2", vm.state.value.endCursor)
        assertEquals(2, vm.state.value.completedBatches)
        composeRule.onNodeWithText(text(R.string.repos_empty_batch)).assertIsDisplayed()
        coVerify(exactly = 0) { repository.getRepos(null, "c2", filters, false) }

        composeRule.onNodeWithText(text(R.string.repos_continue_search)).performClick()
        composeRule.onNodeWithText(text(R.string.repos_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.repos_continue_search)).assertDoesNotExist()
        assertEquals(3, vm.state.value.completedBatches)
        coVerify(exactly = 3) { repository.getRepos(any(), any(), any(), any()) }
    }

    @Test
    fun `后续搜索批次保留累计去重命中而非候选总数`() {
        val filters = RepoFilters(query = "hit")
        coEvery { repository.getRepos(null, null, filters, false) } returns
            ApiResult.Success(page(repo("hit-one"), next = "c1", manual = true, total = 300))
        coEvery { repository.getRepos(null, "c1", filters, false) } returns
            ApiResult.Success(page(repo("HIT-ONE"), repo("hit-two"), total = 300))
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to "hit")))
        setContent(vm)

        assertEquals(listOf("hit-one"), vm.state.value.repos.map { it.name })
        composeRule.onNodeWithText(text(R.string.repos_continue_search)).performScrollTo().performClick()
        assertEquals(listOf("HIT-ONE", "hit-two"), vm.state.value.repos.map { it.name })
    }

    @Test
    fun `尾项进入可见区域才追加且在途请求不会重入`() {
        val repos = (0..24).map { repo("repo-${it.toString().padStart(2, '0')}") }
        val pending = CompletableDeferred<ApiResult<RepoList>>()
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(*repos.toTypedArray(), next = "c1", total = 50))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } coAnswers { pending.await() }
        val vm = viewModel()
        setContent(vm)
        coVerify(exactly = 0) { repository.getRepos(null, "c1", RepoFilters(), false) }

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(repos.size)
        composeRule.waitForIdle()
        assertTrue(vm.state.value.isLoadingMore)
        coVerify(exactly = 1) { repository.getRepos(null, "c1", RepoFilters(), false) }
        composeRule.runOnIdle { vm.loadMore() }
        coVerify(exactly = 1) { repository.getRepos(null, "c1", RepoFilters(), false) }
        composeRule.runOnIdle { pending.complete(ApiResult.Success(page(repo("last")))) }
    }

    @Test
    fun `追加失败不会自动重试且底部按钮保留原批起点`() {
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(repo("first"), next = "c1"))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns
            ApiResult.Failure(ApiError.NetworkError) andThen ApiResult.Success(page(repo("second")))
        val vm = viewModel()
        setContent(vm)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText(text(R.string.common_retry)).assertIsDisplayed()
        composeRule.runOnIdle { vm.updateSearch(" ") }
        composeRule.waitForIdle()
        assertEquals("first", vm.state.value.repos.single().name)
        coVerify(exactly = 1) { repository.getRepos(null, "c1", RepoFilters(), false) }

        composeRule.onNodeWithText(text(R.string.common_retry)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("first", "second"), vm.state.value.repos.map { it.name })
        coVerify(exactly = 2) { repository.getRepos(null, "c1", RepoFilters(), false) }
    }

    @Test
    fun `后续结果插入前部仍保持原阅读锚点且改条件回顶部`() {
        val repos = (0..24).map { repo("repo-${it.toString().padStart(2, '0')}") }
        coEvery { repository.getRepos(null, null, RepoFilters(), false) } returns ApiResult.Success(page(*repos.toTypedArray(), next = "c1"))
        coEvery { repository.getRepos(null, "c1", RepoFilters(), false) } returns ApiResult.Success(page(repo("alpha")))
        coEvery { repository.getRepos(null, null, RepoFilters(isArchived = false), false) } returns ApiResult.Success(page(*repos.toTypedArray()))
        val vm = viewModel()
        setContent(vm)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val before = composeRule.onNodeWithText("repo-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top

        composeRule.runOnIdle { vm.loadMore() }
        val after = composeRule.onNodeWithText("repo-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 1f)
        assertEquals("alpha", vm.state.value.repos.first().name)

        composeRule.runOnIdle { vm.selectArchived(false) }
        composeRule.onNodeWithText("repo-00").assertIsDisplayed()
    }

    @Test
    fun `每组筛选或排序变化显示固定左侧重置并保留原始查询及展开状态`() {
        val query = "  Raw Query  "
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to query)))
        setContent(vm)
        composeRule.onNodeWithText(text(R.string.repos_reset)).assertDoesNotExist()
        openSearch(query)
        val changes = listOf<() -> Unit>(
            { vm.selectVisibility(RepoVisibility.PUBLIC) }, { vm.selectArchived(true) },
            { vm.selectFork(true) }, { vm.selectIssues(false) },
            { vm.selectSort(RepoSort.CREATED_ASC) }, { vm.selectSort(RepoSort.STARS_DESC) },
            { vm.selectSort(RepoSort.NAME_DESC) },
        )
        changes.forEach { change ->
            composeRule.runOnIdle(change)
            val reset = composeRule.onNodeWithContentDescription(text(R.string.repos_reset_filters))
            val before = reset.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val nameValue = if (vm.state.value.filters.sort == RepoSort.NAME_DESC) R.string.repos_sort_name_desc else R.string.repos_sort_inactive
            capsule(R.string.repos_filter_name, nameValue).performScrollTo().assertIsDisplayed()
            val after = reset.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(before, after)
            reset.performClick()
            assertEquals(RepoFilters(query = query), vm.state.value.filters)
            composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
            composeRule.onNodeWithText(text(R.string.repos_reset)).assertDoesNotExist()
            capsule(R.string.work_filter_visibility, R.string.common_all).assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun `短横屏大字搜索不另占一行且结果可读筛选不随列表纵向滚动`() {
        val repos = (0..24).map { repo("repo-$it") }
        coEvery { repository.getRepos(any(), any(), any(), any()) } returns ApiResult.Success(page(*repos.toTypedArray()))
        setContent(viewModel(), fontScale = 1.5f)
        val before = capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot
        openSearch()
        val input = composeRule.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(input.top >= 0 && input.bottom <= before.top)
        val expanded = capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot
        composeRule.onNode(hasScrollToIndexAction()).assertHeightIsAtLeast(48.dp).performScrollToIndex(12)
        assertEquals(expanded, capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun `仓库及来源详情往返保存收起状态查询和两种滚动位置且重开不请求`() {
        val repos = (0..24).map { index -> repo("needle-${index.toString().padStart(2, '0')}").let {
            if (index == 12) it.copy(isFork = true, forkedFromOwner = "source-owner", forkedFromName = "source-repo") else it
        } }
        coEvery { repository.getRepos(any(), any(), any(), any()) } returns ApiResult.Success(page(*repos.toTypedArray()))
        val query = " Needle "
        val vm = viewModel(SavedStateHandle(mapOf("repos.query" to query, "repos.archived" to false)))
        val opened = mutableListOf<Pair<String, String>>()
        var detail by mutableStateOf(false)
        composeRule.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (detail) {
                    TextButton(onClick = { detail = false }) { Text(text(R.string.common_back)) }
                } else {
                    holder.SaveableStateProvider("repos") {
                        val open: (String, String) -> Unit = { owner, name -> opened += owner to name; detail = true }
                        ReposScreen(viewModel = vm, onRepoClick = open, onForkSourceClick = open)
                    }
                }
            }
        }
        openSearch(query)
        capsule(R.string.repos_filter_name, R.string.repos_sort_inactive).performScrollTo()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val before = composeRule.onNodeWithText("needle-12").fetchSemanticsNode().boundsInRoot.top
        for (source in listOf(false, true)) {
            if (source) openSearch(query)
            if (source) composeRule.onNodeWithContentDescription(text(R.string.common_open_source_repo)).performClick()
            else composeRule.onNodeWithText("needle-12", useUnmergedTree = true).performClick()
            composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
            composeRule.onNodeWithText(text(R.string.common_back)).performClick()
            composeRule.onNodeWithContentDescription(text(R.string.repos_search_active, query)).assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
            assertEquals(before, composeRule.onNodeWithText("needle-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
            capsule(R.string.repos_filter_name, R.string.repos_sort_inactive).assertIsDisplayed()
            assertEquals(RepoFilters(query = query, isArchived = false), vm.state.value.filters)
        }
        assertEquals(listOf("me" to "needle-12", "source-owner" to "source-repo"), opened)
        openSearch(query)
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        coVerify(exactly = 1) { repository.getRepos(any(), any(), any(), any()) }
    }

    private fun assertMenusAndMutualSort() {
        val vm = viewModel()
        setContent(vm)
        val initial = listOf(
            R.string.work_filter_visibility to R.string.common_all,
            R.string.repos_filter_archived to R.string.common_all,
            R.string.repo_fork to R.string.common_all,
            R.string.common_issues to R.string.common_all,
            R.string.repos_filter_time to R.string.work_sort_updated_desc,
            R.string.nav_tab_stars to R.string.repos_sort_inactive,
            R.string.repos_filter_name to R.string.repos_sort_inactive,
        ).map { (filter, value) -> description(filter, value) }
        val capsules = composeRule.onAllNodes(SemanticsMatcher("repository filters") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it in initial } == true
        }).fetchSemanticsNodes()
        assertEquals(initial, capsules.map { it.config[SemanticsProperties.ContentDescription].single() })
        assertEquals(null, vm.state.value.filters.isFork)
        composeRule.onAllNodesWithText(text(R.string.repos_reset)).assertCountEquals(0)

        choose(R.string.work_filter_visibility, R.string.common_all,
            listOf(R.string.common_all, R.string.work_visibility_public, R.string.common_private, R.string.work_visibility_internal), R.string.work_visibility_internal)
        choose(R.string.repos_filter_archived, R.string.common_all,
            listOf(R.string.common_all, R.string.repos_not_archived, R.string.common_archived), R.string.repos_not_archived)
        choose(R.string.repo_fork, R.string.common_all,
            listOf(R.string.common_all, R.string.gist_category_original, R.string.repo_fork), R.string.gist_category_original)
        choose(R.string.common_issues, R.string.common_all,
            listOf(R.string.common_all, R.string.repos_issues_enabled, R.string.repos_issues_disabled), R.string.repos_issues_disabled)
        assertEquals(RepoFilters(visibility = RepoVisibility.INTERNAL, isArchived = false, isFork = false, hasIssuesEnabled = false), vm.state.value.filters)
        val beforeAll = vm.state.value.filters
        choose(R.string.repo_fork, R.string.gist_category_original,
            listOf(R.string.common_all, R.string.gist_category_original, R.string.repo_fork), R.string.common_all)
        assertEquals(beforeAll.copy(isFork = null), vm.state.value.filters)

        choose(R.string.repos_filter_time, R.string.work_sort_updated_desc,
            listOf(R.string.work_sort_updated_desc, R.string.work_sort_created_asc, R.string.work_sort_created_desc,
                R.string.work_sort_updated_asc, R.string.repos_sort_pushed_asc, R.string.repos_sort_pushed_desc), R.string.repos_sort_pushed_desc)
        assertEquals(RepoSort.PUSHED_DESC, vm.state.value.filters.sort)
        choose(R.string.nav_tab_stars, R.string.repos_sort_inactive,
            listOf(R.string.repos_sort_stars_asc, R.string.repos_sort_stars_desc), R.string.repos_sort_stars_desc)
        assertEquals(RepoSort.STARS_DESC, vm.state.value.filters.sort)
        capsule(R.string.repos_filter_time, R.string.repos_sort_inactive).performScrollTo().assertIsDisplayed()
        choose(R.string.repos_filter_name, R.string.repos_sort_inactive,
            listOf(R.string.repos_sort_name_desc, R.string.repos_sort_name_asc), R.string.repos_sort_name_asc)
        assertEquals(RepoSort.NAME_ASC, vm.state.value.filters.sort)
        capsule(R.string.nav_tab_stars, R.string.repos_sort_inactive).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText(text(R.string.repos_reset)).performClick()
        assertEquals(RepoFilters(), vm.state.value.filters)
        capsule(R.string.repo_fork, R.string.common_all).performScrollTo().assertIsDisplayed()
    }

    private fun assertEntry(login: String?, owner: RepositoryCreateOwner, hasBack: Boolean) {
        coEvery { repository.getRepos(any(), any(), any(), any()) } returns ApiResult.Success(page(owner = owner))
        setContent(viewModel(), login = login, onBack = if (hasBack) ({}) else null)
        val title = login?.let { text(R.string.repos_owner_title, it) } ?: owner.name ?: owner.login
        composeRule.onNodeWithText(title).assertIsDisplayed()
        if (hasBack) composeRule.onNodeWithContentDescription(text(R.string.common_back)).assertIsDisplayed()
        else composeRule.onNodeWithContentDescription(text(R.string.common_back)).assertDoesNotExist()
        val create = composeRule.onNodeWithContentDescription(text(R.string.repo_create_title))
        val search = composeRule.onNodeWithContentDescription(text(R.string.repos_search)).assertIsDisplayed()
        if (owner.canCreateRepository) {
            assertTrue(search.fetchSemanticsNode().boundsInRoot.right <= create.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left)
        } else create.assertDoesNotExist()
        val filterTop = capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot.top
        openSearch()
        composeRule.onNodeWithText(title).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(text(R.string.repos_search)).assertDoesNotExist()
        val input = composeRule.onNode(hasSetTextAction()).assertIsFocused().fetchSemanticsNode().boundsInRoot
        if (owner.canCreateRepository) assertTrue(input.right <= create.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left)
        else create.assertDoesNotExist()
        assertEquals(filterTop, capsule(R.string.work_filter_visibility, R.string.common_all).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    private fun openSearch(query: String = "") {
        val description = if (query.isNotBlank()) text(R.string.repos_search_active, query) else text(R.string.repos_search)
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.waitForIdle()
    }

    private fun choose(@StringRes filter: Int, @StringRes current: Int, options: List<Int>, @StringRes choice: Int) {
        capsule(filter, current).performScrollTo().assertIsDisplayed().performClick()
        val labels = composeRule.onAllNodes(hasAnyAncestor(popup) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes().map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertEquals(options.map { text(it) }, labels)
        composeRule.onNode(hasAnyAncestor(popup) and hasText(text(choice))).performClick()
        composeRule.waitForIdle()
    }

    private fun capsule(@StringRes filter: Int, @StringRes value: Int) =
        composeRule.onNodeWithContentDescription(description(filter, value))

    private fun description(@StringRes filter: Int, @StringRes value: Int) =
        text(R.string.work_select_filter_value, text(filter), text(value))

    private fun text(@StringRes resource: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resource, *args)

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
        ReposViewModel(repository, RepoUpdateEventBus(), ErrorEventBus(), handle).also { models += it }

    private fun setContent(viewModel: ReposViewModel, login: String? = null, onBack: (() -> Unit)? = null, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme { ReposScreen(viewModel = viewModel, login = login, onBack = onBack) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun repo(name: String) = RepoListItem(
        name = name, ownerLogin = "me", description = null, homepageUrl = null,
        visibility = RepoVisibility.PUBLIC, isArchived = false, isFork = false, hasIssuesEnabled = true,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, pushedAt = null,
        forkedFromOwner = null, forkedFromName = null, languageName = null, languageColor = null,
        stargazerCount = 0, forkCount = 0, openIssueCount = 0, topics = emptyList(), defaultBranchName = null,
    )

    private fun page(
        vararg repos: RepoListItem,
        next: String? = null,
        total: Int = repos.size,
        manual: Boolean = false,
        owner: RepositoryCreateOwner? = null,
    ) = RepoList(total, repos.toList(), next != null, next, owner?.let(::RepositoryListOwnerContext), requiresManualContinue = manual)
}
