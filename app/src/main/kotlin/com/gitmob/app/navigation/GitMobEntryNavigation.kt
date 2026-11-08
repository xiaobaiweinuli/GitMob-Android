package com.gitmob.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

// Supplied by each tab's entry provider, including entries composed only for a back preview.
internal val LocalNavigationTab = staticCompositionLocalOf<Route> {
    error("Navigation actions must be created inside a tab entry")
}

@Composable
internal fun rememberEntryNavigation(
    navigator: GitMobNavigator,
    route: Route,
): GitMobEntryNavigation {
    val tab = LocalNavigationTab.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(navigator, tab, route, lifecycle) {
        GitMobEntryNavigation(navigator, tab, route, lifecycle)
    }
}

/** Click navigation only. Async results and system back keep using GitMobNavigator directly. */
internal class GitMobEntryNavigation(
    private val navigator: GitMobNavigator,
    private val tab: Route,
    private val route: Route,
    private val lifecycle: Lifecycle,
) {
    fun run(action: () -> Unit) {
        // Lifecycle alone updates after composition. Check the stack as well so a second click
        // from the same frame cannot act on the page that has just replaced this entry.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            navigator.state.topLevelRoute == tab &&
            navigator.state.backStacks[tab]?.lastOrNull() == route
        ) {
            action()
        }
    }

    fun navigate(destination: Route) = run { navigator.navigate(destination) }

    fun goBack() = run { navigator.goBack() }

    fun selectTab(destination: Route) = run {
        if (destination == tab) navigator.onReselect(destination)
        else navigator.navigate(destination)
    }
}
