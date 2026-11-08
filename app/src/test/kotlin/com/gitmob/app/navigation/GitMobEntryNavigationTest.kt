package com.gitmob.app.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.cash.turbine.test
import com.gitmob.app.testutil.AccountSessionRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GitMobEntryNavigationTest {
    @get:Rule val accounts = AccountSessionRule()
    private val state = GitMobNavState(
        HomeRoute, mutableStateOf<Route>(SettingsRoute),
        listOf(HomeRoute, ReposRoute, StarsRoute, GistRoute, SettingsRoute)
            .associateWith { NavBackStack<NavKey>(it) },
    )
    private val navigator by lazy { GitMobNavigator(state, accounts.session.epoch) }
    private var lifecycleState = Lifecycle.State.RESUMED
    private val lifecycle = mockk<Lifecycle> { every { currentState } answers { lifecycleState } }
    private fun clicks(route: Route = SettingsRoute, tab: Route = SettingsRoute) =
        GitMobEntryNavigation(navigator, tab, route, lifecycle)
    private fun stack() = state.backStacks.getValue(state.topLevelRoute).toList()

    @Test
    fun `two different clicks from the outgoing page in the same frame only navigate once`() {
        val click = clicks()
        click.navigate(AboutRoute)
        click.navigate(AdvancedSettingsRoute())
        assertEquals(listOf(SettingsRoute, AboutRoute), stack())
    }

    @Test
    fun `non resumed entries cannot navigate back select tabs or open links`() {
        val click = clicks()
        var opened = 0
        listOf(Lifecycle.State.INITIALIZED, Lifecycle.State.CREATED, Lifecycle.State.STARTED, Lifecycle.State.DESTROYED).forEach {
            lifecycleState = it
            click.navigate(AboutRoute)
            click.goBack()
            click.selectTab(HomeRoute)
            click.run { opened++ }
        }
        assertEquals(SettingsRoute, state.topLevelRoute)
        assertEquals(listOf(SettingsRoute), stack())
        assertEquals(0, opened)
    }

    @Test
    fun `resumed entry accepts clicks again after a cancelled transition`() {
        val click = clicks()
        lifecycleState = Lifecycle.State.STARTED
        click.navigate(AboutRoute)
        lifecycleState = Lifecycle.State.RESUMED
        click.navigate(AboutRoute)
        assertEquals(listOf(SettingsRoute, AboutRoute), stack())
    }

    @Test
    fun `repeated toolbar back never pops the newly revealed page`() {
        navigator.navigate(AdvancedSettingsRoute())
        navigator.navigate(TranslationSettingsRoute)
        val click = clicks(TranslationSettingsRoute)
        click.goBack()
        click.goBack()
        assertEquals(listOf(SettingsRoute, AdvancedSettingsRoute()), stack())
    }

    @Test
    fun `old tab callback is ignored even if another tab has the same route`() {
        navigator.navigate(AboutRoute)
        val old = clicks(AboutRoute)
        navigator.navigate(HomeRoute)
        navigator.navigate(AboutRoute)
        old.navigate(ProfileRoute("octo"))
        old.goBack()
        assertEquals(listOf(HomeRoute, AboutRoute), stack())
    }

    @Test
    fun `reselect emits its existing event and keeps the root stack`() = runTest {
        navigator.reselectEvents.test {
            clicks().selectTab(SettingsRoute)
            assertEquals(SettingsRoute, awaitItem())
            assertEquals(listOf(SettingsRoute), stack())
            clicks().selectTab(HomeRoute)
            expectNoEvents()
            assertEquals(HomeRoute, state.topLevelRoute)
        }
    }

    @Test
    fun `async results and deep links remain independent of click lifecycle`() {
        lifecycleState = Lifecycle.State.STARTED
        clicks().navigate(AboutRoute)
        navigator.navigate(GistDetailRoute("linked"))
        assertEquals(listOf(SettingsRoute, GistDetailRoute("linked")), stack())
        navigator.goBack()
        navigator.navigate(RepoDetailRoute("octo", "created"))
        assertEquals(listOf(SettingsRoute, RepoDetailRoute("octo", "created")), stack())
    }

    @Test
    fun `outgoing entry cannot open a document or external link`() {
        val click = clicks()
        navigator.navigate(AboutRoute)
        var opened = 0
        click.run { opened++ }
        assertEquals(0, opened)
    }

    @Test
    fun `old account click cannot change its former stack`() {
        val click = clicks()
        accounts.session.replace(emptyList(), null)
        click.navigate(AboutRoute)
        click.goBack()
        click.selectTab(HomeRoute)
        assertEquals(SettingsRoute, state.topLevelRoute)
        assertEquals(listOf(SettingsRoute), stack())
    }
}
