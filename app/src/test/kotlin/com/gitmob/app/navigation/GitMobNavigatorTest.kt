package com.gitmob.app.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.testutil.AccountSessionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GitMobNavigatorTest {
    @get:Rule val accounts = AccountSessionRule()
    private val state = GitMobNavState(
        HomeRoute, mutableStateOf<Route>(HomeRoute),
        listOf(HomeRoute, ReposRoute, StarsRoute, GistRoute, SettingsRoute)
            .associateWith { NavBackStack<NavKey>(it) },
    )
    private val navigator by lazy { GitMobNavigator(state, accounts.session.epoch) }
    private fun stack(tab: Route = state.topLevelRoute) = state.backStacks.getValue(tab).toList()

    @Test
    fun `repeated settings destinations only push once`() {
        navigator.navigate(SettingsRoute)
        repeat(4) { navigator.navigate(AboutRoute) }
        assertEquals(listOf(SettingsRoute, AboutRoute), stack())
        navigator.goBack()
        repeat(3) { navigator.navigate(AdvancedSettingsRoute()) }
        repeat(3) { navigator.navigate(TranslationSettingsRoute) }
        assertEquals(listOf(SettingsRoute, AdvancedSettingsRoute(), TranslationSettingsRoute), stack())
    }

    @Test
    fun `opening the same search link again replaces its editable entry without stacking a duplicate`() {
        val initial = RepoIssuesRoute("o", "r", initialQuery = "state:open 发布")
        val first = initial.withSearchVisit("first")
        val second = initial.withSearchVisit("second")
        navigator.navigate(first, withoutAnimation = true)
        navigator.navigate(first, withoutAnimation = true)
        navigator.navigate(second, withoutAnimation = true)
        assertEquals(listOf(HomeRoute, second), stack())
        assertTrue(navigator.withoutForwardAnimation)
        navigator.navigate(RepoPullRequestsRoute("o", "r", initialQuery = "is:merged").withSearchVisit("third"))
        navigator.goBack()
        assertEquals(listOf(HomeRoute, second), stack())
    }

    @Test
    fun `equal parameterized routes from separate instances only push once`() {
        repeat(3) { navigator.navigate(RepoDetailRoute("octo", "repo")) }
        assertEquals(listOf(HomeRoute, RepoDetailRoute("octo", "repo")), stack())
    }

    @Test
    fun `external entry skips only the next forward transition`() {
        navigator.navigate(RepoDetailRoute("octo", "repo"), withoutAnimation = true)
        assertTrue(navigator.withoutForwardAnimation)
        navigator.navigate(ProfileRoute("octo"))
        assertFalse(navigator.withoutForwardAnimation)
        // 栈顶已是目标时没有转场可跳，不能把标记留给下一次 push。
        navigator.navigate(ProfileRoute("octo"), withoutAnimation = true)
        assertFalse(navigator.withoutForwardAnimation)
    }

    @Test
    fun `duplicate external targets retain the active transition mode`() {
        val route = RepoDetailRoute("octo", "repo")
        navigator.navigate(route, withoutAnimation = true)
        navigator.navigate(route)
        navigator.navigate(route, withoutAnimation = true)
        navigator.navigate(HomeRoute)
        assertTrue(navigator.withoutForwardAnimation)
        assertEquals(listOf(HomeRoute, route), stack())
    }

    @Test
    fun `back and tab changes restore ordinary forward transitions`() {
        navigator.navigate(RepoDetailRoute("octo", "repo"), withoutAnimation = true)
        assertTrue(navigator.goBack())
        assertFalse(navigator.withoutForwardAnimation)
        navigator.navigate(RepoDetailRoute("octo", "repo"), withoutAnimation = true)
        navigator.navigate(SettingsRoute)
        assertFalse(navigator.withoutForwardAnimation)
        assertEquals(listOf(HomeRoute, RepoDetailRoute("octo", "repo")), stack(HomeRoute))
    }

    @Test
    fun `external links push onto the active tab without changing other stacks`() {
        navigator.navigate(SettingsRoute)
        navigator.navigate(AboutRoute)
        val first = RepoDetailRoute("tanmoumou252", "skill_toolkit")
        val second = RepoDetailRoute("OpenMinis", "ish-pkgs")
        navigator.navigate(first, withoutAnimation = true)
        navigator.navigate(second, withoutAnimation = true)
        assertTrue(navigator.withoutForwardAnimation)
        assertEquals(listOf(SettingsRoute, AboutRoute, first, second), stack())
        assertEquals(listOf(HomeRoute), stack(HomeRoute))
        assertTrue(navigator.goBack())
        assertEquals(first, stack().last())
        assertFalse(navigator.withoutForwardAnimation)
    }

    @Test
    fun `revoked account cannot alter external navigation or its transition mode`() {
        navigator.navigate(RepoDetailRoute("octo", "repo"), withoutAnimation = true)
        accounts.session.replace(emptyList(), null)
        navigator.navigate(ProfileRoute("octo"))
        navigator.navigate(SettingsRoute)
        navigator.goBack()
        assertTrue(navigator.withoutForwardAnimation)
        assertEquals(listOf(HomeRoute, RepoDetailRoute("octo", "repo")), stack())
    }

    @Test
    fun `different repository owner name ref path and permission remain distinct`() {
        val routes = listOf(
            RepoCodeRoute("one", "repo", "main", "src", RepoPermission.READ),
            RepoCodeRoute("two", "repo", "main", "src", RepoPermission.READ),
            RepoCodeRoute("two", "other", "main", "src", RepoPermission.READ),
            RepoCodeRoute("two", "other", "dev", "src", RepoPermission.READ),
            RepoCodeRoute("two", "other", "dev", "docs", RepoPermission.READ),
            RepoCodeRoute("two", "other", "dev", "docs", RepoPermission.WRITE),
        )
        routes.forEach(navigator::navigate)
        assertEquals(listOf(HomeRoute) + routes, stack())
    }

    @Test
    fun `a destination deeper in history can still be revisited`() {
        navigator.navigate(ProfileRoute("octo"))
        navigator.navigate(RepoDetailRoute("octo", "repo"))
        navigator.navigate(ProfileRoute("octo"))
        assertEquals(4, stack().size)
        navigator.goBack()
        assertEquals(RepoDetailRoute("octo", "repo"), stack().last())
    }

    @Test
    fun `same destination in another tab does not block current tab`() {
        navigator.navigate(AboutRoute)
        navigator.navigate(SettingsRoute)
        navigator.navigate(AboutRoute)
        assertEquals(listOf(HomeRoute, AboutRoute), stack(HomeRoute))
        assertEquals(listOf(SettingsRoute, AboutRoute), stack(SettingsRoute))
    }

    @Test
    fun `tab selection retains every independent stack and exits through home`() {
        listOf(HomeRoute, ReposRoute, StarsRoute, GistRoute, SettingsRoute).forEach {
            navigator.navigate(it)
            navigator.navigate(ProfileRoute(it.toString()))
        }
        listOf(HomeRoute, ReposRoute, StarsRoute, GistRoute, SettingsRoute).forEach {
            navigator.navigate(it)
            assertEquals(listOf(it, ProfileRoute(it.toString())), stack())
            assertTrue(navigator.goBack())
        }
        assertTrue(navigator.goBack())
        assertEquals(HomeRoute, state.topLevelRoute)
        assertFalse(navigator.goBack())
    }

    @Test
    fun `save result returning to the same detail does not create a duplicate`() {
        val detail = RepoIssueDetailRoute("octo", "repo", 7)
        navigator.navigate(detail)
        navigator.navigate(RepoIssueEditorRoute("octo", "repo", 7))
        navigator.goBack()
        navigator.navigate(detail)
        assertEquals(listOf(HomeRoute, detail), stack())
    }

    @Test
    fun `revoked account cannot push pop or select tabs`() {
        navigator.navigate(AboutRoute)
        accounts.session.replace(emptyList(), null)
        navigator.navigate(TranslationSettingsRoute)
        navigator.navigate(SettingsRoute)
        assertTrue(navigator.goBack())
        assertEquals(HomeRoute, state.topLevelRoute)
        assertEquals(listOf(HomeRoute, AboutRoute), stack())
    }
}
