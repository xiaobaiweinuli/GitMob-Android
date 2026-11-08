package com.gitmob.app.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gitmob.app.R

private data class BottomTab(val route: Route, @StringRes val labelRes: Int, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(HomeRoute, R.string.nav_tab_home, Icons.Default.Home),
    BottomTab(ReposRoute, R.string.common_repository, Icons.Default.Storage),
    BottomTab(StarsRoute, R.string.nav_tab_stars, Icons.Default.Star),
    BottomTab(GistRoute, R.string.nav_tab_gist, Icons.Default.Code),
    BottomTab(SettingsRoute, R.string.nav_tab_settings, Icons.Default.Settings),
)

/** The bar and its space belong to this root entry for the entire push/pop transition. */
@Composable
internal fun GitMobTabRoot(
    route: Route,
    onTabClick: (Route) -> Unit,
    modifier: Modifier = Modifier,
    navigationBarInsets: WindowInsets = NavigationBarDefaults.windowInsets,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(windowInsets = navigationBarInsets) {
                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab.route == route,
                        onClick = { onTabClick(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        // NavigationBar includes the system bottom inset. Mark its occupied space consumed so
        // descendants do not add that inset again (including IME/layout inset calculations).
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            content()
        }
    }
}
