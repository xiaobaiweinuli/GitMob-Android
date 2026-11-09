package com.gitmob.app.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.testutil.AccountSessionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule

class RepositoryRouteUpdateTest {
    @get:Rule val accounts = AccountSessionRule()
    @Test
    fun `address replacement updates repository routes and clears cached permission`() {
        val route = RepoCodeRoute("old", "repo", "main", "docs", RepoPermission.ADMIN)

        val updated = route.replaceRepositoryAddress("old", "repo", "new", "renamed") as RepoCodeRoute

        assertEquals("new", updated.owner)
        assertEquals("renamed", updated.name)
        assertEquals("main", updated.ref)
        assertEquals("docs", updated.path)
        assertNull(updated.permission)
    }

    @Test
    fun `address replacement preserves repository code search directory and fork scope`() {
        assertEquals(
            RepoCodeSearchRoute("new", "renamed", "app/src", isFork = true),
            RepoCodeSearchRoute("old", "repo", "app/src", isFork = true).replaceRepositoryAddress("old", "repo", "new", "renamed"),
        )
    }

    @Test
    fun `pull request editor updates base and head addresses independently`() {
        val route = RepoPullRequestEditorRoute(
            owner = "old",
            name = "repo",
            permission = RepoPermission.WRITE,
            baseOwner = "old",
            baseName = "repo",
            baseRef = "main",
            headOwner = "upstream",
            headName = "repo",
            headRef = "main",
        )

        val updated = route.replaceRepositoryAddress("old", "repo", "new", "renamed") as RepoPullRequestEditorRoute

        assertEquals("new", updated.owner)
        assertEquals("renamed", updated.name)
        assertEquals("new", updated.baseOwner)
        assertEquals("renamed", updated.baseName)
        assertEquals("upstream", updated.headOwner)
        assertEquals("repo", updated.headName)
        assertNull(updated.permission)
    }

    @Test
    fun `address change updates matching routes in every tab stack`() {
        val homeStack = NavBackStack<NavKey>(HomeRoute, RepoDetailRoute("old", "repo"))
        val starsStack = NavBackStack<NavKey>(StarsRoute, RepoCodeRoute("old", "repo", "main"))
        val navigator = navigator(homeStack, starsStack)

        navigator.onRepositoryUpdate(RepoUpdateEvent.RepositoryAddressChanged("old", "repo", "new", "renamed"))

        assertEquals(RepoDetailRoute("new", "renamed"), homeStack.last())
        assertEquals(RepoCodeRoute("new", "renamed", "main"), starsStack.last())
    }

    @Test
    fun `address replacement updates repository social routes`() {
        assertEquals(
            RepoStargazersRoute("new", "renamed"),
            RepoStargazersRoute("old", "repo").replaceRepositoryAddress("old", "repo", "new", "renamed"),
        )
        assertEquals(
            RepoForksRoute("new", "renamed"),
            RepoForksRoute("old", "repo").replaceRepositoryAddress("old", "repo", "new", "renamed"),
        )
    }

    @Test
    fun `repository deletion removes matching routes from every tab and opens repositories tab`() {
        val homeStack = NavBackStack<NavKey>(HomeRoute, RepoDetailRoute("old", "repo"), ProfileRoute("octo"))
        val starsStack = NavBackStack<NavKey>(StarsRoute, RepoCodeRoute("old", "repo", "main"))
        val navigator = navigator(homeStack, starsStack)

        navigator.onRepositoryUpdate(RepoUpdateEvent.RepositoryDeleted("old", "repo"))

        assertEquals(listOf(HomeRoute, ProfileRoute("octo")), homeStack.toList())
        assertEquals(listOf(StarsRoute), starsStack.toList())
        assertEquals(ReposRoute, navigator.state.topLevelRoute)
    }


    @Test
    fun `log route survives serialization and repository address changes with identifiers only`() {
        val route = RepoActionStepLogRoute("old", "repo", 42, 3, 101, 8)
        val encoded = kotlinx.serialization.json.Json.encodeToString(RepoActionStepLogRoute.serializer(), route)
        assertEquals(route, kotlinx.serialization.json.Json.decodeFromString(RepoActionStepLogRoute.serializer(), encoded))
        assertEquals(
            RepoActionStepLogRoute("new", "renamed", 42, 3, 101, 8),
            route.replaceRepositoryAddress("old", "repo", "new", "renamed"),
        )
        assertEquals(setOf("owner", "name", "runId", "attempt", "jobId", "stepNumber"),
            (kotlinx.serialization.json.Json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject).keys)
    }

    private fun navigator(
        homeStack: NavBackStack<NavKey>,
        starsStack: NavBackStack<NavKey>,
    ) = GitMobNavigator(
        GitMobNavState(
            startRoute = HomeRoute,
            topLevelRoute = mutableStateOf(HomeRoute),
            backStacks = mapOf(
                HomeRoute to homeStack,
                ReposRoute to NavBackStack<NavKey>(ReposRoute),
                StarsRoute to starsStack,
            ),
        ),
        accounts.session.epoch,
    )
}
