package com.gitmob.app.navigation

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.gitmob.app.R
import com.gitmob.app.core.error.ErrorBannerHost
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.ui.branches.BranchesScreen
import com.gitmob.app.ui.common.PlaceholderScreen
import com.gitmob.app.ui.gist.GistScreen
import com.gitmob.app.ui.home.HomeScreen
import com.gitmob.app.ui.inbox.InboxScreen
import com.gitmob.app.ui.login.LoginScreen
import com.gitmob.app.ui.profile.ProfileScreen
import com.gitmob.app.ui.repodetail.RepoDetailScreen
import com.gitmob.app.ui.repoissues.RepoIssueDetailScreen
import com.gitmob.app.ui.repoissues.RepoIssueListScreen
import com.gitmob.app.ui.repos.ReposScreen
import com.gitmob.app.ui.settings.AboutScreen
import com.gitmob.app.ui.settings.AppearanceScreen
import com.gitmob.app.ui.settings.SettingsScreen
import com.gitmob.app.ui.stars.StarsScreen
import com.gitmob.app.ui.userlist.OrgMembersScreen
import com.gitmob.app.ui.userlist.RepoWatchersScreen
import com.gitmob.app.ui.userlist.UserListMode
import com.gitmob.app.ui.userlist.UserListScreen
import com.gitmob.app.ui.userstars.UserStarredReposScreen
import com.gitmob.app.ui.work.WorkDiscussionListScreen
import com.gitmob.app.ui.work.WorkIssueListScreen
import com.gitmob.app.ui.work.WorkPullRequestListScreen

private data class BottomTab(val route: Route, @StringRes val labelRes: Int, val icon: ImageVector)

/**
 * 全站统一的页面转场：淡入淡出（draw 阶段 alpha，无位移）。
 *
 * 之前是 220ms layout 阶段横滑（slideInHorizontally）——入场页要在动画进行中从零
 * compose+measure+layout（Nav3 SinglePaneScene 只渲染栈顶 entry，切 Tab 两端都重建），
 * 重建高峰和逐帧布局抢主线程，掉帧直接表现为滑动顿挫。fade 没有"运动"可掉帧，
 * 重组抖动被淡入遮蔽（orange-cloud-main / Nav2 官方默认同款观感），
 * 也顺带消解了"横滑方向恒定"的问题（fade 无方向）。
 * 详见 文档/tab-switch-jank-and-back-animation-analysis.md。
 */
private fun crossFadeTransition(): ContentTransform =
    fadeIn(animationSpec = tween(260)) togetherWith fadeOut(animationSpec = tween(260))

private val bottomTabs = listOf(
    BottomTab(HomeRoute, R.string.nav_tab_home, Icons.Default.Home),
    BottomTab(ReposRoute, R.string.common_repository, Icons.Default.Storage),
    BottomTab(StarsRoute, R.string.nav_tab_stars, Icons.Default.Star),
    BottomTab(GistRoute, R.string.nav_tab_gist, Icons.Default.Code),
    BottomTab(SettingsRoute, R.string.nav_tab_settings, Icons.Default.Settings),
)

/**
 * 判断当前显示的路由是否是顶层 Tab（不是 push 路由）。
 * 用于控制是否显示底部 NavigationBar：Tab 根页显示，push 页隐藏。
 */
private fun GitMobNavState.isShowingTabRoot(): Boolean {
    val currentStack = backStacks[topLevelRoute] ?: return false
    return when (currentStack.lastOrNull()) {
        HomeRoute, ReposRoute, StarsRoute, GistRoute, SettingsRoute -> true
        else -> false
    }
}

/**
 * 顶层入口：登录态二选一切换，登录后进入多栈导航。
 *
 * 外层始终 Box：
 *   - 空 Scaffold 默认会加一层无用 padding，Box 避免顶部白边
 *   - ErrorBannerHost 作为全局唯一的顶部错误提示浮层，通过 align 叠在内容之上
 */
@Composable
fun GitMobNavGraph(
    startLoggedIn: Boolean,
    errorEventBus: ErrorEventBus,
    enablePredictiveBack: Boolean,
    deepLinkDestination: DeepLinkDestination? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    var isLoggedIn by remember { mutableStateOf(startLoggedIn) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoggedIn) {
            LoggedInApp(
                onLogout = { isLoggedIn = false },
                enablePredictiveBack = enablePredictiveBack,
                deepLinkDestination = deepLinkDestination,
                onDeepLinkConsumed = onDeepLinkConsumed,
            )
        } else {
            LoginScreen(onLoginSuccess = { isLoggedIn = true })
        }

        ErrorBannerHost(
            errorEventBus = errorEventBus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
        )
    }
}

/**
 * 登录后主 App 骨架：多栈导航 + 底部 NavigationBar（仅 Tab 根页显示）。
 *
 * 对齐 android-skills multiple-backstacks recipe：
 *   - Scaffold contentWindowInsets = WindowInsets(0)：不让 Scaffold 消费任何系统 inset，
 *     所有 inset 由各 Screen 自己的 Scaffold 处理（通过 safeDrawing.only(Top + Horizontal)）
 *   - 只将 NavigationBar 的底部高度作为 NavDisplay 的 bottom padding 传入，
 *     Tab Screen 内部感知不到这个 padding、不需要再挂 Spacer 防遮挡
 *   - Push 路由（二级及以下页）隐藏 NavigationBar，NavDisplay 不再加 bottom padding，
 *     由各 Screen 自己的 Scaffold + WindowInsets.navigationBars 处理底部
 *
 * @param onLogout 设置页登出回调：切回登录页（不进 backStack，直接替换最外层内容）
 */
@Composable
private fun LoggedInApp(
    onLogout: () -> Unit,
    enablePredictiveBack: Boolean,
    deepLinkDestination: DeepLinkDestination?,
    onDeepLinkConsumed: () -> Unit,
) {
    val navState = rememberGitMobNavState(startRoute = HomeRoute)
    val navigator = remember(navState) { GitMobNavigator(navState) }
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    val openExternalUrl: (String) -> Unit = remember(uriHandler) {
        { rawUrl ->
            val target = if (rawUrl.startsWith("http://", ignoreCase = true) ||
                rawUrl.startsWith("https://", ignoreCase = true)
            ) {
                rawUrl
            } else {
                "https://$rawUrl"
            }
            runCatching { uriHandler.openUri(target) }
        }
    }
    val showingTabRoot = navState.isShowingTabRoot()

    LaunchedEffect(deepLinkDestination) {
        val destination = deepLinkDestination ?: return@LaunchedEffect
        when (destination) {
            is DeepLinkDestination.Profile -> navigator.navigate(ProfileRoute(destination.login))
            is DeepLinkDestination.RepoOverview -> navigator.navigate(RepoDetailRoute(destination.owner, destination.repo))
            is DeepLinkDestination.IssueList -> navigator.navigate(RepoIssuesRoute(destination.owner, destination.repo))
            is DeepLinkDestination.IssueDetail -> navigator.navigate(RepoIssueDetailRoute(destination.owner, destination.repo, destination.number))
            is DeepLinkDestination.FileView -> navigator.navigate(RepoCodeRoute(destination.owner, destination.repo, destination.ref))
            is DeepLinkDestination.DirView -> navigator.navigate(RepoCodeRoute(destination.owner, destination.repo, destination.ref))
            is DeepLinkDestination.PullRequestDetail -> navigator.navigate(RepoPlaceholderRoute("${destination.owner}/${destination.repo} #${destination.number}"))
            is DeepLinkDestination.DiscussionDetail -> navigator.navigate(RepoPlaceholderRoute("${destination.owner}/${destination.repo} #${destination.number}"))
            is DeepLinkDestination.DiscussionList -> navigator.navigate(RepoPlaceholderRoute("${destination.owner}/${destination.repo} discussions"))
            DeepLinkDestination.Unsupported -> Unit
        }
        onDeepLinkConsumed()
    }

    val entryProvider = entryProvider<NavKey> {
        // ──────────────────────────────────────────────────────────────
        // Tab 根路由（5 个）：各自独立 Screen，不再包 MainTabHost / HorizontalPager
        // ──────────────────────────────────────────────────────────────
        entry<HomeRoute> {
            HomeScreen(
                onFollowersClick = { login -> navigator.navigate(FollowersRoute(login)) },
                onFollowingClick = { login -> navigator.navigate(FollowingRoute(login)) },
                onReposClick = { login -> navigator.navigate(UserRepoListRoute(login)) },
                onStarredClick = { login -> navigator.navigate(UserStarredReposRoute(login)) },
                onGistClick = { navigator.navigate(GistListRoute()) },
                onOrgClick = { login -> navigator.navigate(ProfileRoute(login)) },
                onWorkIssuesClick = { navigator.navigate(WorkIssuesRoute) },
                onWorkPullRequestsClick = { navigator.navigate(WorkPullRequestsRoute) },
                onWorkDiscussionsClick = { navigator.navigate(WorkDiscussionsRoute) },
                onInboxClick = { navigator.navigate(InboxRoute) },
                onPinnedRepoClick = { owner, name ->
                    navigator.navigate(RepoDetailRoute(owner, name))
                },
            )
        }
        entry<ReposRoute> {
            ReposScreen(
                onRepoClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = openExternalUrl,
            )
        }
        entry<StarsRoute> {
            StarsScreen(
                onRepoClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = openExternalUrl,
            )
        }
        entry<GistRoute> {
            GistScreen(onGistClick = openExternalUrl)
        }
        entry<SettingsRoute> {
            SettingsScreen(
                onAppearanceClick = { navigator.navigate(AppearanceRoute) },
                onAboutClick = { navigator.navigate(AboutRoute) },
                onLogout = onLogout,
            )
        }

        // ──────────────────────────────────────────────────────────────
        // Push 路由：与 Tab 解耦，push 路由进当前激活 Tab 的独立栈
        // 返回键统一走 navigator.goBack()，在 Tab 根上会切回 Home，二级页正常出栈
        // ──────────────────────────────────────────────────────────────
        entry<InboxRoute> {
            InboxScreen(
                onBack = { navigator.goBack() },
                onNotificationClick = { notification ->
                    navigator.navigate(RepoPlaceholderRoute("${notification.subjectType}：${notification.title}"))
                },
            )
        }
        entry<WorkIssuesRoute> {
            WorkIssueListScreen(
                onBack = { navigator.goBack() },
                onItemClick = { owner, name, number ->
                    navigator.navigate(RepoIssueDetailRoute(owner, name, number))
                },
            )
        }
        entry<WorkPullRequestsRoute> {
            WorkPullRequestListScreen(
                onBack = { navigator.goBack() },
                onItemClick = { owner, name, number ->
                    navigator.navigate(RepoPlaceholderRoute("$owner/$name #$number"))
                },
            )
        }
        entry<WorkDiscussionsRoute> {
            WorkDiscussionListScreen(
                onBack = { navigator.goBack() },
                onItemClick = { owner, name, number ->
                    navigator.navigate(RepoPlaceholderRoute("$owner/$name #$number"))
                },
            )
        }
        entry<AboutRoute> {
            AboutScreen(onBack = { navigator.goBack() })
        }
        entry<AppearanceRoute> {
            AppearanceScreen(onBack = { navigator.goBack() })
        }
        entry<FollowersRoute> { route ->
            UserListScreen(
                login = route.login,
                mode = UserListMode.FOLLOWERS,
                onBack = { navigator.goBack() },
                onUserClick = { login -> navigator.navigate(ProfileRoute(login)) },
            )
        }
        entry<FollowingRoute> { route ->
            UserListScreen(
                login = route.login,
                mode = UserListMode.FOLLOWING,
                onBack = { navigator.goBack() },
                onUserClick = { login -> navigator.navigate(ProfileRoute(login)) },
            )
        }
        entry<ProfileRoute> { route ->
            ProfileScreen(
                login = route.login,
                onBack = { navigator.goBack() },
                onFollowersClick = { login -> navigator.navigate(FollowersRoute(login)) },
                onFollowingClick = { login -> navigator.navigate(FollowingRoute(login)) },
                onReposClick = { login -> navigator.navigate(UserRepoListRoute(login)) },
                onStarredClick = { login -> navigator.navigate(UserStarredReposRoute(login)) },
                onGistClick = { login -> navigator.navigate(GistListRoute(login)) },
                onMembersClick = { login -> navigator.navigate(OrgMembersRoute(login)) },
                onOrgClick = { login -> navigator.navigate(ProfileRoute(login)) },
                onPinnedRepoClick = { owner, name ->
                    navigator.navigate(RepoDetailRoute(owner, name))
                },
            )
        }
        entry<RepoDetailRoute> { route ->
            RepoDetailScreen(
                owner = route.owner,
                name = route.name,
                onBack = { navigator.goBack() },
                onOwnerClick = { login -> navigator.navigate(ProfileRoute(login)) },
                onForkSourceClick = { forkOwner, forkName ->
                    navigator.navigate(RepoDetailRoute(forkOwner, forkName))
                },
                onNavigateBranches = { currentRef, canManageBranchProtection ->
                    navigator.navigate(
                        RepoBranchesRoute(route.owner, route.name, currentRef, canManageBranchProtection)
                    )
                },
                onNavigateCode = { ref -> navigator.navigate(RepoCodeRoute(route.owner, route.name, ref)) },
                onNavigateCommits = { ref -> navigator.navigate(RepoCommitsRoute(route.owner, route.name, ref)) },
                onNavigateWatchers = { navigator.navigate(RepoWatchersRoute(route.owner, route.name)) },
                onNavigateIssues = { permission, viewerCanCreateIssues ->
                    navigator.navigate(RepoIssuesRoute(route.owner, route.name, permission, viewerCanCreateIssues))
                },
                onNavigatePlaceholder = { label -> navigator.navigate(RepoPlaceholderRoute(label)) },
            )
        }
        entry<RepoIssuesRoute> { route ->
            RepoIssueListScreen(
                owner = route.owner,
                name = route.name,
                permission = route.permission,
                viewerCanCreateIssues = route.viewerCanCreateIssues,
                onBack = { navigator.goBack() },
                onIssueClick = { number -> navigator.navigate(RepoIssueDetailRoute(route.owner, route.name, number, route.permission)) },
            )
        }
        entry<RepoIssueDetailRoute> { route ->
            RepoIssueDetailScreen(
                owner = route.owner,
                name = route.name,
                number = route.number,
                permission = route.permission,
                onBack = { navigator.goBack() },
            )
        }
        entry<RepoBranchesRoute> { route ->
            BranchesScreen(
                owner = route.owner,
                name = route.name,
                currentRef = route.currentRef,
                canManageBranchProtection = route.canManageBranchProtection,
                onBack = { navigator.goBack() },
            )
        }
        entry<RepoWatchersRoute> { route ->
            RepoWatchersScreen(
                owner = route.owner,
                name = route.name,
                onBack = { navigator.goBack() },
                onUserClick = { login -> navigator.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoCodeRoute> { route -> PlaceholderScreen(stringResource(R.string.nav_code_browser, route.ref)) }
        entry<RepoCommitsRoute> { route -> PlaceholderScreen(stringResource(R.string.nav_commit_history, route.ref)) }

        // ========== 复用参数化 Screen 的 push 路由 ==========

        entry<UserRepoListRoute> { route ->
            ReposScreen(
                login = route.login,
                onRepoClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = openExternalUrl,
            )
        }

        entry<UserStarredReposRoute> { route ->
            UserStarredReposScreen(
                login = route.login,
                onBack = { navigator.goBack() },
                onRepoClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> navigator.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = openExternalUrl,
            )
        }

        entry<GistListRoute> { route ->
            GistScreen(
                login = route.login,
                onBack = { navigator.goBack() },
                onGistClick = openExternalUrl,
            )
        }

        entry<OrgMembersRoute> { route ->
            OrgMembersScreen(
                orgLogin = route.login,
                onBack = { navigator.goBack() },
                onUserClick = { login -> navigator.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoPlaceholderRoute> { route -> PlaceholderScreen(route.label) }
    }

    Scaffold(
        bottomBar = {
            if (showingTabRoot) {
                NavigationBar {
                    val selectedRoute = navState.topLevelRoute
                    bottomTabs.forEach { tab ->
                        val isSelected = tab.route == selectedRoute
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    navigator.onReselect(tab.route)
                                } else {
                                    navigator.navigate(tab.route)
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
        // ★ contentWindowInsets = 0：不让 Scaffold 默认消费 statusBars/navigationBars，
        // 所有 inset 由各 Screen 自己的 Scaffold 通过 safeDrawing.only(Top + Horizontal) 处理，
        // 底部 NavigationBar 高度只在 Tab 根页显示时通过 innerPadding.bottom 传给 NavDisplay。
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        val navBarBottom = innerPadding.calculateBottomPadding()

        NavDisplay(
            entries = navState.toDecoratedEntries(entryProvider),
            onBack = {
                val handled = navigator.goBack()
                // navigator 返回 false = 已在 Home Tab 根页且栈空 → 交给系统 finish Activity
                if (!handled) activity?.finish()
            },
            transitionSpec = { crossFadeTransition() },
            popTransitionSpec = { crossFadeTransition() },
            predictivePopTransitionSpec = {
                if (enablePredictiveBack) {
                    // 预测性返回开启：保留跟手的横滑预览（平台标准观感）
                    slideInHorizontally(initialOffsetX = { -it / 4 }) togetherWith
                        slideOutHorizontally(targetOffsetX = { it / 4 })
                } else {
                    // 开关语义 = 关闭"跟手预览"，不是关闭动画：
                    // 回退成与 popTransitionSpec 相同的淡入淡出，提交时播完整动画。
                    // 之前这里是 None togetherWith None，全面屏手势下所有返回都走本
                    // spec，等于把一切返回动画关掉了。
                    crossFadeTransition()
                }
            },
            // 仅在显示 Tab 根页时加 bottom padding（等于 NavigationBar 高度），
            // 避免 push 路由页与它们内部的 WindowInsets.navigationBars Spacer 重复叠加。
            modifier = Modifier.fillMaxSize().then(
                if (showingTabRoot) Modifier.padding(bottom = navBarBottom)
                else Modifier
            ),
        )
    }
}
