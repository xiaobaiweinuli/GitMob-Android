package com.gitmob.android.ui.nav
 
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.*
import androidx.navigation.compose.*
import com.gitmob.android.auth.ThemeMode
import com.gitmob.android.api.ApiClient
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.ui.common.ErrorBox
import com.gitmob.android.ui.common.LoadingBox
import com.gitmob.android.ui.create.CreateRepoScreen
import com.gitmob.android.ui.local.LocalRepoDetailScreen
import com.gitmob.android.ui.local.LocalRepoListScreen
import com.gitmob.android.ui.local.LocalRepoViewModel
import com.gitmob.android.ui.filepicker.FilePickerScreen
import com.gitmob.android.ui.filepicker.PickerMode
import com.gitmob.android.ui.login.LoginScreen
import com.gitmob.android.ui.repo.IssueDetailScreen
import com.gitmob.android.ui.repo.IssueDetailViewModel
import com.gitmob.android.ui.repo.PRDetailScreen
import com.gitmob.android.ui.repo.PRDetailViewModel
import com.gitmob.android.ui.repo.DiscussionDetailScreen
import com.gitmob.android.ui.repo.DiscussionDetailViewModel
import com.gitmob.android.ui.repo.RepoDetailScreen
import com.gitmob.android.ui.repo.RepoDetailViewModel
import com.gitmob.android.ui.home.HomeScreen
import com.gitmob.android.ui.home.HomeViewModel
import com.gitmob.android.ui.repos.RepoListScreen
import com.gitmob.android.ui.search.SearchScreen
import com.gitmob.android.ui.settings.SettingsScreen
import com.gitmob.android.ui.theme.Coral
import com.gitmob.android.ui.theme.CoralDim
import com.gitmob.android.ui.theme.LocalGmColors
import com.gitmob.android.ui.theme.RedColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import com.gitmob.android.ui.repo.EditFileScreen
import com.gitmob.android.ui.repo.EditFileMode
import com.gitmob.android.ui.repo.CommitMessageDialog
import com.gitmob.android.ui.repo.FilePatchInfo
import com.gitmob.android.ui.repo.FileDiffSheet
import android.net.Uri
import com.gitmob.android.util.GitHubDestination
import com.gitmob.android.util.GitHubUrlParser
import com.gitmob.android.ui.common.GmDivider
import androidx.compose.foundation.background
import com.gitmob.android.ui.theme.Green
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.gitmob.android.util.LogManager
 
sealed class Route(val path: String) {
    object Login      : Route("login")
    object Main       : Route("main")
    object CreateRepo : Route("create_repo?org={org}") {
        fun go(org: String? = null) = if (org != null) "create_repo?org=$org" else "create_repo"
    }
    object Settings   : Route("settings")
    object RepoDetail : Route("repo/{owner}/{repo}") {
        fun go(owner: String, repo: String) = "repo/$owner/$repo"
    }
    object LocalRepoDetail : Route("local_repo/{repoId}") {
        fun go(repoId: String) = "local_repo/$repoId"
    }
    object FileViewer : Route("file/{owner}/{repo}/{branch}?path={path}") {
        fun go(owner: String, repo: String, path: String, branch: String) =
            "file/$owner/$repo/$branch?path=${URLEncoder.encode(path, "UTF-8")}"
    }
    object IssueDetail : Route("issue/{owner}/{repo}/{issueNumber}") {
        fun go(owner: String, repo: String, issueNumber: Int) = "issue/$owner/$repo/$issueNumber"
    }
    object PRDetail : Route("pr/{owner}/{repo}/{prNumber}") {
        fun go(owner: String, repo: String, prNumber: Int) = "pr/$owner/$repo/$prNumber"
    }
    object DiscussionDetail : Route("discussion/{owner}/{repo}/{discussionNumber}") {
        fun go(owner: String, repo: String, discussionNumber: Int) = "discussion/$owner/$repo/$discussionNumber"
    }
    object EditFile : Route("edit_file/{owner}/{repo}/{branch}?path={path}&mode={mode}") {
        fun go(owner: String, repo: String, path: String, branch: String, mode: String) =
            "edit_file/$owner/$repo/$branch?path=${URLEncoder.encode(path, "UTF-8")}&mode=$mode"
    }
    object Search : Route("search")
    object UserNavGraph : Route("user_graph") {
        fun go() = "user_graph"
    }
    object UserProfile : Route("user_profile/{login}") {
        fun go(login: String) = "user_profile/$login"
    }
    object UserRepos : Route("user_repos/{login}") {
        fun go(login: String) = "user_repos/$login"
    }
    object UserStarred : Route("user_starred/{login}") {
        fun go(login: String) = "user_starred/$login"
    }
    object UserOrgs : Route("user_orgs/{login}") {
        fun go(login: String) = "user_orgs/$login"
    }
}
 
sealed class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    object Home     : BottomTab("tab_home",     "主页",   Icons.Default.Home)
    object Remote   : BottomTab("tab_remote",   "远程",   Icons.Default.Cloud)
    object Local    : BottomTab("tab_local",    "本地",   Icons.Default.Folder)
    object Settings : BottomTab("tab_settings", "设置",   Icons.Default.Settings)
}
 
/**
 * App 初始化状态三态机：
 *   Loading  — DataStore 尚未发出第一个值（<16ms），显示 Loading
 *   NeedsLogin — DataStore 已发出，token 为空，跳转登录页
 *   Ready    — DataStore 已发出，token 有效，跳转主页
 */
private sealed class AppInitState {
    object Loading    : AppInitState()
    object NeedsLogin : AppInitState()
    data class Ready(val token: String) : AppInitState()
}
 
@Composable
fun AppNavGraph(
    tokenStorage: TokenStorage,
    initialToken: String?,
    onThemeChange: (ThemeMode) -> Unit,
    onTokenConsumed: () -> Unit = {},
    initialGitHubUrl: String? = null,
    onGitHubUrlConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var isReauth   by remember { mutableStateOf(false) }
    val currentTheme by tokenStorage.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val rootEnabled by tokenStorage.rootEnabled.collectAsState(initial = false)
    val tabStepBackEnabled by tokenStorage.tabStepBack.collectAsState(initial = false)
    
    // 共享的 ViewModel，在整个 AppNavGraph 范围内可用
    val repoListVm: com.gitmob.android.ui.repos.RepoListViewModel = viewModel()
    val starVm: com.gitmob.android.ui.repos.StarListViewModel = viewModel()
 
    // 三态初始化状态机：区分"正在加载"与"已加载但无 token"，修复新用户白屏
    val initState by produceState<AppInitState>(initialValue = AppInitState.Loading) {
        tokenStorage.accessToken.collect { token ->
            value = if (token.isNullOrBlank()) AppInitState.NeedsLogin
                    else AppInitState.Ready(token)
        }
    }
 
    // 初始化阶段显示居中 Loading
    if (initState == AppInitState.Loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Coral, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        return
    }
 
    val startDest = if (initState is AppInitState.NeedsLogin) Route.Login.path else Route.Main.path
 
    // ── accounts_json 自动补全 ──────────────────────────────────────────────
    // 场景：release 混淆崩溃等原因导致 access_token 有效但 accounts_json 缺失。
    // token 存在时，自动用 API 拉取用户信息并写入 AccountStore，无需重新登录。
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(initState) {
        if (initState !is AppInitState.Ready) return@LaunchedEffect
        val accountStore = com.gitmob.android.auth.AccountStore(context)
        val existingAccounts = accountStore.accounts.first()
        if (existingAccounts.isNotEmpty()) return@LaunchedEffect  // 已有数据，无需恢复
 
        val token = tokenStorage.accessToken.first() ?: return@LaunchedEffect
        try {
            // token 有效但 accounts_json 为空 → 静默拉取用户信息补全
            val user = com.gitmob.android.api.ApiClient.api.getCurrentUser()
            val info = com.gitmob.android.auth.AccountInfo(
                login     = user.login,
                name      = user.name ?: user.login,
                email     = user.email ?: "${user.login}@users.noreply.github.com",
                avatarUrl = user.avatarUrl ?: "",
                token     = token,
            )
            accountStore.addOrUpdateAccount(info)
            tokenStorage.syncActiveAccount(info)
            com.gitmob.android.util.LogManager.i("NavGraph", "accounts_json 自动恢复成功: ${user.login}")
        } catch (_: Exception) {
            // 网络失败或 token 已失效 → 静默忽略，不影响正常使用
        }
    }
 
    // 监听 401 Token 失效事件
    LaunchedEffect(Unit) {
        ApiClient.tokenExpired.collect {
            navController.navigate(Route.Login.path) { popUpTo(0) { inclusive = true } }
        }
    }
 
    // 处理从其他 App 传入的 github.com 链接
    LaunchedEffect(initialGitHubUrl, initState) {
        val url = initialGitHubUrl ?: return@LaunchedEffect
        if (initState !is AppInitState.Ready) return@LaunchedEffect
        
        fun navigateWithBackStack(destinations: List<() -> Unit>) {
            destinations.forEachIndexed { index, navigate ->
                if (index == 0) {
                    navigate()
                } else {
                    // 后续导航不添加到返回栈（使用 launchSingleTop）
                    navigate()
                }
            }
        }
        
        when (val dest = GitHubUrlParser.parse(Uri.parse(url))) {
            is GitHubDestination.UserProfile -> {
                navController.navigate(Route.UserProfile.go(dest.login))
            }
            is GitHubDestination.Repo -> {
                navController.navigate(Route.UserProfile.go(dest.owner))
                navController.navigate(Route.RepoDetail.go(dest.owner, dest.repo))
            }
            is GitHubDestination.Issue -> {
                navController.navigate(Route.UserProfile.go(dest.owner))
                navController.navigate(Route.RepoDetail.go(dest.owner, dest.repo))
                navController.navigate(Route.IssueDetail.go(dest.owner, dest.repo, dest.number))
            }
            is GitHubDestination.PR -> {
                navController.navigate(Route.UserProfile.go(dest.owner))
                navController.navigate(Route.RepoDetail.go(dest.owner, dest.repo))
                navController.navigate(Route.PRDetail.go(dest.owner, dest.repo, dest.number))
            }
            is GitHubDestination.Discussion -> {
                navController.navigate(Route.UserProfile.go(dest.owner))
                navController.navigate(Route.RepoDetail.go(dest.owner, dest.repo))
                navController.navigate(Route.DiscussionDetail.go(dest.owner, dest.repo, dest.number))
            }
            is GitHubDestination.Commit -> {
                // Commit 详情暂时跳转到仓库详情页（提交标签页）
                navController.navigate(Route.UserProfile.go(dest.owner))
                navController.navigate(Route.RepoDetail.go(dest.owner, dest.repo))
            }
            is GitHubDestination.FileView -> {
                navController.navigate(Route.UserProfile.go(dest.owner))
                navController.navigate(Route.RepoDetail.go(dest.owner, dest.repo))
                navController.navigate(
                    Route.FileViewer.go(dest.owner, dest.repo, dest.path, dest.branch)
                )
            }
            is GitHubDestination.Home -> { /* 不跳转，保持主页 */ }
        }
        onGitHubUrlConsumed()
    }
    
    NavHost(navController = navController, startDestination = startDest) {
 
        composable(Route.Login.path) {
            LoginScreen(
                pendingToken = initialToken,
                isReauth = isReauth,
                onTokenConsumed = onTokenConsumed,
                onSuccess = {
                    isReauth = false
                    val mainInBackStack = navController.previousBackStackEntry?.destination?.route == Route.Main.path
                    if (mainInBackStack) {
                        navController.popBackStack(Route.Main.path, inclusive = false)
                    } else {
                        navController.navigate(Route.Main.path) {
                            popUpTo(Route.Login.path) { inclusive = true }
                        }
                    }
                },
            )
        }
 
        composable(Route.Main.path) {
            // ViewModel 在这里创建，作用域是 Route.Main 的 BackStackEntry
            // 两个 Tab 都接收同一实例，克隆操作因此生效
            val localVm: LocalRepoViewModel = viewModel()
            MainScreen(
                tokenStorage         = tokenStorage,
                currentTheme         = currentTheme,
                rootEnabled          = rootEnabled,
                onThemeChange        = onThemeChange,
                onNavigateToSettings = { /* 已移入 Tab */ },
                onRepoClick          = { owner, repo ->
                    navController.navigate(Route.RepoDetail.go(owner, repo))
                },
                onLocalRepoClick     = { repoId ->
                    navController.navigate(Route.LocalRepoDetail.go(repoId))
                },
                onCreateRepo = { org -> navController.navigate(Route.CreateRepo.go(org)) },
                localVm      = localVm,
                repoListVm   = repoListVm,
                starVm       = starVm,
                onUserClick  = { login ->
                    navController.navigate(Route.UserProfile.go(login))
                },
                onLogout     = { forceReauth ->
                    // forceReauth=true: 取消授权(deleteGrant)后需重新完整授权
                    // forceReauth=false: 普通退出登录(revokeToken)，grant保留，直接快速重登
                    isReauth = forceReauth
                    navController.navigate(Route.Login.path) { popUpTo(0) { inclusive = true } }
                },
                onSwitchAccount = {
                    navController.navigate(Route.Main.path) {
                        popUpTo(Route.Main.path) { inclusive = true }
                    }
                },
                onAddAccount = {
                    navController.navigate(Route.Login.path) {
                        popUpTo(Route.Main.path) { saveState = true }
                    }
                },
                onSearchClick = {
                    navController.navigate(Route.Search.path)
                },
            )
        }
 
        composable(
            route = Route.CreateRepo.path,
            arguments = listOf(
                androidx.navigation.navArgument("org") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStack ->
            val orgLogin = backStack.arguments?.getString("org")
            CreateRepoScreen(
                onBack    = { navController.popBackStack() },
                orgLogin  = orgLogin,
                onCreated = { owner, repo ->
                    navController.navigate(Route.RepoDetail.go(owner, repo)) {
                        popUpTo(Route.CreateRepo.go(orgLogin)) { inclusive = true }
                    }
                },
            )
        }
 
        composable(
            route = "repo/{owner}/{repo}",
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo")  { type = NavType.StringType },
            ),
        ) { back ->
            val owner = back.arguments?.getString("owner") ?: ""
            val repo  = back.arguments?.getString("repo")  ?: ""
            RepoDetailScreen(
                owner = owner, repoName = repo,
                tabStepBackEnabled = tabStepBackEnabled,
                onBack = { navController.popBackStack() },
                onFileClick = { o, r, path, branch ->
                    navController.navigate(Route.FileViewer.go(o, r, path, branch))
                },
                onIssueClick = { issueNumber ->
                    navController.navigate(Route.IssueDetail.go(owner, repo, issueNumber))
                },
                onPRClick = { prNumber ->
                    navController.navigate(Route.PRDetail.go(owner, repo, prNumber))
                },
                onDiscussionClick = { discussionNumber ->
                    navController.navigate(Route.DiscussionDetail.go(owner, repo, discussionNumber))
                },
                onForkedRepoClick = { o, r ->
                    navController.navigate(Route.RepoDetail.go(o, r))
                },
                onOwnerClick = { login ->
                    navController.navigate(Route.UserProfile.go(login))
                },
                onEditFile = { path, mode, branch ->
                    val fullPath = if (mode == "NEW" && path.isNotEmpty()) {
                        "$path/"
                    } else {
                        path
                    }
                    navController.navigate(Route.EditFile.go(owner, repo, fullPath, branch, mode))
                },
                vm = viewModel {
                        val app = checkNotNull(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                        val handle = androidx.lifecycle.SavedStateHandle(mapOf("owner" to owner, "repo" to repo))
                        RepoDetailViewModel(app, handle)
                    },
            )
        }
 
        composable(
            route = Route.FileViewer.path,
            arguments = listOf(
                navArgument("owner")  { type = NavType.StringType },
                navArgument("repo")   { type = NavType.StringType },
                navArgument("branch") { type = NavType.StringType },
                navArgument("path")   { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val owner  = back.arguments?.getString("owner")  ?: ""
            val repo   = back.arguments?.getString("repo")   ?: ""
            val branch = back.arguments?.getString("branch") ?: ""
            val path   = URLDecoder.decode(back.arguments?.getString("path") ?: "", "UTF-8")
            FileViewerScreen(
                owner = owner, repo = repo, path = path, ref = branch,
                onBack = { navController.popBackStack() },
                onEdit = {
                    navController.navigate(Route.EditFile.go(owner, repo, path, branch, "EDIT"))
                }
            )
        }
 
        composable(
            route = "issue/{owner}/{repo}/{issueNumber}",
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("issueNumber") { type = NavType.IntType },
            ),
        ) { back ->
            val owner = back.arguments?.getString("owner") ?: ""
            val repo = back.arguments?.getString("repo") ?: ""
            val issueNumber = back.arguments?.getInt("issueNumber") ?: 0
            IssueDetailScreen(
                owner = owner,
                repoName = repo,
                issueNumber = issueNumber,
                onBack = { navController.popBackStack() },
            )
        }
 
        composable(
            route = "pr/{owner}/{repo}/{prNumber}",
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("prNumber") { type = NavType.IntType },
            ),
        ) { back ->
            val owner = back.arguments?.getString("owner") ?: ""
            val repo = back.arguments?.getString("repo") ?: ""
            val prNumber = back.arguments?.getInt("prNumber") ?: 0
            PRDetailScreen(
                owner = owner,
                repoName = repo,
                prNumber = prNumber,
                onBack = { navController.popBackStack() },
            )
        }
 
        composable(
            route = "discussion/{owner}/{repo}/{discussionNumber}",
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("discussionNumber") { type = NavType.IntType },
            ),
        ) { back ->
            val owner = back.arguments?.getString("owner") ?: ""
            val repo = back.arguments?.getString("repo") ?: ""
            val discussionNumber = back.arguments?.getInt("discussionNumber") ?: 0
            DiscussionDetailScreen(
                owner = owner,
                repoName = repo,
                discussionNumber = discussionNumber,
                onBack = { navController.popBackStack() },
            )
        }
 
        composable(
            route = Route.EditFile.path,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("branch") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("mode") { type = NavType.StringType; defaultValue = "EDIT" },
            ),
        ) { back ->
            val owner = back.arguments?.getString("owner") ?: ""
            val repo = back.arguments?.getString("repo") ?: ""
            val branch = back.arguments?.getString("branch") ?: ""
            val path = URLDecoder.decode(back.arguments?.getString("path") ?: "", "UTF-8")
            val modeStr = back.arguments?.getString("mode") ?: "EDIT"
            val mode = try { EditFileMode.valueOf(modeStr) } catch (e: Exception) { EditFileMode.EDIT }
            val repository = remember { com.gitmob.android.data.RepoRepository() }
            
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = rememberCoroutineScope()
            val c = com.gitmob.android.ui.theme.LocalGmColors.current
            var initialContent by remember { mutableStateOf("") }
            var initialFileName by remember { mutableStateOf(path.substringAfterLast("/")) }
            var loading by remember { mutableStateOf(mode == EditFileMode.EDIT) }
            var sha by remember { mutableStateOf<String?>(null) }
            var showCommitDialog by remember { mutableStateOf(false) }
            var pendingFileName by remember { mutableStateOf("") }
            var pendingContent by remember { mutableStateOf("") }
            var pendingCommitMsg by remember { mutableStateOf("") }
            var pendingFullPath by remember { mutableStateOf("") }
            
            LaunchedEffect(Unit) {
                if (mode == EditFileMode.EDIT) {
                    try {
                        val fileWithInfo = repository.getFileWithInfo(owner, repo, path, branch)
                        initialContent = fileWithInfo.content
                        sha = fileWithInfo.info.sha
                        loading = false
                    } catch (e: Exception) {
                        loading = false
                    }
                }
            }
            
            if (mode == EditFileMode.EDIT && loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.gitmob.android.ui.common.LoadingBox(Modifier)
                }
            } else {
                EditFileScreen(
                    mode = mode,
                    fileName = initialFileName,
                    initialContent = initialContent,
                    onBack = { navController.popBackStack() },
                    onSave = { newFileName, newContent ->
                        val fullPath = if (path.contains("/")) {
                            val parentPath = path.substringBeforeLast("/")
                            "$parentPath/$newFileName"
                        } else {
                            newFileName
                        }
                        val commitMsg = if (mode == EditFileMode.EDIT) "Update $fullPath" else "Create $fullPath"
                        
                        pendingFileName = newFileName
                        pendingContent = newContent
                        pendingCommitMsg = commitMsg
                        pendingFullPath = fullPath
                        showCommitDialog = true
                    }
                )
            }
            
            if (showCommitDialog) {
                CommitMessageDialog(
                    defaultMessage = pendingCommitMsg,
                    c = c,
                    onConfirm = { msg ->
                        showCommitDialog = false
                        scope.launch {
                            try {
                                repository.createOrUpdateFile(
                                    owner = owner,
                                    repo = repo,
                                    path = pendingFullPath,
                                    message = msg,
                                    content = pendingContent,
                                    sha = sha,
                                    branch = branch,
                                )
                                navController.popBackStack()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "保存失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDismiss = { showCommitDialog = false }
                )
            }
        }
 
        composable(
            route = "local_repo/{repoId}",
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
            ),
        ) { back ->
            val repoId = back.arguments?.getString("repoId") ?: ""
            LocalRepoDetailScreen(
                repoId = repoId,
                onBack = { navController.popBackStack() },
            )
        }
 
        composable(Route.Search.path) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onRepoClick = { owner, repo ->
                    navController.navigate(Route.RepoDetail.go(owner, repo))
                },
                onOrgClick = { orgLogin ->
                    navController.popBackStack()
                },
                onUserClick = { login ->
                    navController.navigate(Route.UserProfile.go(login))
                },
            )
        }
 
        // 用户主页
        composable(
            route = Route.UserProfile.path,
            arguments = listOf(navArgument("login") { type = NavType.StringType })
        ) { backStackEntry ->
            val login = backStackEntry.arguments?.getString("login") ?: ""
            val homeVm: com.gitmob.android.ui.home.HomeViewModel = viewModel()
            val favVm: com.gitmob.android.data.FavoritesManager = viewModel()
            
            com.gitmob.android.ui.home.HomeScreen(
                onSearchClick = { 
                    LogManager.d("NavGraph", "用户主页点击搜索，login=$login")
                    navController.navigate(Route.Search.path) 
                },
                onSettingsClick = { 
                    LogManager.d("NavGraph", "用户主页点击设置，login=$login")
                    navController.navigate(Route.Settings.path) 
                },
                onRepoClick = { owner, repo -> 
                    LogManager.d("NavGraph", "用户主页点击仓库，owner=$owner, repo=$repo, 当前用户=$login")
                    navController.navigate(Route.RepoDetail.go(owner, repo)) 
                },
                onReposClick = { 
                    LogManager.d("NavGraph", "用户主页点击仓库列表，login=$login")
                    navController.navigate(Route.UserRepos.go(login))
                },
                onStarredClick = {
                    LogManager.d("NavGraph", "用户主页点击星标列表，login=$login")
                    navController.navigate(Route.UserStarred.go(login))
                },
                onOrgClick = {
                    LogManager.d("NavGraph", "用户主页点击组织列表，login=$login")
                    navController.navigate(Route.UserOrgs.go(login))
                },
                onUserClick = { targetLogin ->
                    LogManager.d("NavGraph", "用户主页点击用户，当前用户=$login, 目标用户=$targetLogin")
                    navController.navigate(Route.UserProfile.go(targetLogin))
                },
                onUserReposClick = { targetLogin ->
                    LogManager.d("NavGraph", "用户主页点击用户仓库，当前用户=$login, 目标用户=$targetLogin")
                    navController.navigate(Route.UserRepos.go(targetLogin))
                },
                onUserStarredClick = { targetLogin ->
                    LogManager.d("NavGraph", "用户主页点击用户星标，当前用户=$login, 目标用户=$targetLogin")
                    navController.navigate(Route.UserStarred.go(targetLogin))
                },
                onUserOrgsClick = { targetLogin ->
                    LogManager.d("NavGraph", "用户主页点击用户组织，当前用户=$login, 目标用户=$targetLogin")
                    navController.navigate(Route.UserOrgs.go(targetLogin))
                },
                onBack = { 
                    LogManager.d("NavGraph", "用户主页点击返回，login=$login")
                    navController.popBackStack() 
                },
                targetUserLogin = login,
                vm = homeVm,
                favVm = favVm,
            )
        }
        
        // 用户仓库列表
        composable(
            route = Route.UserRepos.path,
            arguments = listOf(navArgument("login") { type = NavType.StringType })
        ) { backStackEntry ->
            val login = backStackEntry.arguments?.getString("login") ?: ""
            val context = androidx.compose.ui.platform.LocalContext.current
            val localVm: com.gitmob.android.ui.local.LocalRepoViewModel = viewModel()
            
            LogManager.d("NavGraph", "进入用户仓库列表页面，login=$login")
            
            // 切换到用户仓库
            LaunchedEffect(login) {
                repoListVm.switchToUserRepos(login, null)
            }
            
            // 页面离开时重置 ViewModel 状态
            DisposableEffect(Unit) {
                onDispose {
                    repoListVm.resetToCurrentUser()
                }
            }
            
            com.gitmob.android.ui.repos.RepoListScreen(
                onRepoClick = { owner, repo ->
                    LogManager.d("NavGraph", "用户仓库列表点击仓库，owner=$owner, repo=$repo, 当前用户=$login")
                    navController.navigate(Route.RepoDetail.go(owner, repo))
                },
                onCreateRepo = { navController.navigate(Route.CreateRepo.go(null)) },
                onCloneRepo = { url -> localVm.startClone(url) },
                onBack = { navController.popBackStack() },
                vm = repoListVm,
                starVm = starVm,
                onSearchClick = { navController.navigate(Route.Search.path) },
            )
        }
        
        // 用户星标列表
        composable(
            route = Route.UserStarred.path,
            arguments = listOf(navArgument("login") { type = NavType.StringType })
        ) { backStackEntry ->
            val login = backStackEntry.arguments?.getString("login") ?: ""
            val context = androidx.compose.ui.platform.LocalContext.current
            val localVm: com.gitmob.android.ui.local.LocalRepoViewModel = viewModel()
            
            LogManager.d("NavGraph", "进入用户星标列表页面，login=$login")
            
            // 切换到用户星标
            LaunchedEffect(login) {
                repoListVm.switchToUserStarred(login, null)
            }
            
            // 页面离开时重置 ViewModel 状态
            DisposableEffect(Unit) {
                onDispose {
                    repoListVm.resetToCurrentUser()
                }
            }
            
            com.gitmob.android.ui.repos.RepoListScreen(
                onRepoClick = { owner, repo ->
                    LogManager.d("NavGraph", "用户星标列表点击仓库，owner=$owner, repo=$repo, 当前用户=$login")
                    navController.navigate(Route.RepoDetail.go(owner, repo))
                },
                onCreateRepo = { navController.navigate(Route.CreateRepo.go(null)) },
                onCloneRepo = { url -> localVm.startClone(url) },
                onBack = { navController.popBackStack() },
                vm = repoListVm,
                starVm = starVm,
                onSearchClick = { navController.navigate(Route.Search.path) },
            )
        }
        
        // 用户组织列表
        composable(
            route = Route.UserOrgs.path,
            arguments = listOf(navArgument("login") { type = NavType.StringType })
        ) { backStackEntry ->
            val login = backStackEntry.arguments?.getString("login") ?: ""
            val context = androidx.compose.ui.platform.LocalContext.current
            val localVm: com.gitmob.android.ui.local.LocalRepoViewModel = viewModel()
            
            LogManager.d("NavGraph", "进入用户组织列表页面，login=$login")
            
            // 切换到用户组织
            LaunchedEffect(login) {
                repoListVm.switchToUserOrgs(login, null)
            }
            
            // 页面离开时重置 ViewModel 状态
            DisposableEffect(Unit) {
                onDispose {
                    repoListVm.resetToCurrentUser()
                }
            }
            
            com.gitmob.android.ui.repos.RepoListScreen(
                onRepoClick = { owner, repo ->
                    LogManager.d("NavGraph", "用户组织列表点击仓库，owner=$owner, repo=$repo, 当前用户=$login")
                    navController.navigate(Route.RepoDetail.go(owner, repo))
                },
                onCreateRepo = { navController.navigate(Route.CreateRepo.go(null)) },
                onCloneRepo = { url -> localVm.startClone(url) },
                onBack = { navController.popBackStack() },
                vm = repoListVm,
                starVm = starVm,
                onSearchClick = { navController.navigate(Route.Search.path) },
            )
        }
    }
}
 
// ─── MainScreen：不用嵌套 Scaffold，改用 Column+Box 避免双层 padding ──────────
 
@Composable
private fun MainScreen(
    tokenStorage: TokenStorage,
    currentTheme: ThemeMode,
    rootEnabled: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigateToSettings: () -> Unit,
    onRepoClick: (String, String) -> Unit,
    onLocalRepoClick: (String) -> Unit,
    onCreateRepo: (org: String?) -> Unit,
    localVm: LocalRepoViewModel,
    onLogout: (forceReauth: Boolean) -> Unit,
    onUserClick: (String) -> Unit,
    repoListVm: com.gitmob.android.ui.repos.RepoListViewModel,
    starVm: com.gitmob.android.ui.repos.StarListViewModel,
    onSwitchAccount: (com.gitmob.android.auth.AccountInfo) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSettingsTabClick: () -> Unit = {},
) {
    val c = LocalGmColors.current
    val tabs = listOf(BottomTab.Home, BottomTab.Remote, BottomTab.Local, BottomTab.Settings)
    val tabNavController = rememberNavController()
    val navBackStack by tabNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route
 
    // 共享的 ViewModel 从外部传入
    val repoListState by repoListVm.state.collectAsState()
 
    // 当 targetUserLogin 不为 null 时自动切换到 Remote Tab（从 UserProfile 页面点击返回的情况）
    // 但不应该在用户查看其他用户仓库列表时触发
    LaunchedEffect(repoListState.targetUserLogin) {
        // 只有在当前路由是Home或其他非用户仓库页面时才切换到Remote Tab
        if (repoListState.targetUserLogin != null && 
            currentRoute != BottomTab.Remote.route &&
            currentRoute != Route.UserRepos.path &&
            currentRoute != Route.UserStarred.path &&
            currentRoute != Route.UserOrgs.path) {
            tabNavController.navigate(BottomTab.Remote.route) {
                popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
        }
    }
 
    /** 切换到 Remote Tab 并可选地执行额外操作 */
    fun navigateToRemote(extra: () -> Unit = {}) {
        extra()
        tabNavController.navigate(BottomTab.Remote.route) {
            popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }
 
    // 用 Column 替代 Scaffold：避免嵌套 Scaffold 造成 bottomBar insets 重复
    Column(modifier = Modifier.fillMaxSize()) {
 
        // 观察共享 VM 的克隆状态，在此层级弹出文件选择器
        val vmState by localVm.state.collectAsState()
        val vmBookmarks by localVm.customBookmarks.collectAsState()
        
        var showNewProjectPicker by remember { mutableStateOf(false) }
        var newProjectPickerSession by remember { mutableIntStateOf(0) }
        var newProjectParentDir by remember { mutableStateOf("") }
        var newProjectDialog by remember { mutableStateOf(false) }
        var newProjectName by remember { mutableStateOf("") }
 
        // 上部内容区域（Tab 页面各自有自己的 Scaffold + TopAppBar）
        // picker 仅在其所属 tab 激活时渲染；切走时视觉消失但 rememberSaveable 保留路径状态；切回自动恢复
        val isOnLocalTab  = currentRoute == BottomTab.Local.route
        val isOnRemoteTab = currentRoute == BottomTab.Remote.route
 
        // SaveableStateHolder：Navigation Compose 内部保留各 Tab 状态所用的同一机制
        // 让被条件移出 composition 的 FilePickerScreen 在重新进入时恢复所有 rememberSaveable 状态
        val pickerStateHolder = rememberSaveableStateHolder()
 
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = tabNavController,
                startDestination = BottomTab.Home.route,
            ) {
                composable(BottomTab.Home.route) {
                    val homeVm: HomeViewModel = viewModel()
                    HomeScreen(
                        onSearchClick   = onSearchClick,
                        onSettingsClick = {
                            tabNavController.navigate(BottomTab.Settings.route) {
                                popUpTo(tabNavController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        onRepoClick     = onRepoClick,
                        onReposClick    = { navigateToRemote() },
                        onStarredClick  = {
                            // 激活星标模式，再跳转到 Remote Tab
                            navigateToRemote { starVm.toggleStarMode() }
                        },
                        onOrgClick      = { org ->
                            // 切换到对应组织上下文，再跳转到 Remote Tab
                            navigateToRemote {
                                repoListVm.switchContext(
                                    com.gitmob.android.ui.repos.OrgContext(
                                        login = org.login, avatarUrl = org.avatarUrl, isUser = false
                                    )
                                )
                            }
                        },
                        onUserClick     = onUserClick,
                        vm = homeVm,
                    )
                }
                composable(BottomTab.Remote.route) {
                    RepoListScreen(
                        onRepoClick    = onRepoClick,
                        onCreateRepo   = {
                            val org = repoListState.currentContext?.login
                            onCreateRepo(org)
                        },
                        onCloneRepo    = { url -> localVm.startClone(url) },
                        vm             = repoListVm,
                        starVm         = starVm,
                        onSearchClick  = onSearchClick,
                    )
                }
                composable(BottomTab.Local.route) {
                    LocalRepoListScreen(
                        rootEnabled = rootEnabled, 
                        vm = localVm,
                        onShowNewProjectPicker = { showNewProjectPicker = true },
                        onRepoClick = { repoId -> onLocalRepoClick(repoId) }
                    )
                }
                composable(BottomTab.Settings.route) {
                    val settingsScope = rememberCoroutineScope()
                    val rootEnabled2 by tokenStorage.rootEnabled.collectAsState(initial = false)
                    val tabStepBackEnabled by tokenStorage.tabStepBack.collectAsState(initial = false)
                    SettingsScreen(
                        tokenStorage  = tokenStorage,
                        currentTheme  = currentTheme,
                        rootEnabled   = rootEnabled2,
                        tabStepBackEnabled = tabStepBackEnabled,
                        onThemeChange = onThemeChange,
                        onRootToggle  = { enabled ->
                            settingsScope.launch { tokenStorage.setRootEnabled(enabled) }
                        },
                        onTabStepBackToggle = { enabled ->
                            settingsScope.launch { tokenStorage.setTabStepBack(enabled) }
                        },
                        onBack   = { /* 底部 tab，无需返回 */ },
                        onLogout = { forceReauth ->
                            onLogout(forceReauth)
                        },
                        onSwitchAccount = { account ->
                            settingsScope.launch {
                                com.gitmob.android.api.ApiClient.rebuild()
                            }
                            onSwitchAccount(account)
                        },
                        onAddAccount = { onAddAccount() },
                    )
                }
            }
 
            // ── 本地 tab 的 picker 覆盖层（仅本地 tab 激活时渲染）──────────────
            if (isOnLocalTab) {
                if (vmState.showFilePicker) {
                    // SaveableStateProvider 为 FilePickerScreen 内部的 rememberSaveable 提供持久化 registry
                    // 即使 Composable 从 composition 中移除，状态也被 holder 保留，重新进入时恢复
                    pickerStateHolder.SaveableStateProvider("local_file_picker_${vmState.filePickerSessionId}") {
                        FilePickerScreen(
                            title = "导入本地目录",
                            mode = PickerMode.DIRECTORY,
                            detectGitRepos = true,
                            rootEnabled = rootEnabled,
                            customBookmarks = vmBookmarks,
                            onAddBookmark    = { bm -> localVm.addBookmark(bm) },
                            onRemoveBookmark = { bm -> localVm.removeBookmark(bm) },
                            onConfirm = { path, _ -> localVm.importDirectory(path) },
                            onDismiss = localVm::hideFilePicker,
                        )
                    }
                }
                if (showNewProjectPicker) {
                    pickerStateHolder.SaveableStateProvider("local_new_project_picker_$newProjectPickerSession") {
                        FilePickerScreen(
                            title = "选择项目父目录",
                            mode = PickerMode.DIRECTORY,
                            rootEnabled = rootEnabled,
                            customBookmarks = vmBookmarks,
                            onAddBookmark    = { bm -> localVm.addBookmark(bm) },
                            onRemoveBookmark = { bm -> localVm.removeBookmark(bm) },
                            onConfirm = { path, _ ->
                                newProjectParentDir = path
                                showNewProjectPicker = false
                                newProjectPickerSession++
                                newProjectDialog = true
                            },
                            onDismiss = {
                                showNewProjectPicker = false
                                newProjectPickerSession++
                            },
                        )
                    }
                }
            }
 
            // ── 远程 tab 的 clone picker（仅远程 tab 激活时渲染）──────────────
            if (isOnRemoteTab) {
                if (vmState.showClonePicker) {
                    pickerStateHolder.SaveableStateProvider("remote_clone_picker_${vmState.clonePickerSessionId}") {
                        FilePickerScreen(
                            title = "选择克隆目标目录",
                            mode = PickerMode.DIRECTORY,
                            rootEnabled = rootEnabled,
                            customBookmarks = vmBookmarks,
                            onAddBookmark    = { bm -> localVm.addBookmark(bm) },
                            onRemoveBookmark = { bm -> localVm.removeBookmark(bm) },
                            onConfirm = { path, _ ->
                                localVm.hideClonePicker()
                                val targetPath = if (path.endsWith("/")) path else "$path"
                                localVm.cloneRepo(vmState.pendingCloneUrl, targetPath)
                            },
                            onDismiss = localVm::hideClonePicker,
                        )
                    }
                }
            }
            
            // 新建项目对话框
            val c = LocalGmColors.current
            if (newProjectDialog) {
                AlertDialog(
                    onDismissRequest = { newProjectDialog = false },
                    containerColor = c.bgCard,
                    title = { Text("新建本地项目", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("父目录：$newProjectParentDir",
                                fontSize = 11.sp, color = c.textTertiary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            OutlinedTextField(
                                value = newProjectName, onValueChange = { newProjectName = it },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text("项目名称") },
                                placeholder = { Text("my-project", color = c.textTertiary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                                    focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                                    focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
                                    focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary,
                                ),
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newProjectName.isNotBlank()) {
                                    localVm.createLocalProject(newProjectParentDir, newProjectName)
                                    newProjectDialog = false
                                    newProjectName = ""
                                }
                            },
                            enabled = newProjectName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Coral),
                        ) { Text("创建 & Init") }
                    },
                    dismissButton = {
                        TextButton(onClick = { newProjectDialog = false }) { Text("取消", color = c.textSecondary) }
                    },
                )
            }
        }
 
        // 底部导航栏（固定在 Column 底部，不参与 Scaffold inset 计算）
        NavigationBar(
            containerColor = c.bgCard,
            tonalElevation = 0.dp,
            modifier = Modifier.navigationBarsPadding(),
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = currentRoute == tab.route,
                    onClick = {
                        val localPickerOpen  = vmState.showFilePicker || showNewProjectPicker
                        val remotePickerOpen = vmState.showClonePicker
 
                        when {
                            // 点的是当前本地 tab 且本地 picker 开着 → 关闭 picker，不导航
                            tab.route == BottomTab.Local.route
                                && currentRoute == BottomTab.Local.route
                                && localPickerOpen -> {
                                localVm.hideFilePicker()
                                showNewProjectPicker = false
                            }
                            // 点的是当前远程 tab 且远程 clone picker 开着 → 关闭 picker，不导航
                            tab.route == BottomTab.Remote.route
                                && currentRoute == BottomTab.Remote.route
                                && remotePickerOpen -> {
                                localVm.hideClonePicker()
                            }
                            // 其余（点其他 tab、无 picker）→ 正常导航，picker 状态由 rememberSaveable 保留
                            else -> {
                                tabNavController.navigate(tab.route) {
                                    popUpTo(tabNavController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    },
                    icon = { Icon(tab.icon, null) },
                    label = { Text(tab.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor   = Coral,
                        selectedTextColor   = Coral,
                        unselectedIconColor = c.textTertiary,
                        unselectedTextColor = c.textTertiary,
                        indicatorColor      = CoralDim,
                    ),
                )
            }
        }
    }
}
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    owner: String, repo: String, path: String, ref: String, onBack: () -> Unit,
    onEdit: () -> Unit = {},
) {
    val c = LocalGmColors.current
    val repository = remember { RepoRepository() }
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error   by remember { mutableStateOf<String?>(null) }
    var fileInfo by remember { mutableStateOf<com.gitmob.android.api.GHContent?>(null) }
    
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyCommits by remember { mutableStateOf<List<com.gitmob.android.api.GHCommit>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var selectedCommitForHistory by remember { mutableStateOf<com.gitmob.android.api.GHCommitFull?>(null) }
    var commitDetailLoading by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
 
    LaunchedEffect(path) {
        loading = true
        try { 
            val fileWithInfo = repository.getFileWithInfo(owner, repo, path, ref)
            fileInfo = fileWithInfo.info
            content = fileWithInfo.content
            error = null 
        } catch (e: Exception) { 
            error = e.message 
        } finally { 
            loading = false 
        }
    }
 
    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Text(path.substringAfterLast("/"), fontWeight = FontWeight.Medium,
                        fontSize = 15.sp, color = c.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val url = "https://github.com/$owner/$repo/blob/$ref/$path"
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "分享"))
                    }) {
                        Icon(Icons.Default.Share, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                    }
                    
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑", fontSize = 13.sp, color = c.textPrimary) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        null,
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", fontSize = 13.sp, color = RedColor) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        null,
                                        tint = RedColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("历史记录", fontSize = 13.sp, color = c.textPrimary) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.History,
                                        null,
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showHistory = true
                                    historyLoading = true
                                    scope.launch {
                                        try {
                                            historyCommits = repository.getCommits(owner, repo, ref, path)
                                        } catch (e: Exception) {
                                            error = e.message
                                        } finally {
                                            historyLoading = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
    ) { padding ->
        val isMd = path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }
        when {
            loading       -> LoadingBox(Modifier.padding(padding))
            error != null -> ErrorBox(error!!) {}
            isMd          -> com.gitmob.android.ui.common.GmMarkdownWebView(
                markdown = content,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.padding(padding),
            ) {
                item {
                    Text(content, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = c.textPrimary, lineHeight = 18.sp)
                }
            }
        }
    }
    
    if (showDeleteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = c.bgCard,
            icon = {
                Icon(Icons.Default.Delete, null, tint = RedColor, modifier = Modifier.size(28.dp))
            },
            title = { Text("确认删除", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text("确定要删除文件 \"${path.substringAfterLast("/")}\" 吗？此操作无法撤销。", fontSize = 13.sp, color = c.textSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            try {
                                repository.deleteFile(
                                    owner = owner,
                                    repo = repo,
                                    path = path,
                                    message = "Delete $path",
                                    sha = fileInfo?.sha ?: "",
                                    branch = ref,
                                )
                                onBack()
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedColor),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }
    
    if (showHistory) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            sheetState = sheetState,
            containerColor = c.bgCard,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("历史记录", color = c.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    IconButton(onClick = { showHistory = false }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", tint = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                GmDivider()
                Spacer(Modifier.height(8.dp))
                
                if (historyLoading) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Coral, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (historyCommits.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("暂无历史记录", fontSize = 13.sp, color = c.textTertiary)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(historyCommits) { commit ->
                            Column(
                                modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch {
                                            commitDetailLoading = true
                                            try {
                                                selectedCommitForHistory = repository.getCommitDetail(owner, repo, commit.sha)
                                            } catch (e: Exception) {
                                                error = e.message
                                            } finally {
                                                commitDetailLoading = false
                                            }
                                        }
                                    }.padding(12.dp),
                            ) {
                                Text(commit.commit.message.lines().first(), fontSize = 13.sp,
                                    color = c.textPrimary, fontWeight = FontWeight.Medium, maxLines = 2)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (commit.author != null) {
                                        com.gitmob.android.ui.common.AvatarImage(commit.author.avatarUrl, 20)
                                    }
                                    Text(commit.commit.author.name, fontSize = 11.sp, color = c.textSecondary)
                                    Spacer(Modifier.weight(1f))
                                    Text(commit.sha.take(7), fontSize = 10.sp, color = Coral,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.background(CoralDim, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 1.dp))
                                    Text(
                                        try { java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                            .format(java.time.OffsetDateTime.parse(commit.commit.author.date))
                                        } catch (_: Exception) { commit.commit.author.date },
                                        fontSize = 11.sp, color = c.textTertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    selectedCommitForHistory?.let { commit ->
        val selectedFilePatchForHistory = remember { mutableStateOf<FilePatchInfo?>(null) }
 
        selectedFilePatchForHistory.value?.let { info ->
            FileDiffSheet(info = info, c = c, vm = viewModel {
                    val app = checkNotNull(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    val handle = androidx.lifecycle.SavedStateHandle(mapOf("owner" to owner, "repo" to repo))
                    RepoDetailViewModel(app, handle)
                },
                onDismiss = { selectedFilePatchForHistory.value = null })
        }
 
        ModalBottomSheet(
            onDismissRequest = { selectedCommitForHistory = null },
            containerColor = c.bgCard,
            dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        commit.sha.take(7),
                        fontSize = 12.sp, color = Coral, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(CoralDim, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (commit.stats != null) {
                        Text("+${commit.stats.additions}", fontSize = 12.sp, color = Green)
                        Text("-${commit.stats.deletions}", fontSize = 12.sp, color = RedColor)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(commit.commit.message, fontSize = 14.sp, color = c.textPrimary,
                    lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                GmDivider()
                Spacer(Modifier.height(12.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (commit.author != null) {
                        com.gitmob.android.ui.common.AvatarImage(commit.author.avatarUrl, 32)
                    }
                    Column {
                        Text(commit.commit.author.name, fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            try { java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                .format(java.time.OffsetDateTime.parse(commit.commit.author.date))
                            } catch (_: Exception) { commit.commit.author.date },
                            fontSize = 11.sp, color = c.textTertiary
                        )
                    }
                }
                
                if (commit.files?.isNotEmpty() == true) {
                    Spacer(Modifier.height(16.dp))
                    GmDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("变更文件", fontSize = 14.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    
                    commit.files.forEach { file ->
                        val status = when (file.status) {
                            "added" -> "新增"
                            "removed" -> "删除"
                            "modified" -> "修改"
                            "renamed" -> "重命名"
                            else -> file.status
                        }
                        val statusColor = when (file.status) {
                            "added" -> Green
                            "removed" -> RedColor
                            else -> c.textSecondary
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.bgItem, RoundedCornerShape(8.dp))
                                .clickable {
                                    file.patch?.let { patch ->
                                        selectedFilePatchForHistory.value = FilePatchInfo(
                                            filename         = file.filename,
                                            patch            = patch,
                                            additions        = file.additions,
                                            deletions        = file.deletions,
                                            status           = file.status,
                                            parentSha        = commit.parentSha,
                                            previousFilename = file.previousFilename,
                                            owner            = owner,
                                            repoName         = repo,
                                            currentSha       = commit.sha,
                                        )
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (file.status == "added") Icons.Default.Add else if (file.status == "removed") Icons.Default.Remove else Icons.Default.Edit,
                                null,
                                tint = statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.filename, fontSize = 12.sp, color = c.textPrimary, maxLines = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(status, fontSize = 11.sp, color = statusColor)
                                    if (file.additions > 0) Text("+${file.additions}", fontSize = 11.sp, color = Green)
                                    if (file.deletions > 0) Text("-${file.deletions}", fontSize = 11.sp, color = RedColor)
                                }
                            }
                            if (file.patch != null) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}