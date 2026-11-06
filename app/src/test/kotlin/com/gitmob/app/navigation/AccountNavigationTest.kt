package com.gitmob.app.navigation

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.GistUpdateEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.preferences.ThemePreferenceStore
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.GHUser
import com.gitmob.app.data.model.GistCommentPage
import com.gitmob.app.data.model.GistDetail
import com.gitmob.app.data.model.GistUser
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.PagedStarredRepos
import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoList
import com.gitmob.app.data.model.RepoListItem
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryListOwnerContext
import com.gitmob.app.data.model.ViewerProfile
import com.gitmob.app.data.repository.AuthRepository
import com.gitmob.app.data.repository.GistRepository
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.StarRepository
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import com.gitmob.app.ui.common.AccountViewModel
import com.gitmob.app.ui.gist.GistDetailViewModel
import com.gitmob.app.ui.gist.GistCreateViewModel
import com.gitmob.app.ui.gist.GistViewModel
import com.gitmob.app.ui.home.HomeViewModel
import com.gitmob.app.ui.login.LoginViewModel
import com.gitmob.app.ui.repos.ReposViewModel
import com.gitmob.app.ui.settings.SettingsViewModel
import com.gitmob.app.ui.stars.StarsViewModel
import com.gitmob.app.ui.theme.ThemePreference
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder
import dagger.hilt.android.internal.lifecycle.HiltViewModelFactory
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.time.Instant
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w480dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountNavigationTest {
    private val accounts = AccountSessionRule()
    private val composeRule = createEmptyComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(composeRule)
    private val fixture = NavigationFixture(accounts.session)
    private var controller: ActivityController<HostActivity>? = null

    @After
    fun closeActivity() {
        composeRule.runOnUiThread { controller?.close() }
        HostActivity.fixture = null
    }

    @Test
    fun `real five tabs retain default Hilt view models filters and scroll within one session`() {
        launch()
        val epoch = accounts.session.epoch
        val home = fixture.model<HomeViewModel>()
        tab(R.string.common_repository)
        val repos = fixture.model<ReposViewModel>()
        composeRule.onNodeWithContentDescription(text(R.string.repos_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("  Alpha draft  ")
        composeRule.runOnIdle { repos.selectVisibility(RepoVisibility.PRIVATE) }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val repoTop = composeRule.onNodeWithText("alpha-repo-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top

        tab(R.string.nav_tab_stars)
        val stars = fixture.model<StarsViewModel>()
        composeRule.onNodeWithText(text(R.string.stars_my_lists)).performClick()
        tab(R.string.nav_tab_gist)
        val gist = fixture.model<GistViewModel>()
        composeRule.runOnIdle {
            gist.selectCategory(GistCategory.ORIGINAL)
            gist.selectSort(GistSort.OLDEST_CREATED)
            gist.selectVisibility(GistVisibility.PRIVATE)
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        val gistTop = composeRule.onNodeWithText("alpha-gist-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        tab(R.string.nav_tab_settings)
        val settings = fixture.model<SettingsViewModel>()
        expandAccounts("alpha")

        tab(R.string.common_repository)
        assertSame(repos, fixture.model<ReposViewModel>())
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals("  Alpha draft  ", repos.state.value.filters.query)
        assertEquals(repoTop, composeRule.onNodeWithText("alpha-repo-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        tab(R.string.nav_tab_gist)
        assertSame(gist, fixture.model<GistViewModel>())
        assertEquals(GistVisibility.PRIVATE, gist.state.value.selectedVisibility)
        assertEquals(gistTop, composeRule.onNodeWithText("alpha-gist-12").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        tab(R.string.nav_tab_stars)
        assertSame(stars, fixture.model<StarsViewModel>())
        assertTrue(stars.state.value.listsSectionExpanded)
        tab(R.string.nav_tab_settings)
        assertSame(settings, fixture.model<SettingsViewModel>())
        composeRule.onNodeWithText("@beta").assertExists()
        tab(R.string.nav_tab_home)
        assertSame(home, fixture.model<HomeViewModel>())

        // 同一类型在 Tab 根和真实 push entry 中也不能共用 Store/SavedStateHandle。
        composeRule.onNode(hasText(text(R.string.common_repository)) and hasClickAction() and !tabRole).performClick()
        composeRule.waitForIdle()
        val pushedRepos = fixture.model<ReposViewModel>()
        assertNotSame(repos, pushedRepos)
        assertNotSame(fixture.creation(repos).handle, fixture.creation(pushedRepos).handle)
        assertEquals(RepoFilters(), pushedRepos.state.value.filters)
        assertEquals(2, fixture.creations.count { it.model is ReposViewModel })
        assertTrue(fixture.creations.all { it.model.accountEpoch === epoch })
    }

    @Test
    fun `switch discards every tab store saved state permission draft and old navigation callback`() {
        launch()
        visitAllTabs()
        val previous = fixture.creations.toList()
        tab(R.string.common_repository)
        val repos = fixture.model<ReposViewModel>()
        composeRule.onNodeWithContentDescription(text(R.string.repo_create_title)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.repos_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("alpha-only")
        composeRule.runOnIdle { repos.selectVisibility(RepoVisibility.PRIVATE) }
        tab(R.string.nav_tab_gist)
        val gist = fixture.model<GistViewModel>()
        composeRule.runOnIdle { gist.selectVisibility(GistVisibility.PRIVATE) }
        val oldCreate = composeRule.onNodeWithContentDescription(text(R.string.gist_create_title))
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        composeRule.onNodeWithContentDescription(text(R.string.gist_create_title)).performClick()
        composeRule.waitForIdle()
        val draft = fixture.model<GistCreateViewModel>()
        composeRule.onNodeWithText(text(R.string.gist_create_description_optional)).performTextInput("alpha draft")
        composeRule.runOnIdle { draft.updateFilename(0, "alpha.txt") }

        switchTo(fixture.beta)

        assertTrue((previous + fixture.creation(draft)).all { it.cleared })
        assertTrue((previous + fixture.creation(draft)).all { !it.model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive })
        assertEquals("beta", fixture.model<HomeViewModel>().state.value.profile!!.user.login)
        composeRule.runOnIdle { oldCreate() }
        composeRule.onNode(hasText(text(R.string.nav_tab_home)) and tabRole).assertExists()
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.runOnIdle { draft.create() }
        coVerify(exactly = 0) { fixture.gists.createGist(any(), any(), any()) }

        tab(R.string.common_repository)
        assertEquals(RepoFilters(), fixture.model<ReposViewModel>().state.value.filters)
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("beta-repo-00").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.repo_create_title)).assertDoesNotExist()
        tab(R.string.nav_tab_gist)
        assertEquals(GistVisibility.ALL, fixture.model<GistViewModel>().state.value.selectedVisibility)
        composeRule.onNodeWithText("beta-gist-00").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.gist_create_title)).performClick()
        composeRule.waitForIdle()
        assertEquals("", fixture.model<GistCreateViewModel>().state.value.description)
        assertEquals("", fixture.model<GistCreateViewModel>().state.value.files.single().filename)

        switchTo(fixture.alpha)
        tab(R.string.common_repository)
        assertEquals(RepoFilters(), fixture.model<ReposViewModel>().state.value.filters)
        assertNotSame(repos, fixture.model<ReposViewModel>())
        assertNotSame(fixture.creation(repos).handle, fixture.creation(fixture.model<ReposViewModel>()).handle)
    }

    @Test
    fun `real settings account choice replaces session without carrying expanded account state`() {
        launch()
        tab(R.string.nav_tab_settings)
        val oldModels = fixture.creations.toList()
        expandAccounts("alpha")
        composeRule.onNode(hasText("@beta") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { fixture.auth.switchAccount(fixture.beta.key) }
        assertEquals(fixture.beta.key, accounts.session.state.value.activeAccount!!.key)
        assertTrue(oldModels.all { it.cleared })
        tab(R.string.nav_tab_settings)
        composeRule.onNodeWithText("@beta").assertExists()
        composeRule.onNodeWithText(text(R.string.accounts_pat)).assertDoesNotExist()
        assertFalse(fixture.model<LoginViewModel>().state.value.isLoading)
    }

    @Test
    fun `configuration recreation retains all five stores handles and session saveable state`() {
        launch()
        visitAllTabs()
        tab(R.string.common_repository)
        val repos = fixture.model<ReposViewModel>()
        composeRule.onNodeWithContentDescription(text(R.string.repos_search)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("retained")
        composeRule.runOnIdle { repos.selectVisibility(RepoVisibility.PRIVATE) }
        tab(R.string.nav_tab_gist)
        composeRule.runOnIdle { fixture.model<GistViewModel>().selectCategory(GistCategory.FORKED) }
        tab(R.string.nav_tab_settings)
        expandAccounts("alpha")
        val epoch = accounts.session.epoch
        val models = fixture.creations.toList()
        val activity = controller!!.get()

        composeRule.runOnUiThread { controller!!.recreate() }
        composeRule.waitForIdle()

        assertNotSame(activity, controller!!.get())
        assertSame(epoch, accounts.session.epoch)
        assertEquals(models.size, fixture.creations.size)
        assertTrue(models.none { it.cleared })
        composeRule.onNodeWithText("@beta").assertExists()
        tab(R.string.common_repository)
        assertSame(repos, fixture.model<ReposViewModel>())
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals("retained", repos.state.value.filters.query)
        tab(R.string.nav_tab_gist)
        assertEquals(GistCategory.FORKED, fixture.model<GistViewModel>().state.value.selectedCategory)

        composeRule.runOnUiThread { controller!!.close(); controller = null }
        assertTrue(models.all { it.cleared })
    }

    @Test
    fun `冷启动Gist深链进入已有详情页并消费一次`() {
        launch(DeepLinkDestination.GistDetail("abc123"))
        composeRule.onAllNodesWithText("Deep link abc123")[0].assertIsDisplayed()
        coVerify(exactly = 1) { fixture.gists.getGist("abc123") }
        assertEquals(null, fixture.deepLinkDestination)
        assertEquals(1, fixture.consumedDeepLinks)
    }

    @Test
    fun `未登录的Gist深链在登录后继续打开`() {
        launch(DeepLinkDestination.GistDetail("abc123"), loggedIn = false)
        coVerify(exactly = 0) { fixture.gists.getGist(any()) }
        assertEquals(0, fixture.consumedDeepLinks)
        switchTo(fixture.alpha)
        composeRule.onAllNodesWithText("Deep link abc123")[0].assertIsDisplayed()
        coVerify(exactly = 1) { fixture.gists.getGist("abc123") }
        assertEquals(1, fixture.consumedDeepLinks)
    }

    @Test
    fun `运行中收到Gist深链会打开对应详情`() {
        launch()
        composeRule.runOnIdle { fixture.deepLinkDestination = DeepLinkDestination.GistDetail("def456") }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Deep link def456")[0].assertIsDisplayed()
        coVerify(exactly = 1) { fixture.gists.getGist("def456") }
        assertEquals(1, fixture.consumedDeepLinks)
    }

    @Test
    fun `浏览器不可用时未登录也能显示全局错误`() {
        composeRule.mainClock.autoAdvance = false
        launch(DeepLinkDestination.BrowserUnavailable, loggedIn = false)
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertEquals(null, fixture.deepLinkDestination)
        assertEquals(1, fixture.consumedDeepLinks)
        composeRule.onNodeWithText(text(R.string.deep_link_browser_unavailable)).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = true
    }

    private fun launch(destination: DeepLinkDestination? = null, loggedIn: Boolean = true) {
        fixture.deepLinkDestination = destination
        if (loggedIn) accounts.session.replace(listOf(fixture.alpha, fixture.beta), fixture.alpha.key)
        else accounts.session.replace(emptyList(), null)
        HostActivity.fixture = fixture
        composeRule.runOnUiThread { controller = Robolectric.buildActivity(HostActivity::class.java).setup() }
        composeRule.waitForIdle()
    }

    private fun visitAllTabs() {
        listOf(R.string.common_repository, R.string.nav_tab_stars, R.string.nav_tab_gist, R.string.nav_tab_settings).forEach(::tab)
    }

    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun tab(resource: Int) {
        composeRule.onNode(hasText(text(resource)) and tabRole).performClick()
        composeRule.waitForIdle()
    }

    private fun expandAccounts(login: String) {
        composeRule.onNode(hasText("@$login") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
    }

    private fun switchTo(account: GitHubAccount) {
        composeRule.runOnIdle { accounts.session.replace(listOf(fixture.alpha, fixture.beta), account.key) }
        composeRule.waitForIdle()
    }

    private fun text(resource: Int) = ApplicationProvider.getApplicationContext<Context>().getString(resource)

    class HostActivity : ComponentActivity(), GeneratedComponentManager<Any> {
        companion object { internal var fixture: NavigationFixture? = null }
        override fun generatedComponent(): Any = checkNotNull(fixture).activityComponent
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val data = checkNotNull(fixture)
            setContent {
                MaterialTheme {
                    val state by data.session.state.collectAsStateWithLifecycle()
                    GitMobNavGraph(
                        state, data.session, data.errors, data.repoEvents, ThemePreference(), false,
                        deepLinkDestination = data.deepLinkDestination,
                        onDeepLinkConsumed = { data.deepLinkDestination = null; data.consumedDeepLinks++ },
                    )
                }
            }
        }
    }
}

/** 只替换 Hilt 组件绑定；Screen 的默认 hiltViewModel、真实 Factory/extras/handle 和导航均不替换。 */
internal class NavigationFixture(val session: AccountSession) {
    val alpha = GitHubAccount("1", 1, "U_1", "alpha", "Alpha")
    val beta = GitHubAccount("2", 2, "U_2", "beta", "Beta")
    val users = mockk<UserRepository>()
    private val repos = mockk<RepoRepository>(relaxUnitFun = true)
    val gists = mockk<GistRepository>()
    var deepLinkDestination by mutableStateOf<DeepLinkDestination?>(null)
    var consumedDeepLinks = 0
    private val stars = mockk<StarRepository>()
    val auth = mockk<AuthRepository>()
    private val preferences = mockk<ThemePreferenceStore>()
    val errors = ErrorEventBus()
    val repoEvents = RepoUpdateEventBus()
    private val gistEvents = GistUpdateEventBus()
    val creations = mutableListOf<Creation>()
    data class Creation(val model: AccountViewModel, val handle: SavedStateHandle, var cleared: Boolean = false)

    init {
        coEvery { gists.getGist(any()) } coAnswers {
            val id = firstArg<String>()
            ApiResult.Success(GistDetail(
                id = id, nodeId = "node_$id", url = "https://gist.github.com/owner/$id",
                description = "Deep link $id", isPublic = true, owner = GistUser("owner", null, null),
                files = emptyList(), commentCount = 0, commentsEnabled = false,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
                forkOf = null, latestVersion = "sha", viewerLogin = "alpha", isStarred = false,
                stargazerCount = 0, forkCount = 0,
            ))
        }
        coEvery { gists.getComments(any(), any()) } returns ApiResult.Success(GistCommentPage(emptyList(), null))
        coEvery { gists.getOldestVersion(any()) } returns ApiResult.Success(null)
        every { users.profileUpdatesFor(any()) } returns emptyFlow()
        every { auth.accountState } returns session.state
        coEvery { auth.switchAccount(any()) } coAnswers {
            session.replace(session.state.value.accounts, firstArg())
            ApiResult.Success(Unit)
        }
        coEvery { users.getViewerProfile() } coAnswers {
            val login = AccountContext.ensureCurrent().login!!
            ApiResult.Success(ViewerProfile(GHUser("U_$login", login, "Profile $login"), null, 1, 0,
                pinnedRepos = emptyList(), followState = FollowState(true, false, false)))
        }
        coEvery { repos.getRepos(any(), any(), any(), any()) } coAnswers {
            val login = AccountContext.ensureCurrent().login!!
            val owner = RepositoryCreateOwner("U_$login", login, "Repos $login", null, RepositoryCreateOwnerType.USER, login == "alpha")
            val items = (0..24).map { index ->
                RepoListItem("$login-repo-${index.toString().padStart(2, '0')}", login, null, null,
                    RepoVisibility.PRIVATE, false, false, true, Instant.EPOCH, Instant.EPOCH, null,
                    null, null, null, null, 0, 0, 0, emptyList(), "main")
            }
            ApiResult.Success(RepoList(items.size, items, false, null, RepositoryListOwnerContext(owner)))
        }
        coEvery { stars.getLists() } returns ApiResult.Success(emptyList())
        coEvery { stars.getViewerStarred(any()) } returns ApiResult.Success(PagedStarredRepos(0, emptyList(), false, null))
        coEvery { gists.getGists(any(), any(), any(), any(), any()) } coAnswers {
            val login = AccountContext.ensureCurrent().login!!
            val items = (0..24).map { index ->
                GistListItem("$login-$index", "$login-$index", login, "$login-gist-${index.toString().padStart(2, '0')}",
                    "https://gist.github.com/$login/$index", false, false, true, "2026-01-01T00:00:00Z",
                    "2026-01-01T00:00:00Z", 0, 0, null, 1, false)
            }
            ApiResult.Success(GistPage(items, false, null))
        }
    }

    fun creation(model: AccountViewModel) = creations.single { it.model === model }
    inline fun <reified T : AccountViewModel> model(): T = creations.last { it.model is T }.model as T

    private val factories: Map<Class<*>, (SavedStateHandle) -> AccountViewModel> = mapOf(
        HomeViewModel::class.java to { HomeViewModel(users, errors, repoEvents, session) },
        ReposViewModel::class.java to { ReposViewModel(repos, repoEvents, errors, it, session) },
        StarsViewModel::class.java to { StarsViewModel(stars, repoEvents, errors, session) },
        GistViewModel::class.java to { GistViewModel(gists, errors, it, gistEvents, session) },
        SettingsViewModel::class.java to { SettingsViewModel(preferences, auth, errors, session) },
        LoginViewModel::class.java to { LoginViewModel(auth, errors, session) },
        GistDetailViewModel::class.java to {
            GistDetailViewModel(gists, mockk(), mockk(), errors, mockk(), mockk(), gistEvents, session)
        },
        GistCreateViewModel::class.java to { GistCreateViewModel(gists, errors, gistEvents, session) },
    )

    // Hilt 2.60.1 的 ActivityCreatorEntryPoint 为包私有接口，Proxy 只用于提供该测试组件。
    val activityComponent: Any = Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint").let { entryPoint ->
        Proxy.newProxyInstance(entryPoint.classLoader, arrayOf(entryPoint, GeneratedComponent::class.java)) { _, method, _ ->
            when (method.name) {
                "getViewModelKeys" -> factories.keys.associateWith { true }
                "getViewModelComponentBuilder" -> ComponentBuilder()
                else -> error("Unexpected Hilt entry point: ${method.name}")
            }
        }
    }

    private inner class ComponentBuilder : ViewModelComponentBuilder {
        private lateinit var handle: SavedStateHandle
        override fun savedStateHandle(handle: SavedStateHandle): ViewModelComponentBuilder = apply { this.handle = handle }
        override fun viewModelLifecycle(lifecycle: ViewModelLifecycle): ViewModelComponentBuilder = this
        override fun build(): ViewModelComponent = object : ViewModelComponent, GeneratedComponent, HiltViewModelFactory.ViewModelFactoriesEntryPoint {
            override fun getHiltViewModelAssistedMap(): Map<Class<*>, Any> = emptyMap()
            override fun getHiltViewModelMap(): Map<Class<*>, Provider<ViewModel>> = factories.mapValues { (_, create) ->
                Provider {
                    create(handle).also { model ->
                        val creation = Creation(model, handle)
                        creations += creation
                        model.addCloseable { creation.cleared = true }
                    }
                }
            }
        }
    }
}
