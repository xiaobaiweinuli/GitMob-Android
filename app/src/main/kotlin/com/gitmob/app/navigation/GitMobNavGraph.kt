package com.gitmob.app.navigation

import com.gitmob.app.ui.translation.TranslationHost
import com.gitmob.app.ui.translation.TranslationSettingsScreen

import android.net.Uri
import com.gitmob.app.data.model.RepoDocumentContext
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.gitmob.app.ui.search.RepoCodeSearchScreen
import com.gitmob.app.ui.search.SearchScreen
import com.gitmob.app.ui.search.SearchCategoryScreen
import com.gitmob.app.ui.search.SearchResultActions
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ErrorBannerHost
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.AccountState
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.ui.branches.BranchesScreen
import com.gitmob.app.ui.common.ConversationComposerScreen
import com.gitmob.app.ui.repoactions.RepoActionStepLogScreen
import com.gitmob.app.data.model.RepoActionStepLogKey
import com.gitmob.app.ui.repoactions.RepoActionsScreen
import com.gitmob.app.ui.repoactions.RepoWorkflowRunScreen
import com.gitmob.app.ui.repocommunity.RepoContributorsScreen
import com.gitmob.app.ui.repocommunity.RepoLicenseScreen
import com.gitmob.app.ui.repoforks.RepoForksScreen
import com.gitmob.app.ui.repocommits.RepoCommitDetailScreen
import com.gitmob.app.ui.repocommits.RepoCommitsScreen
import com.gitmob.app.ui.repocode.RepoCodeScreen
import com.gitmob.app.ui.repocode.RepoFileDetailScreen
import com.gitmob.app.ui.repocode.RepoFileEditorScreen
import com.gitmob.app.ui.reporeleases.RepoReleaseDetailScreen
import com.gitmob.app.ui.reporeleases.RepoReleaseEditorScreen
import com.gitmob.app.ui.reporeleases.RepoReleaseListScreen
import com.gitmob.app.ui.common.PlaceholderScreen
import com.gitmob.app.ui.gist.GistDetailScreen
import com.gitmob.app.ui.gist.GistCreateScreen
import com.gitmob.app.ui.gist.GistFileEditorScreen
import com.gitmob.app.ui.gist.GistForksScreen
import com.gitmob.app.ui.gist.GistRevisionDetailScreen
import com.gitmob.app.ui.gist.GistRevisionsScreen
import com.gitmob.app.ui.gist.GistScreen
import com.gitmob.app.ui.library.FavoritesScreen
import com.gitmob.app.ui.library.FavoriteGroupScreen
import com.gitmob.app.ui.library.HistoryScreen
import com.gitmob.app.ui.settings.AdvancedSettingsScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.gitmob.app.ui.library.AccountLibraryViewModel
import com.gitmob.app.ui.home.HomeScreen
import com.gitmob.app.ui.inbox.InboxScreen
import com.gitmob.app.ui.login.LoginScreen
import com.gitmob.app.ui.profile.ProfileScreen
import com.gitmob.app.ui.profile.ProfileEditorScreen
import com.gitmob.app.ui.repodetail.RepoDetailScreen
import com.gitmob.app.ui.repodiscussions.RepoDiscussionDetailScreen
import com.gitmob.app.ui.repodiscussions.RepoDiscussionEditorScreen
import com.gitmob.app.ui.repodiscussions.RepoDiscussionListScreen
import com.gitmob.app.ui.repoissues.RepoIssueDetailScreen
import com.gitmob.app.ui.repoissues.RepoIssueEditorScreen
import com.gitmob.app.ui.repoissues.RepoIssueListScreen
import com.gitmob.app.ui.repopullrequests.RepoPullRequestDetailScreen
import com.gitmob.app.ui.repopullrequests.RepoPullRequestEditorScreen
import com.gitmob.app.ui.repopullrequests.RepoPullRequestListScreen
import com.gitmob.app.ui.repos.RepoCreateScreen
import com.gitmob.app.ui.repos.ReposScreen
import com.gitmob.app.ui.settings.AboutScreen
import com.gitmob.app.ui.settings.AppearanceScreen
import com.gitmob.app.ui.settings.SettingsScreen
import com.gitmob.app.ui.stars.StarsScreen
import com.gitmob.app.ui.theme.ThemePreference
import com.gitmob.app.ui.userlist.OrgMembersScreen
import com.gitmob.app.ui.userlist.RepoWatchersScreen
import com.gitmob.app.ui.userlist.RepoStargazersScreen
import com.gitmob.app.ui.userlist.GistStargazersScreen
import com.gitmob.app.ui.userlist.UserListMode
import com.gitmob.app.ui.userlist.UserListScreen
import com.gitmob.app.ui.userstars.UserStarredReposScreen
import com.gitmob.app.ui.work.WorkDiscussionListScreen
import com.gitmob.app.ui.work.WorkIssueListScreen
import com.gitmob.app.ui.work.WorkPullRequestListScreen

private fun RepositoryCreateOwner.toRepoCreateRoute() = RepoCreateRoute(
    ownerId = id,
    ownerLogin = login,
    ownerName = name,
    ownerAvatarUrl = avatarUrl,
    ownerIsOrganization = type == RepositoryCreateOwnerType.ORGANIZATION,
    ownerCanCreateRepository = canCreateRepository,
)

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

/**
 * 顶层入口：登录态二选一切换，登录后进入多栈导航。
 *
 * 外层始终 Box：
 *   - 空 Scaffold 默认会加一层无用 padding，Box 避免顶部白边
 *   - ErrorBannerHost 作为全局唯一的顶部错误提示浮层，通过 align 叠在内容之上
 */
@Composable
fun GitMobNavGraph(
    accountState: AccountState,
    accountSession: AccountSession,
    errorEventBus: ErrorEventBus,
    repoUpdateEventBus: RepoUpdateEventBus,
    themePreference: ThemePreference,
    enablePredictiveBack: Boolean,
    deepLinkDestination: DeepLinkDestination? = null,
    onDeepLinkConsumed: () -> Unit = {},
    updateSettings: (@Composable (AccountEpoch) -> Unit)? = null,
) {
    val epoch = accountSession.epoch
    if (epoch.id != accountState.sessionId) return
    val savedStates = rememberSaveableStateHolder()
    // 同账号配置变更保留状态；换会话销毁父 Store 及五栈 entry，不靠根 key 猜测生命周期。
    key(accountState.sessionId) {
    val provider = rememberViewModelStoreProvider(key = epoch.id)
    val owner = rememberViewModelStoreOwner(key = epoch.id, provider = provider)
    DisposableEffect(epoch) {
        onDispose { if (!epoch.isCurrent) savedStates.removeState(epoch.id) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
    savedStates.SaveableStateProvider(epoch.id) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (accountState.activeAccount != null) {
            LoggedInApp(
                onLogout = {},
                themePreference = themePreference,
                enablePredictiveBack = enablePredictiveBack,
                deepLinkDestination = deepLinkDestination.takeUnless { it == DeepLinkDestination.BrowserUnavailable },
                onDeepLinkConsumed = onDeepLinkConsumed,
                repoUpdateEventBus = repoUpdateEventBus,
                updateSettings = updateSettings,
                epoch = epoch,
            )
        } else {
            LoginScreen(sessionId = accountState.sessionId)
        }

        ErrorBannerHost(
            errorEventBus = errorEventBus,
            epoch = epoch,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
        )
        LaunchedEffect(deepLinkDestination, epoch) {
            if (deepLinkDestination == DeepLinkDestination.BrowserUnavailable && epoch.isCurrent) {
                // 等待提示宿主完成首帧订阅，避免无重放的事件在冷启动时丢失。
                withFrameNanos { }
                errorEventBus.emit(ApiError.UserVisible(R.string.deep_link_browser_unavailable), epoch)
                onDeepLinkConsumed()
            }
        }
    }
    }
    }
    }
}

/**
 * Five independent tab stacks share a full-screen navigation container.
 * Each root entry owns its bottom bar and occupied space, so navigating to a child does not
 * change the outgoing page's constraints. Screens continue to own their top/side system insets.
 */
@Composable
private fun LoggedInApp(
    onLogout: () -> Unit,
    themePreference: ThemePreference,
    enablePredictiveBack: Boolean,
    deepLinkDestination: DeepLinkDestination?,
    onDeepLinkConsumed: () -> Unit,
    repoUpdateEventBus: RepoUpdateEventBus,
    epoch: AccountEpoch,
    updateSettings: (@Composable (AccountEpoch) -> Unit)?,
) {
    // Resolve before entryProvider installs each navigation entry's own store.
    val accountLibrary: AccountLibraryViewModel = hiltViewModel()
    val navState = rememberGitMobNavState(startRoute = HomeRoute)
    val navigator = remember(navState, epoch) { GitMobNavigator(navState, epoch) }
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    val openExternalUrl: (String) -> Unit = remember(uriHandler, epoch) {
        { rawUrl ->
            if (epoch.isCurrent) {
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
    }

    LaunchedEffect(repoUpdateEventBus, navigator) {
        repoUpdateEventBus.eventsFor(epoch).collect(navigator::onRepositoryUpdate)
    }

    fun navigateDeepLink(destination: DeepLinkDestination) {
        if (!epoch.isCurrent) return
        when (destination) {
            is DeepLinkDestination.GistDetail -> navigator.navigate(GistDetailRoute(destination.id))
            is DeepLinkDestination.Profile -> navigator.navigate(ProfileRoute(destination.login))
            is DeepLinkDestination.RepoOverview -> navigator.navigate(RepoDetailRoute(destination.owner, destination.repo))
            is DeepLinkDestination.IssueList -> navigator.navigate(RepoIssuesRoute(destination.owner, destination.repo))
            is DeepLinkDestination.IssueDetail -> navigator.navigate(RepoIssueDetailRoute(destination.owner, destination.repo, destination.number))
            is DeepLinkDestination.FileView -> navigator.navigate(RepoFileDetailRoute(destination.owner, destination.repo, destination.ref, destination.path))
            is DeepLinkDestination.DirView -> navigator.navigate(RepoCodeRoute(destination.owner, destination.repo, destination.ref, destination.path))
            is DeepLinkDestination.CommitDetail -> navigator.navigate(RepoCommitDetailRoute(destination.owner, destination.repo, destination.sha))
            is DeepLinkDestination.CommitList -> navigator.navigate(RepoCommitsRoute(destination.owner, destination.repo, destination.ref, destination.path))
            is DeepLinkDestination.PullRequestDetail -> navigator.navigate(RepoPullRequestDetailRoute(destination.owner, destination.repo, destination.number))
            is DeepLinkDestination.PullRequestList -> navigator.navigate(RepoPullRequestsRoute(destination.owner, destination.repo))
            is DeepLinkDestination.DiscussionDetail -> navigator.navigate(RepoDiscussionDetailRoute(destination.owner, destination.repo, destination.number))
            is DeepLinkDestination.DiscussionList -> navigator.navigate(RepoDiscussionsRoute(destination.owner, destination.repo))
            is DeepLinkDestination.Actions -> navigator.navigate(RepoActionsRoute(destination.owner, destination.repo))
            is DeepLinkDestination.WorkflowRun -> navigator.navigate(RepoWorkflowRunRoute(destination.owner, destination.repo, destination.runId))
            is DeepLinkDestination.ReleaseList -> navigator.navigate(RepoReleasesRoute(destination.owner, destination.repo))
            is DeepLinkDestination.ReleaseDetail -> navigator.navigate(RepoReleaseDetailRoute(destination.owner, destination.repo, destination.tag))
            is DeepLinkDestination.Contributors -> navigator.navigate(RepoContributorsRoute(destination.owner, destination.repo))
            DeepLinkDestination.BrowserUnavailable, DeepLinkDestination.Unsupported -> Unit
        }
    }
    val openDocument: (RepoDocumentContext) -> Unit = { document ->
        if (epoch.isCurrent) {
            if (document.filePath.isEmpty() || document.filePath.endsWith('/')) {
                navigator.navigate(RepoCodeRoute(document.owner, document.name, document.ref, document.filePath.trimEnd('/')))
            } else {
                navigator.navigate(RepoFileDetailRoute(document.owner, document.name, document.ref, document.filePath))
            }
        }
    }
    val openDocumentExternal: (String) -> Unit = { url ->
        val destination = DeepLinkRouter.parse(Uri.parse(url))
        if (destination == DeepLinkDestination.Unsupported) openExternalUrl(url)
        else navigateDeepLink(destination)
    }
    LaunchedEffect(deepLinkDestination) {
        if (!epoch.isCurrent) return@LaunchedEffect
        val destination = deepLinkDestination ?: return@LaunchedEffect
        navigateDeepLink(destination)
        onDeepLinkConsumed()
    }

    fun searchActions(clicks: GitMobEntryNavigation) = SearchResultActions(
        repository = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
        work = { item ->
            clicks.navigate(if (item.isPullRequest) RepoPullRequestDetailRoute(item.repoOwner, item.repoName, item.number)
                else RepoIssueDetailRoute(item.repoOwner, item.repoName, item.number))
        },
        account = { clicks.navigate(ProfileRoute(it)) },
        file = { item ->
            val target = item.target
            if (target == null) clicks.run { openExternalUrl(item.htmlUrl) }
            else clicks.navigate(RepoFileDetailRoute(item.owner, item.repository, target.ref, item.path))
        },
        homepage = { url -> clicks.run { openExternalUrl(url) } },
    )
    val entryProvider = entryProvider<NavKey> {
        entry<SearchRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            SearchScreen(
                onBack = { clicks.goBack() },
                onCategory = { query, category -> clicks.navigate(SearchCategoryRoute(query, category)) },
                actions = searchActions(clicks),
            )
        }
        entry<RepoCodeSearchRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoCodeSearchScreen(route.owner, route.name, route.path, route.isFork,
                onBack = { clicks.goBack() }, onFile = searchActions(clicks).file)
        }
        entry<SearchCategoryRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            SearchCategoryScreen(route.query, route.category, onBack = { clicks.goBack() }, actions = searchActions(clicks))
        }
        // ──────────────────────────────────────────────────────────────
        // Tab 根路由（5 个）：各自独立 Screen，不再包 MainTabHost / HorizontalPager
        // ──────────────────────────────────────────────────────────────
        entry<HomeRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GitMobTabRoot(route, onTabClick = clicks::selectTab) {
                HomeScreen(
                    accountLibrary = accountLibrary,
                    onFollowersClick = { login -> clicks.navigate(FollowersRoute(login)) },
                    onFollowingClick = { login -> clicks.navigate(FollowingRoute(login)) },
                    onReposClick = { login -> clicks.navigate(UserRepoListRoute(login)) },
                    onStarredClick = { login -> clicks.navigate(UserStarredReposRoute(login)) },
                    onGistClick = { clicks.navigate(GistListRoute()) },
                    onOrgClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onWorkIssuesClick = { clicks.navigate(WorkIssuesRoute) },
                    onWorkPullRequestsClick = { clicks.navigate(WorkPullRequestsRoute) },
                    onWorkDiscussionsClick = { clicks.navigate(WorkDiscussionsRoute) },
                    onInboxClick = { clicks.navigate(InboxRoute) },
                    onFavoritesClick = { clicks.navigate(FavoritesRoute()) },
                    onHistoryClick = { clicks.navigate(HistoryRoute()) },
                    onSearchClick = { clicks.navigate(SearchRoute()) },
                    onPinnedRepoClick = { owner, name ->
                        clicks.navigate(RepoDetailRoute(owner, name))
                    },
                    onCreateRepository = { owner -> clicks.navigate(owner.toRepoCreateRoute()) },
                )
            }
        }
        entry<ReposRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GitMobTabRoot(route, onTabClick = clicks::selectTab) {
                ReposScreen(
                    onRepoClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onForkSourceClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onHomepageClick = { url -> clicks.run { openExternalUrl(url) } },
                    onCreateRepository = { owner -> clicks.navigate(owner.toRepoCreateRoute()) },
                )
            }
        }
        entry<StarsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GitMobTabRoot(route, onTabClick = clicks::selectTab) {
                StarsScreen(
                    onRepoClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onForkSourceClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onHomepageClick = { url -> clicks.run { openExternalUrl(url) } },
                )
            }
        }
        entry<GistRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GitMobTabRoot(route, onTabClick = clicks::selectTab) {
                GistScreen(
                    onCreateGist = { clicks.navigate(GistCreateRoute) },
                    onGistClick = { gist ->
                        clicks.navigate(GistDetailRoute(gist.apiName))
                    },
                    onForkSourceClick = { id -> clicks.navigate(GistDetailRoute(id)) },
                )
            }
        }
        entry<SettingsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GitMobTabRoot(route, onTabClick = clicks::selectTab) {
                SettingsScreen(
                    preference = themePreference,
                    onAppearanceClick = { clicks.navigate(AppearanceRoute) },
                    onAdvancedClick = { clicks.navigate(AdvancedSettingsRoute()) },
                    onAboutClick = { clicks.navigate(AboutRoute) },
                    onEditProfileClick = { clicks.navigate(ProfileEditorRoute) },
                    onLogout = onLogout,
                )
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Push 路由：与 Tab 解耦，push 路由进当前激活 Tab 的独立栈
        // 返回键统一走 navigator.goBack()，在 Tab 根上会切回 Home，二级页正常出栈
        // ──────────────────────────────────────────────────────────────
        entry<AdvancedSettingsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            AdvancedSettingsScreen(
                onBack = { clicks.goBack() }, viewModel = accountLibrary,
                onTranslation = { clicks.navigate(TranslationSettingsRoute) },
                updateContent = updateSettings?.let { content -> { content(epoch) } },
            )
        }
        entry<TranslationSettingsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationSettingsScreen(onBack = { clicks.goBack() })
        }
        entry<FavoritesRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            FavoritesScreen(accountLibrary = accountLibrary, onBack = { clicks.goBack() }, onGroup = { clicks.navigate(FavoriteGroupRoute(it.id, it.name)) })
        }
        entry<FavoriteGroupRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            FavoriteGroupScreen(accountLibrary, route.groupId, initialName = route.name, onGist = { clicks.navigate(GistDetailRoute(it)) }, onBack = { clicks.goBack() }, onRepo = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) }, onHomepage = { url -> clicks.run { openExternalUrl(url) } })
        }
        entry<HistoryRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            HistoryScreen(accountLibrary = accountLibrary, onGist = { clicks.navigate(GistDetailRoute(it)) }, onBack = { clicks.goBack() }, onRepo = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) }, onHomepage = { url -> clicks.run { openExternalUrl(url) } })
        }
        entry<InboxRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            InboxScreen(
                onBack = { clicks.goBack() },
                onNotificationClick = { notification ->
                    clicks.navigate(RepoPlaceholderRoute("${notification.subjectType}：${notification.title}"))
                },
            )
        }
        entry<WorkIssuesRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            WorkIssueListScreen(
                onBack = { clicks.goBack() },
                onItemClick = { owner, name, number ->
                    clicks.navigate(RepoIssueDetailRoute(owner, name, number))
                },
            )
        }
        entry<WorkPullRequestsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            WorkPullRequestListScreen(
                onBack = { clicks.goBack() },
                onItemClick = { owner, name, number ->
                    clicks.navigate(RepoPullRequestDetailRoute(owner, name, number))
                },
            )
        }
        entry<WorkDiscussionsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            WorkDiscussionListScreen(
                onBack = { clicks.goBack() },
                onItemClick = { owner, name, number ->
                    clicks.navigate(RepoDiscussionDetailRoute(owner, name, number))
                },
            )
        }
        entry<AboutRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            AboutScreen(onBack = { clicks.goBack() })
        }
        entry<AppearanceRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            AppearanceScreen(
                preference = themePreference,
                onBack = { clicks.goBack() },
            )
        }
        entry<ProfileEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            ProfileEditorScreen(onBack = { clicks.goBack() })
        }
        entry<FollowersRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            UserListScreen(
                login = route.login,
                mode = UserListMode.FOLLOWERS,
                onBack = { clicks.goBack() },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<FollowingRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            UserListScreen(
                login = route.login,
                mode = UserListMode.FOLLOWING,
                onBack = { clicks.goBack() },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoCreateRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoCreateScreen(
                defaultOwner = RepositoryCreateOwner(
                    id = route.ownerId,
                    login = route.ownerLogin,
                    name = route.ownerName,
                    avatarUrl = route.ownerAvatarUrl,
                    type = if (route.ownerIsOrganization) {
                        RepositoryCreateOwnerType.ORGANIZATION
                    } else {
                        RepositoryCreateOwnerType.USER
                    },
                    canCreateRepository = route.ownerCanCreateRepository,
                ),
                onBack = { clicks.goBack() },
                onCreated = { owner, name ->
                    navigator.goBack()
                    navigator.navigate(RepoDetailRoute(owner, name))
                },
            )
        }
        entry<ProfileRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                ProfileScreen(
                    onDocumentLink = { document -> clicks.run { openDocument(document) } },
                    onDocumentExternalLink = { url -> clicks.run { openDocumentExternal(url) } },
                    login = route.login,
                    onBack = { clicks.goBack() },
                    onFollowersClick = { login -> clicks.navigate(FollowersRoute(login)) },
                    onFollowingClick = { login -> clicks.navigate(FollowingRoute(login)) },
                    onReposClick = { login -> clicks.navigate(UserRepoListRoute(login)) },
                    onStarredClick = { login -> clicks.navigate(UserStarredReposRoute(login)) },
                    onGistClick = { login -> clicks.navigate(GistListRoute(login)) },
                    onMembersClick = { login -> clicks.navigate(OrgMembersRoute(login)) },
                    onOrgClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onPinnedRepoClick = { owner, name ->
                        clicks.navigate(RepoDetailRoute(owner, name))
                    },
                    onCreateRepository = { owner -> clicks.navigate(owner.toRepoCreateRoute()) },
                )
            }
        }
        entry<RepoDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                RepoDetailScreen(
                    accountLibrary = accountLibrary,
                    onDocumentLink = { document -> clicks.run { openDocument(document) } },
                    onDocumentExternalLink = { url -> clicks.run { openDocumentExternal(url) } },
                    owner = route.owner,
                    name = route.name,
                    onBack = { clicks.goBack() },
                    onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onForkSourceClick = { forkOwner, forkName ->
                        clicks.navigate(RepoDetailRoute(forkOwner, forkName))
                    },
                    onHomepageClick = { url -> clicks.run { openExternalUrl(url) } },
                    onNavigateBranches = { currentRef, canPush, canManageBranchProtection ->
                        clicks.navigate(
                            RepoBranchesRoute(route.owner, route.name, currentRef, canPush, canManageBranchProtection)
                        )
                    },
                    onNavigateCode = { ref -> clicks.navigate(RepoCodeRoute(route.owner, route.name, ref)) },
                    onNavigateCommits = { ref -> clicks.navigate(RepoCommitsRoute(route.owner, route.name, ref)) },
                    onNavigateWatchers = { clicks.navigate(RepoWatchersRoute(route.owner, route.name)) },
                    onNavigateStargazers = { clicks.navigate(RepoStargazersRoute(route.owner, route.name)) },
                    onNavigateForks = { clicks.navigate(RepoForksRoute(route.owner, route.name)) },
                    onNavigateIssues = { permission, viewerCanCreateIssues ->
                        clicks.navigate(RepoIssuesRoute(route.owner, route.name, permission, viewerCanCreateIssues))
                    },
                    onNavigatePullRequests = { permission -> clicks.navigate(RepoPullRequestsRoute(route.owner, route.name, permission)) },
                    onNavigateDiscussions = { permission -> clicks.navigate(RepoDiscussionsRoute(route.owner, route.name, permission)) },
                    onNavigateActions = { permission, defaultRef -> clicks.navigate(RepoActionsRoute(route.owner, route.name, permission, defaultRef)) },
                    onNavigateReleases = { permission -> clicks.navigate(RepoReleasesRoute(route.owner, route.name, permission)) },
                    onNavigateContributors = { clicks.navigate(RepoContributorsRoute(route.owner, route.name)) },
                    onNavigateLicense = { ref -> clicks.navigate(RepoLicenseRoute(route.owner, route.name, ref)) },
                    onNavigatePlaceholder = { label -> clicks.navigate(RepoPlaceholderRoute(label)) },
                    onRepositoryAddressChanged = { owner, name ->
                        navigator.onRepositoryUpdate(RepoUpdateEvent.RepositoryAddressChanged(route.owner, route.name, owner, name))
                    },
                    onRepositoryDeleted = {
                        navigator.onRepositoryUpdate(RepoUpdateEvent.RepositoryDeleted(route.owner, route.name))
                    },
                )
            }
        }
        entry<RepoIssuesRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoIssueListScreen(
                owner = route.owner,
                name = route.name,
                permission = route.permission,
                viewerCanCreateIssues = route.viewerCanCreateIssues,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onIssueClick = { number -> clicks.navigate(RepoIssueDetailRoute(route.owner, route.name, number, route.permission)) },
                onCreate = { templateFilename -> clicks.navigate(RepoIssueEditorRoute(route.owner, route.name, permission = route.permission, templateFilename = templateFilename)) },
            )
        }
        entry<RepoIssueDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                RepoIssueDetailScreen(
                    owner = route.owner,
                    name = route.name,
                    number = route.number,
                    permission = route.permission,
                    onBack = { clicks.goBack() },
                    onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onEdit = { clicks.navigate(RepoIssueEditorRoute(route.owner, route.name, route.number, route.permission)) },
                    onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onCompose = { request ->
                        clicks.navigate(
                            ConversationComposerRoute(
                                owner = route.owner,
                                name = route.name,
                                number = route.number,
                                target = request.target,
                                subjectId = request.subjectId,
                                initialText = request.initialText,
                                commentId = request.commentId,
                                replyToId = request.replyToId,
                                reviewEvent = request.reviewEvent,
                            ),
                        )
                    },
                )
            }
        }
        entry<RepoIssueEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoIssueEditorScreen(
                owner = route.owner,
                name = route.name,
                number = route.number,
                templateFilename = route.templateFilename,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onSaved = { number -> navigator.goBack(); navigator.navigate(RepoIssueDetailRoute(route.owner, route.name, number, route.permission)) },
            )
        }
        entry<RepoPullRequestsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoPullRequestListScreen(
                owner = route.owner,
                name = route.name,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onPullRequestClick = { number -> clicks.navigate(RepoPullRequestDetailRoute(route.owner, route.name, number, route.permission)) },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onCreate = { selection ->
                    clicks.navigate(
                        RepoPullRequestEditorRoute(
                            owner = selection.baseOwner,
                            name = selection.baseRepository,
                            permission = route.permission,
                            baseOwner = selection.baseOwner,
                            baseName = selection.baseRepository,
                            baseRef = selection.baseRef,
                            headOwner = selection.headOwner,
                            headName = selection.headRepository,
                            headRef = selection.headRef,
                            headRepositoryId = selection.headRepositoryId,
                        ),
                    )
                },
                onCreateCommitClick = { commitOwner, commitName, ref, sha -> clicks.navigate(RepoCommitDetailRoute(commitOwner, commitName, sha, ref, route.permission)) },
                onExistingPullRequestClick = { existingOwner, existingName, number ->
                    clicks.navigate(RepoPullRequestDetailRoute(existingOwner, existingName, number, route.permission))
                },
            )
        }
        entry<RepoPullRequestDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                RepoPullRequestDetailScreen(
                    owner = route.owner,
                    name = route.name,
                    number = route.number,
                    permission = route.permission,
                    onBack = { clicks.goBack() },
                    onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onEdit = { clicks.navigate(RepoPullRequestEditorRoute(route.owner, route.name, route.number, route.permission)) },
                    onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onCommitClick = { sha, ref -> clicks.navigate(RepoCommitDetailRoute(route.owner, route.name, sha, ref, route.permission)) },
                    onCompose = { request ->
                        clicks.navigate(
                            ConversationComposerRoute(
                                owner = route.owner,
                                name = route.name,
                                number = route.number,
                                target = request.target,
                                subjectId = request.subjectId,
                                initialText = request.initialText,
                                commentId = request.commentId,
                                replyToId = request.replyToId,
                                reviewEvent = request.reviewEvent,
                            ),
                        )
                    },
                )
            }
        }
        entry<RepoPullRequestEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoPullRequestEditorScreen(
                owner = route.owner,
                name = route.name,
                number = route.number,
                baseOwner = route.baseOwner,
                baseName = route.baseName,
                baseRef = route.baseRef,
                headOwner = route.headOwner,
                headName = route.headName,
                headRef = route.headRef,
                headRepositoryId = route.headRepositoryId,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onSaved = { number -> navigator.goBack(); navigator.navigate(RepoPullRequestDetailRoute(route.owner, route.name, number, route.permission)) },
            )
        }
        entry<RepoDiscussionsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoDiscussionListScreen(
                owner = route.owner,
                name = route.name,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onDiscussionClick = { number -> clicks.navigate(RepoDiscussionDetailRoute(route.owner, route.name, number, route.permission)) },
                onCreate = { clicks.navigate(RepoDiscussionEditorRoute(route.owner, route.name, permission = route.permission)) },
            )
        }
        entry<RepoDiscussionDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                RepoDiscussionDetailScreen(
                    owner = route.owner,
                    name = route.name,
                    number = route.number,
                    permission = route.permission,
                    onBack = { clicks.goBack() },
                    onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onEdit = { clicks.navigate(RepoDiscussionEditorRoute(route.owner, route.name, route.number, route.permission)) },
                    onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onCompose = { request ->
                        clicks.navigate(
                            ConversationComposerRoute(
                                owner = route.owner,
                                name = route.name,
                                number = route.number,
                                target = request.target,
                                subjectId = request.subjectId,
                                initialText = request.initialText,
                                commentId = request.commentId,
                                replyToId = request.replyToId,
                                reviewEvent = request.reviewEvent,
                            ),
                        )
                    },
                )
            }
        }
        entry<RepoDiscussionEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoDiscussionEditorScreen(
                owner = route.owner,
                name = route.name,
                number = route.number,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onSaved = { number -> navigator.goBack(); navigator.navigate(RepoDiscussionDetailRoute(route.owner, route.name, number, route.permission)) },
            )
        }
        entry<ConversationComposerRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            ConversationComposerScreen(
                route = route,
                onBack = { clicks.goBack() },
                onSubmitted = { navigator.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
            )
        }
        entry<RepoActionsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoActionsScreen(
                owner = route.owner,
                name = route.name,
                permission = route.permission,
                defaultRef = route.defaultRef,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onRunClick = { runId -> clicks.navigate(RepoWorkflowRunRoute(route.owner, route.name, runId, route.permission)) },
            )
        }
        entry<RepoWorkflowRunRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoWorkflowRunScreen(
                owner = route.owner,
                name = route.name,
                runId = route.runId,
                permission = route.permission,
                onStepClick = { key -> clicks.navigate(RepoActionStepLogRoute(key.owner, key.name, key.runId, key.attempt, key.jobId, key.stepNumber)) },
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
            )
        }
        entry<RepoActionStepLogRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoActionStepLogScreen(
                logKey = RepoActionStepLogKey(route.owner, route.name, route.runId, route.attempt, route.jobId, route.stepNumber),
                onBack = { clicks.goBack() },
            )
        }
        entry<RepoReleasesRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoReleaseListScreen(
                owner = route.owner,
                name = route.name,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onReleaseClick = { tag -> clicks.navigate(RepoReleaseDetailRoute(route.owner, route.name, tag, route.permission)) },
                onCreate = { clicks.navigate(RepoReleaseEditorRoute(route.owner, route.name, permission = route.permission)) },
            )
        }
        entry<RepoReleaseDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoReleaseDetailScreen(
                owner = route.owner,
                name = route.name,
                tag = route.tag,
                releaseId = null,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onEdit = { releaseId -> clicks.navigate(RepoReleaseEditorRoute(route.owner, route.name, releaseId, route.permission)) },
            )
        }
        entry<RepoReleaseEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoReleaseEditorScreen(
                owner = route.owner,
                name = route.name,
                releaseId = route.releaseId,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onSaved = { tag -> navigator.goBack(); navigator.navigate(RepoReleaseDetailRoute(route.owner, route.name, tag, route.permission)) },
            )
        }
        entry<RepoContributorsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoContributorsScreen(
                owner = route.owner,
                name = route.name,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoLicenseRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoLicenseScreen(
                owner = route.owner,
                name = route.name,
                ref = route.ref,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
            )
        }
        entry<RepoBranchesRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            BranchesScreen(
                owner = route.owner,
                name = route.name,
                currentRef = route.currentRef,
                canPush = route.canPush,
                canManageBranchProtection = route.canManageBranchProtection,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
            )
        }
        entry<RepoWatchersRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoWatchersScreen(
                owner = route.owner,
                name = route.name,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoStargazersRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoStargazersScreen(
                owner = route.owner,
                name = route.name,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoForksRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoForksScreen(
                owner = route.owner,
                name = route.name,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = { url -> clicks.run { openExternalUrl(url) } },
            )
        }
        entry<RepoCodeRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoCodeScreen(
                owner = route.owner,
                name = route.name,
                ref = route.ref,
                path = route.path,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onSearch = { isFork -> clicks.navigate(RepoCodeSearchRoute(route.owner, route.name, route.path, isFork)) },
                onAdd = { clicks.navigate(RepoFileEditorRoute(route.owner, route.name, route.ref, null, route.permission)) },
                onDirectoryClick = { path -> clicks.navigate(RepoCodeRoute(route.owner, route.name, route.ref, path, route.permission)) },
                onFileClick = { path -> clicks.navigate(RepoFileDetailRoute(route.owner, route.name, route.ref, path, route.permission)) },
                onCreateSyncPullRequest = { info ->
                    clicks.navigate(
                        RepoPullRequestEditorRoute(
                            owner = route.owner,
                            name = route.name,
                            permission = route.permission,
                            baseOwner = route.owner,
                            baseName = route.name,
                            baseRef = info.branch,
                            headOwner = info.upstreamOwner,
                            headName = info.upstreamName,
                            headRef = info.branch,
                            headRepositoryId = info.upstreamRepositoryId,
                        ),
                    )
                },
                onExistingSyncPullRequest = { number ->
                    clicks.navigate(RepoPullRequestDetailRoute(route.owner, route.name, number, route.permission))
                },
            )
        }
        entry<RepoCommitsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoCommitsScreen(
                owner = route.owner,
                name = route.name,
                ref = route.ref,
                path = route.path,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onCommitClick = { sha -> clicks.navigate(RepoCommitDetailRoute(route.owner, route.name, sha, route.ref, route.permission)) },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoCommitDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoCommitDetailScreen(
                owner = route.owner,
                name = route.name,
                ref = route.ref,
                sha = route.sha,
                permission = route.permission,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoFileDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                RepoFileDetailScreen(
                    onDirectoryFound = {
                        if (epoch.isCurrent && navState.backStacks[navState.topLevelRoute]?.lastOrNull() == route) {
                            navigator.goBack()
                            navigator.navigate(RepoCodeRoute(route.owner, route.name, route.ref, route.path, route.permission))
                        }
                    },
                    onDocumentLink = { document -> clicks.run { openDocument(document) } },
                    onDocumentExternalLink = { url -> clicks.run { openDocumentExternal(url) } },
                    owner = route.owner,
                    name = route.name,
                    ref = route.ref,
                    path = route.path,
                    onBack = { clicks.goBack() },
                    onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                    onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                    onEdit = { clicks.navigate(RepoFileEditorRoute(route.owner, route.name, route.ref, route.path, route.permission)) },
                    onHistory = { clicks.navigate(RepoCommitsRoute(route.owner, route.name, route.ref, route.path, route.permission)) },
                )
            }
        }
        entry<RepoFileEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            RepoFileEditorScreen(
                owner = route.owner,
                name = route.name,
                ref = route.ref,
                path = route.path,
                onBack = { clicks.goBack() },
                onOwnerClick = { login -> clicks.navigate(ProfileRoute(login)) },
                onRepositoryClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onSaved = { navigator.goBack() },
            )
        }

        // ========== 复用参数化 Screen 的 push 路由 ==========

        entry<UserRepoListRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            ReposScreen(
                login = route.login,
                onBack = { clicks.goBack() },
                onRepoClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = { url -> clicks.run { openExternalUrl(url) } },
                onCreateRepository = { owner -> clicks.navigate(owner.toRepoCreateRoute()) },
            )
        }

        entry<UserStarredReposRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            UserStarredReposScreen(
                login = route.login,
                onBack = { clicks.goBack() },
                onRepoClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onForkSourceClick = { owner, name -> clicks.navigate(RepoDetailRoute(owner, name)) },
                onHomepageClick = { url -> clicks.run { openExternalUrl(url) } },
            )
        }

        entry<GistListRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistScreen(
                login = route.login,
                onBack = { clicks.goBack() },
                onCreateGist = if (route.login == null) {
                    { clicks.navigate(GistCreateRoute) }
                } else {
                    null
                },
                onGistClick = { gist ->
                    clicks.navigate(GistDetailRoute(gist.apiName))
                },
                onForkSourceClick = { id -> clicks.navigate(GistDetailRoute(id)) },
            )
        }

        entry<GistCreateRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistCreateScreen(
                onBack = { clicks.goBack() },
                onCreated = { id ->
                    navigator.goBack()
                    navigator.navigate(GistDetailRoute(id))
                },
            )
        }

        entry<GistDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            TranslationHost {
                GistDetailScreen(
                    accountLibrary = accountLibrary,
                    gistId = route.id,
                    onBack = { clicks.goBack() },
                    onOwnerClick = { clicks.navigate(ProfileRoute(it)) },
                    onForkSourceClick = { clicks.navigate(GistDetailRoute(it)) },
                    onRevisions = { clicks.navigate(GistRevisionsRoute(route.id)) },
                    onForks = { clicks.navigate(GistForksRoute(route.id)) },
                    onStargazers = { nodeId -> clicks.navigate(GistStargazersRoute(nodeId)) },
                    onAddFile = { clicks.navigate(GistFileEditorRoute(route.id)) },
                    onEditFile = { file -> clicks.navigate(GistFileEditorRoute(route.id, file.filename)) },
                    onCompose = { request ->
                        clicks.navigate(
                            ConversationComposerRoute(
                                owner = "",
                                name = "",
                                number = 0,
                                target = request.target,
                                subjectId = request.subjectId,
                                initialText = request.initialText,
                                commentId = request.commentId,
                                replyToId = request.replyToId,
                                reviewEvent = request.reviewEvent,
                            ),
                        )
                    },
                    onForked = { fork -> navigator.navigate(GistDetailRoute(fork.id)) },
                    onDeleted = { navigator.goBack() },
                )
            }
        }

        entry<GistRevisionsRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistRevisionsScreen(
                gistId = route.id,
                onBack = { clicks.goBack() },
                onRevisionClick = { revision, previous ->
                    clicks.navigate(
                        GistRevisionDetailRoute(
                            id = route.id,
                            version = revision.version,
                            previousVersion = previous,
                            committedAt = revision.committedAt,
                            additions = revision.additions,
                            deletions = revision.deletions,
                            totalChanges = revision.totalChanges,
                        ),
                    )
                },
                onUserClick = { clicks.navigate(ProfileRoute(it)) },
            )
        }

        entry<GistRevisionDetailRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistRevisionDetailScreen(
                gistId = route.id,
                version = route.version,
                previousVersion = route.previousVersion,
                committedAt = route.committedAt,
                additions = route.additions,
                deletions = route.deletions,
                totalChanges = route.totalChanges,
                onBack = { clicks.goBack() },
            )
        }

        entry<GistForksRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistForksScreen(
                gistId = route.id,
                onBack = { clicks.goBack() },
                onUserClick = { clicks.navigate(ProfileRoute(it)) },
            )
        }

        entry<GistStargazersRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistStargazersScreen(
                nodeId = route.nodeId,
                onBack = { clicks.goBack() },
                onUserClick = { clicks.navigate(ProfileRoute(it)) },
            )
        }

        entry<GistFileEditorRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            GistFileEditorScreen(
                gistId = route.id,
                filename = route.filename,
                onBack = { clicks.goBack() },
                onSaved = { navigator.goBack() },
            )
        }

        entry<OrgMembersRoute> { route ->
            val clicks = rememberEntryNavigation(navigator, route)
            OrgMembersScreen(
                orgLogin = route.login,
                onBack = { clicks.goBack() },
                onUserClick = { login -> clicks.navigate(ProfileRoute(login)) },
            )
        }
        entry<RepoPlaceholderRoute> { route -> PlaceholderScreen(route.label) }
    }

    GitMobNavDisplay(
        entries = navState.toDecoratedEntries(entryProvider),
        enablePredictiveBack = enablePredictiveBack,
        onBack = {
            val handled = navigator.goBack()
            if (!handled) activity?.finish()
        },
        transitionSpec = { crossFadeTransition() },
        popTransitionSpec = { crossFadeTransition() },
        modifier = Modifier.fillMaxSize(),
    )
}
