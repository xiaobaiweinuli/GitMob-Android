package com.gitmob.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.gitmob.android.auth.AccountInfo
import com.gitmob.android.auth.AuthType
import com.gitmob.android.auth.RootManager
import com.gitmob.android.auth.ThemeMode
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.ui.theme.*
import com.gitmob.android.ui.update.UpdateDialog
import com.gitmob.android.util.EmojiManager
import com.gitmob.android.util.GmDownloadManager
import com.gitmob.android.util.LanguageManager
import com.gitmob.android.util.LogLevel
import com.gitmob.android.util.LogManager
import com.gitmob.android.util.UpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.painterResource
import com.gitmob.android.R
import com.gitmob.android.BuildConfig
import androidx.compose.foundation.Image
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.material.icons.filled.FileUpload
import com.gitmob.android.data.FavoritesManager
import com.gitmob.android.data.ImportConflict
import com.gitmob.android.data.ImportConflictType
import com.gitmob.android.data.ImportResult
import com.gitmob.android.data.ConflictResolution
import com.gitmob.android.ui.filepicker.FilePickerScreen
import com.gitmob.android.ui.filepicker.PickerMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tokenStorage: TokenStorage,
    currentTheme: ThemeMode,
    rootEnabled: Boolean,
    tabStepBackEnabled: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onRootToggle: (Boolean) -> Unit,
    onTabStepBackToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    onLogout: (forceReauth: Boolean) -> Unit,
    onSwitchAccount: (com.gitmob.android.auth.AccountInfo) -> Unit = {},
    onAddAccount: () -> Unit = {},
) {
    val c = LocalGmColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile by tokenStorage.userProfile.collectAsState(initial = null)
    val logLevelIdx by tokenStorage.logLevel.collectAsState(initial = 1)

    // FavoritesManager 实例
    val favoritesManager = androidx.lifecycle.viewmodel.compose.viewModel<FavoritesManager>()

    // 多账号
    val accountStore = remember { com.gitmob.android.auth.AccountStore(context) }
    val allAccounts by accountStore.accounts.collectAsState(initial = emptyList())
    val activeLogin by tokenStorage.userLogin.collectAsState(initial = null)
    val activeAccount = remember(allAccounts, activeLogin) {
        allAccounts.find { it.login == activeLogin }
    }

    // 初始化 FavoritesManager
    LaunchedEffect(activeLogin) {
        activeLogin?.let { favoritesManager.init(it) }
    }
    var accountCardExpanded by remember { mutableStateOf(false) }

    var rootCheckMsg by remember { mutableStateOf<String?>(null) }
    var rootChecking by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var showExportLogPicker by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var checkingForUpdate by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<UpdateManager.Release?>(null) }

    // 收藏夹导出导入
    var showExportFavPicker by remember { mutableStateOf(false) }
    var showImportFavPicker by remember { mutableStateOf(false) }
    var showImportConflictDialog by remember { mutableStateOf(false) }
    var importJsonString by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var importResolutions by remember { mutableStateOf<Map<Int, ConflictResolution>>(emptyMap()) }
    var showImportResultDialog by remember { mutableStateOf(false) }

    // Token显示相关
    var showTokenDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    val isTokenRevealed = activeAccount?.tokenRevealed ?: false

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            kotlinx.coroutines.delay(2500)
            toastMessage = null
        }
    }

    // 检测 MANAGE_EXTERNAL_STORAGE
    val hasAllFilesAccess = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Environment.isExternalStorageManager()
            else true
        )
    }
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasAllFilesAccess.value =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Environment.isExternalStorageManager()
            else true
    }

    // 检测通知权限（Android 13+）
    val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
    val hasNotifPerm = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                notificationManager.areNotificationsEnabled()
            else true
        )
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm.value = granted
    }
    // 从设置页返回时刷新状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasNotifPerm.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    notificationManager.areNotificationsEnabled() else true
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun requestNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android <13 直接跳设置
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"))
            storagePermLauncher.launch(intent)
        }
    }

    fun handleRootToggle(enabled: Boolean) {
        if (!enabled) { onRootToggle(false); return }
        scope.launch {
            rootChecking = true
            val granted = RootManager.requestRoot()
            rootChecking = false
            if (granted) {
                onRootToggle(true)
                rootCheckMsg = "✓ Root 权限已授权"
                LogManager.i("Settings", "Root 权限授权成功")
            } else {
                rootCheckMsg = "⚠ 未获得 Root 权限，请确认设备已 Root 并授权"
                LogManager.w("Settings", "Root 授权失败")
            }
        }
    }

    fun checkForUpdate() {
        scope.launch {
            checkingForUpdate = true
            LogManager.d("Settings", "开始检测更新...")
            val token = com.gitmob.android.GitMobApp.instance.tokenStorage.accessToken.first()
            val result = UpdateManager.checkForUpdate(token)
            checkingForUpdate = false
            result.onSuccess { release ->
                if (release != null) {
                    latestRelease = release
                    showUpdateDialog = true
                    LogManager.i("Settings", "检测到新版本: ${release.tagName}")
                } else {
                    LogManager.i("Settings", "当前已是最新版本")
                    rootCheckMsg = "✓ 当前已是最新版本 (v${BuildConfig.VERSION_NAME})"
                }
            }.onFailure { e ->
                LogManager.e("Settings", "检测更新失败", e)
                rootCheckMsg = "✗ 检测更新失败: ${e.message}"
            }
        }
    }

    fun downloadAndInstall(release: UpdateManager.Release) {
        val apkUrl = release.apkUrl ?: return
        LogManager.i("Settings", "开始下载更新: ${release.tagName}")
        GmDownloadManager.download(
            ctx = context,
            url = apkUrl,
            filename = "gitmob-${release.tagName}.apk"
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showUpdateDialog && latestRelease != null) {
        UpdateDialog(
            release = latestRelease!!,
            onDismiss = { showUpdateDialog = false },
            onIgnore = {
                UpdateManager.ignoreVersion(context, latestRelease!!.tagName)
                showUpdateDialog = false
            },
            onUpdate = {
                showUpdateDialog = false
                downloadAndInstall(latestRelease!!)
            }
        )
    }

    // 导出日志：用文件选择器选择保存目录
    if (showExportLogPicker) {
        LogManager.d("Settings", "FilePickerScreen 正在显示，showExportLogPicker=true")
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                LogManager.d("Settings", "Dialog onDismissRequest 被调用")
                showExportLogPicker = false
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            com.gitmob.android.ui.filepicker.FilePickerScreen(
                title       = "选择日志导出目录",
                mode        = com.gitmob.android.ui.filepicker.PickerMode.DIRECTORY,
                rootEnabled = rootEnabled,
                onConfirm   = { dir, _ ->
                    LogManager.d("Settings", "FilePickerScreen 确认选择目录: $dir")
                    showExportLogPicker = false
                    scope.launch {
                        LogManager.d("Settings", "开始调用 exportLogs 函数")
                        exportLogs(dir)
                    }
                },
                onDismiss = {
                    LogManager.d("Settings", "FilePickerScreen 被关闭")
                    showExportLogPicker = false
                },
            )
        }
    }

    // 导出收藏夹
    if (showExportFavPicker) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showExportFavPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            com.gitmob.android.ui.filepicker.FilePickerScreen(
                title       = "选择收藏夹导出目录",
                mode        = com.gitmob.android.ui.filepicker.PickerMode.DIRECTORY,
                rootEnabled = rootEnabled,
                onConfirm   = { dir, _ ->
                    showExportFavPicker = false
                    scope.launch {
                        exportFavorites(dir, favoritesManager.exportFavorites())
                    }
                },
                onDismiss = {
                    showExportFavPicker = false
                },
            )
        }
    }

    // 导入收藏夹
    if (showImportFavPicker) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showImportFavPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            com.gitmob.android.ui.filepicker.FilePickerScreen(
                title       = "选择收藏夹 JSON 文件",
                mode        = com.gitmob.android.ui.filepicker.PickerMode.SINGLE_FILE,
                allowedExtensions = listOf("json"),
                rootEnabled = rootEnabled,
                onConfirm   = { _, files ->
                    showImportFavPicker = false
                    scope.launch {
                        val file = files.firstOrNull()
                        if (file != null) {
                            val json = readImportFile(file)
                            if (json != null) {
                                importJsonString = json
                                val result = favoritesManager.analyzeImport(json)
                                importResult = result
                                if (result.success && result.conflicts.isNotEmpty()) {
                                    showImportConflictDialog = true
                                } else if (result.success) {
                                    val finalResult = favoritesManager.performImport(json)
                                    importResult = finalResult
                                    showImportResultDialog = true
                                } else {
                                    importResult = result
                                    showImportResultDialog = true
                                }
                            }
                        }
                    }
                },
                onDismiss = {
                    showImportFavPicker = false
                },
            )
        }
    }

    // 导入冲突处理弹窗
    if (showImportConflictDialog && importResult != null) {
        ConflictResolutionDialog(
            conflicts = importResult!!.conflicts,
            onConfirm = { resolutions ->
                importResolutions = resolutions
                showImportConflictDialog = false
                scope.launch {
                    val finalResult = favoritesManager.performImport(importJsonString, resolutions)
                    importResult = finalResult
                    showImportResultDialog = true
                }
            },
            onDismiss = {
                showImportConflictDialog = false
            },
            c = c
        )
    }

    // 导入结果弹窗
    if (showImportResultDialog && importResult != null) {
        ImportResultDialog(
            result = importResult!!,
            onDismiss = {
                showImportResultDialog = false
                importResult = null
            },
            c = c
        )
    }

    Scaffold(
        containerColor = c.bgDeep,
        snackbarHost = {
            toastMessage?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Snackbar(
                        modifier = Modifier.padding(top = 80.dp, start = 16.dp, end = 16.dp),
                    ) {
                        Text(it)
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── 账号 ──────────────────────────────────────────
            if (profile != null) {
                SLabel("账号", c)
                SCard(c) {
                    // ── 当前活跃账号（主卡片，点击展开/收起）──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { accountCardExpanded = !accountCardExpanded }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = profile!!.third.let {
                                if (it.contains("?")) it else "$it?s=80"
                            },
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(c.bgItem),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    profile!!.second,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp, color = c.textPrimary,
                                )
                                if (activeAccount?.authType == AuthType.TOKEN) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(
                                            "Token",
                                            fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                "@${profile!!.first}",
                                fontSize = 12.sp, color = c.textSecondary,
                            )
                        }
                        // 展开/收起箭头（有多账号时才显示）
                        Icon(
                            if (accountCardExpanded) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = c.textTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // ── 展开内容：其他账号 + 添加账号 ──
                    androidx.compose.animation.AnimatedVisibility(visible = accountCardExpanded) {
                        Column {
                            HorizontalDivider(color = c.border, thickness = 0.5.dp)

                            // 其他账号列表
                            val otherAccounts = allAccounts.filter { it.login != activeLogin }
                            if (otherAccounts.isNotEmpty()) {
                                otherAccounts.forEach { account ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch {
                                                    accountStore.switchAccount(account.login)
                                                    tokenStorage.syncActiveAccount(account)
                                                    accountCardExpanded = false
                                                    onSwitchAccount(account)
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        AsyncImage(
                                            model = account.avatarUrl.let {
                                                if (it.contains("?")) it else "$it?s=80"
                                            },
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(c.bgItem),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    account.displayName,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = c.textPrimary,
                                                )
                                                if (account.authType == AuthType.TOKEN) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                                        shape = RoundedCornerShape(4.dp),
                                                    ) {
                                                        Text(
                                                            "Token",
                                                            fontSize = 9.sp, fontWeight = FontWeight.Medium,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                "@${account.login}",
                                                fontSize = 11.sp, color = c.textSecondary,
                                            )
                                        }
                                        Text(
                                            "切换",
                                            fontSize = 12.sp, color = Coral,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                    HorizontalDivider(
                                        color = c.border, thickness = 0.5.dp,
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                    )
                                }
                            }

                            // 添加账号按钮
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        accountCardExpanded = false
                                        onAddAccount()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(CoralDim, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Add, null,
                                        tint = Coral, modifier = Modifier.size(20.dp),
                                    )
                                }
                                Text(
                                    "添加账号",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Coral,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Default.ChevronRight, null,
                                    tint = c.textTertiary, modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── 外观 ──────────────────────────────────────────
            SLabel("外观", c)
            SCard(c) {
                listOf(ThemeMode.LIGHT to "浅色模式", ThemeMode.DARK to "深色模式", ThemeMode.SYSTEM to "跟随系统")
                    .forEachIndexed { i, (mode, label) ->
                        SRow(
                            title = label,
                            trailing = {
                                if (currentTheme == mode)
                                    Icon(Icons.Default.Check, null, tint = Coral, modifier = Modifier.size(18.dp))
                            },
                            onClick = { onThemeChange(mode) }, c = c,
                        )
                        if (i < 2) SDivider(c)
                    }
            }

            // ── 高级 ──────────────────────────────────────────
            SLabel("高级", c)
            SCard(c) {

                // 检测更新
                SRow(
                    title = "检测更新",
                    subtitle = "当前版本 v${BuildConfig.VERSION_NAME}",
                    leadingIcon = {
                        SIconBox(CoralDim, Icons.Default.SystemUpdate, Coral)
                    },
                    trailing = {
                        if (checkingForUpdate) {
                            CircularProgressIndicator(color = Coral,
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("检查", fontSize = 12.sp, color = Coral, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    onClick = { checkForUpdate() },
                    c = c,
                )

                SDivider(c)

                // Root 开关
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SIconBox(
                        color = if (rootEnabled) Color(0x25FF6B00) else c.bgItem,
                        icon = Icons.Default.Security,
                        tint = if (rootEnabled) Color(0xFFFF6B00) else c.textTertiary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Root 模式", fontSize = 15.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            if (rootEnabled) "已启用 · 可访问 /data /system 等目录"
                            else "允许访问受保护目录",
                            fontSize = 12.sp, color = c.textSecondary,
                        )
                    }
                    if (rootChecking) {
                        CircularProgressIndicator(color = Color(0xFFFF6B00),
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = rootEnabled, onCheckedChange = ::handleRootToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFFF6B00),
                                uncheckedThumbColor = c.textTertiary,
                                uncheckedTrackColor = c.bgItem,
                            ),
                        )
                    }
                }
                rootCheckMsg?.let { msg ->
                    LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); rootCheckMsg = null }
                    Text(msg, fontSize = 12.sp,
                        color = if (msg.startsWith("✓")) Green else Yellow,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                }

                SDivider(c)

                // 仓库详情Tab逐级返回开关
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SIconBox(
                        color = if (tabStepBackEnabled) BlueDim else c.bgItem,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        tint = if (tabStepBackEnabled) BlueColor else c.textTertiary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("仓库详情Tab逐级返回", fontSize = 15.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            if (tabStepBackEnabled) "已启用 · 标签页按顺序逐级返回"
                            else "关闭 · 非文件Tab先返回文件Tab",
                            fontSize = 12.sp, color = c.textSecondary,
                        )
                    }
                    Switch(
                        checked = tabStepBackEnabled, onCheckedChange = onTabStepBackToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BlueColor,
                            uncheckedThumbColor = c.textTertiary,
                            uncheckedTrackColor = c.bgItem,
                        ),
                    )
                }

                SDivider(c)

                // 全部文件访问权限
                SRow(
                    title = "文件访问权限",
                    subtitle = if (hasAllFilesAccess.value) "已授权 · 可访问完整存储空间"
                               else "⚠ 未授权 · 部分目录可能无法读取",
                    subtitleColor = if (hasAllFilesAccess.value) Green else Yellow,
                    leadingIcon = {
                        SIconBox(
                            color = if (hasAllFilesAccess.value) GreenDim else YellowDim,
                            icon = Icons.Default.FolderOpen,
                            tint = if (hasAllFilesAccess.value) Green else Yellow,
                        )
                    },
                    trailing = {
                        if (!hasAllFilesAccess.value) {
                            Text("去授权", fontSize = 12.sp, color = Coral, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = { if (!hasAllFilesAccess.value) requestAllFilesAccess() },
                    c = c,
                )

                SDivider(c)

                // 通知权限（下载进度条通知）
                SRow(
                    title = "通知权限",
                    subtitle = if (hasNotifPerm.value) "已授权 · 下载时显示进度通知"
                               else "⚠ 未授权 · 无法显示下载进度通知",
                    subtitleColor = if (hasNotifPerm.value) Green else Yellow,
                    leadingIcon = {
                        SIconBox(
                            color = if (hasNotifPerm.value) GreenDim else YellowDim,
                            icon  = if (hasNotifPerm.value) Icons.Default.Notifications
                                    else Icons.Default.NotificationsOff,
                            tint  = if (hasNotifPerm.value) Green else Yellow,
                        )
                    },
                    trailing = {
                        if (!hasNotifPerm.value) {
                            Text("去授权", fontSize = 12.sp, color = Coral, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = { if (!hasNotifPerm.value) requestNotifPerm() },
                    c = c,
                )

                SDivider(c)

                // 安装未知应用权限
                val hasInstallPermission = remember { mutableStateOf(hasInstallPermission(context)) }
                val installPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    hasInstallPermission.value = hasInstallPermission(context)
                }

                SRow(
                    title = "安装未知应用权限",
                    subtitle = if (hasInstallPermission.value) "已授权 · 可自动安装更新"
                               else "⚠ 未授权 · 下载后需手动安装",
                    subtitleColor = if (hasInstallPermission.value) Green else Yellow,
                    leadingIcon = {
                        SIconBox(
                            color = if (hasInstallPermission.value) GreenDim else YellowDim,
                            icon = Icons.Default.GetApp,
                            tint = if (hasInstallPermission.value) Green else Yellow,
                        )
                    },
                    trailing = {
                        if (!hasInstallPermission.value) {
                            Text("去授权", fontSize = 12.sp, color = Coral, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = { 
                        if (!hasInstallPermission.value) {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            installPermLauncher.launch(intent)
                        }
                    },
                    c = c,
                )

            }

            // ── 数据 ──────────────────────────────────────────
            SLabel("数据", c)
            SCard(c) {
                // ── 语言数据 ──────────────────────────────────────────────
                val hasLangData = remember { mutableStateOf(LanguageManager.hasLanguageData(context)) }
                var langFetching by remember { mutableStateOf(false) }
                var langResult by remember { mutableStateOf<String?>(null) }
                langResult?.let { msg ->
                    LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); langResult = null }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SIconBox(
                        color = if (hasLangData.value) BlueDim else c.bgItem,
                        icon = Icons.Default.Code,
                        tint = if (hasLangData.value) BlueColor else c.textTertiary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (hasLangData.value) "语言数据" else "语言数据（未获取）",
                            fontSize = 15.sp, color = c.textPrimary, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (hasLangData.value) "已获取 · 语言筛选使用本地数据及颜色"
                            else "未获取 · 语言筛选仅显示已加载仓库的语言",
                            fontSize = 12.sp, color = c.textSecondary,
                        )
                        langResult?.let { msg ->
                            Text(msg, fontSize = 12.sp,
                                color = if (msg.startsWith("✓")) Green else Color(0xFFF85149),
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (langFetching) {
                        CircularProgressIndicator(color = BlueColor,
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            langFetching = true
                            scope.launch {
                                val res = LanguageManager.fetchAndSave(context)
                                langFetching = false
                                if (res.isSuccess) {
                                    hasLangData.value = true
                                    langResult = "✓ 已获取 ${res.getOrDefault(0)} 种语言"
                                } else {
                                    langResult = "✗ ${res.exceptionOrNull()?.message ?: "获取失败"}"
                                }
                            }
                        }) {
                            Text(
                                if (hasLangData.value) "更新" else "获取",
                                color = BlueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                SDivider(c)

                // ── Emoji 数据 ──────────────────────────────────────────────
                val hasEmojiData = remember { mutableStateOf(EmojiManager.hasEmojiData(context)) }
                var emojiFetching by remember { mutableStateOf(false) }
                var emojiResult by remember { mutableStateOf<String?>(null) }
                emojiResult?.let { msg ->
                    LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); emojiResult = null }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SIconBox(
                        color = if (hasEmojiData.value) PurpleDim else c.bgItem,
                        icon = Icons.Default.EmojiEmotions,
                        tint = if (hasEmojiData.value) PurpleColor else c.textTertiary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (hasEmojiData.value) "Emoji 数据" else "Emoji 数据（未获取）",
                            fontSize = 15.sp, color = c.textPrimary, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (hasEmojiData.value) "已获取 · 标签中的 emoji 标记将转换为字符"
                            else "未获取 · 标签中的 emoji 标记将原样显示",
                            fontSize = 12.sp, color = c.textSecondary,
                        )
                        emojiResult?.let { msg ->
                            Text(msg, fontSize = 12.sp,
                                color = if (msg.startsWith("✓")) Green else Color(0xFFF85149),
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (emojiFetching) {
                        CircularProgressIndicator(color = PurpleColor,
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            emojiFetching = true
                            scope.launch {
                                val res = EmojiManager.fetchAndSave(context)
                                emojiFetching = false
                                if (res.isSuccess) {
                                    hasEmojiData.value = true
                                    emojiResult = "✓ 已获取 ${res.getOrDefault(0)} 个 emoji"
                                } else {
                                    emojiResult = "✗ ${res.exceptionOrNull()?.message ?: "获取失败"}"
                                }
                            }
                        }) {
                            Text(
                                if (hasEmojiData.value) "更新" else "获取",
                                color = PurpleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // ── 收藏夹 ──────────────────────────────────────────
            SLabel("收藏夹", c)
            SCard(c) {
                SRow(
                    title = "导出收藏夹",
                    subtitle = "将当前用户的收藏夹数据导出为 JSON 文件",
                    leadingIcon = {
                        SIconBox(GreenDim, Icons.Default.FileDownload, Green)
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
                            tint = c.textTertiary, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        showExportFavPicker = true
                    },
                    c = c,
                )
                SDivider(c)
                SRow(
                    title = "导入收藏夹",
                    subtitle = "从 JSON 文件导入收藏夹数据到当前用户",
                    leadingIcon = {
                        SIconBox(BlueDim, Icons.Default.FileUpload, BlueColor)
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
                            tint = c.textTertiary, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        showImportFavPicker = true
                    },
                    c = c,
                )
            }

            // ── 日志 ──────────────────────────────────────────
            SLabel("调试", c)
            SCard(c) {
                // 日志等级选择（内联显示，不用弹窗）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SIconBox(c.bgItem, Icons.Default.BugReport, BlueColor)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("日志等级", fontSize = 15.sp, color = c.textPrimary)
                            Text(
                                "当前：${LogLevel.entries.getOrElse(logLevelIdx) { LogLevel.DEBUG }.name}",
                                fontSize = 12.sp, color = c.textSecondary,
                            )
                        }
                    }
                    val levels = LogLevel.entries.filter { it != LogLevel.NONE }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        levels.forEachIndexed { idx, level ->
                            val selected = logLevelIdx == idx
                            val (bg, fg) = when (level) {
                                LogLevel.VERBOSE -> Color(0x1FA78BFA) to PurpleColor
                                LogLevel.DEBUG   -> BlueDim to BlueColor
                                LogLevel.INFO    -> GreenDim to Green
                                LogLevel.WARN    -> YellowDim to Yellow
                                LogLevel.ERROR   -> RedDim to RedColor
                                else             -> c.bgItem to c.textTertiary
                            }
                            FilterChip(
                                selected = selected,
                                onClick  = {
                                    scope.launch { tokenStorage.setLogLevel(idx) }
                                    LogManager.setLevel(level)
                                },
                                label    = { Text(level.name, fontSize = 10.sp) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = bg,
                                    selectedLabelColor     = fg,
                                ),
                                modifier = Modifier.height(28.dp),
                            )
                        }
                    }
                }

                SDivider(c)

                // 导出日志
                SRow(
                    title = "导出日志",
                    subtitle = "选择目录保存日志文件",
                    leadingIcon = {
                        SIconBox(GreenDim, Icons.Default.FileUpload, Green)
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
                            tint = c.textTertiary, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        LogManager.d("Settings", "导出日志按钮被点击，showExportLogPicker=$showExportLogPicker")
                        showExportLogPicker = true
                        LogManager.d("Settings", "showExportLogPicker 已设置为 true")
                    },
                    c = c,
                )

                SDivider(c)

                // 清空日志
                SRow(
                    title = "清空日志",
                    subtitle = "删除今日日志文件和内存记录",
                    titleColor = RedColor,
                    leadingIcon = {
                        SIconBox(RedDim, Icons.Default.Delete, RedColor)
                    },
                    onClick = {
                        LogManager.clearToday()
                    },
                    c = c,
                )
            }

            // ── 账号操作 ──────────────────────────────────────
            SLabel("账号操作", c)
            SCard(c) {
                // 查看Token按钮（仅在未显示过时显示）
                if (!isTokenRevealed && activeLogin != null) {
                    SRow(
                        title = "查看Token",
                        subtitle = "查看并复制Token",
                        leadingIcon = {
                            SIconBox(
                                color = CoralDim,
                                icon = Icons.Default.Key,
                                tint = Coral
                            )
                        },
                        trailing = {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
                                tint = c.textTertiary, modifier = Modifier.size(16.dp))
                        },
                        onClick = { showTokenDialog = true },
                        c = c
                    )
                    SDivider(c)
                }
                if (activeAccount?.authType == AuthType.TOKEN) {
                    // Token 账号：只显示移除账号
                    SRow(
                        title      = "移除账号",
                        subtitle   = "从本地移除该 Token 账号",
                        titleColor = RedColor,
                        leadingIcon = {
                            SIconBox(
                                color = androidx.compose.ui.graphics.Color(0x22F87171),
                                icon  = Icons.AutoMirrored.Filled.Logout,
                                tint  = RedColor,
                            )
                        },
                        onClick = { showLogoutDialog = true }, c = c,
                    )
                } else {
                    // OAuth 账号：显示退出登录和取消授权
                    SRow(
                        title      = "退出登录",
                        subtitle   = "撤销当前 Token，授权记录保留",
                        titleColor = RedColor,
                        leadingIcon = {
                            SIconBox(
                                color = androidx.compose.ui.graphics.Color(0x22F87171),
                                icon  = Icons.AutoMirrored.Filled.Logout,
                                tint  = RedColor,
                            )
                        },
                        onClick = { showLogoutDialog = true }, c = c,
                    )
                    HorizontalDivider(
                        color     = c.border,
                        thickness = 0.5.dp,
                        modifier  = Modifier.padding(horizontal = 12.dp),
                    )
                    SRow(
                        title      = "取消所有授权",
                        subtitle   = "彻底移除 GitMob 的 GitHub 授权",
                        titleColor = RedColor,
                        leadingIcon = {
                            SIconBox(
                                color = androidx.compose.ui.graphics.Color(0x22F87171),
                                icon  = Icons.Default.NoAccounts,
                                tint  = RedColor,
                            )
                        },
                        onClick = { showRevokeDialog = true }, c = c,
                    )
                }
            }

            // ── 退出登录确认弹窗 ──
            if (showLogoutDialog) {
                val isTokenAccount = activeAccount?.authType == AuthType.TOKEN
                AlertDialog(
                    onDismissRequest = { showLogoutDialog = false },
                    containerColor   = c.bgCard,
                    icon  = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = RedColor) },
                    title = { Text(if (isTokenAccount) "移除账号" else "退出登录", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
                    text  = {
                        Text(
                            if (isTokenAccount) "将从本地移除该 Token 账号。" 
                            else "将撤销当前 Token 并退出登录。\n\nGitHub 授权记录保留，下次可快速重新登录。",
                            fontSize = 14.sp, color = c.textSecondary, lineHeight = 22.sp,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showLogoutDialog = false
                                scope.launch {
                                    val token = tokenStorage.accessToken.first()
                                    // 只有 OAuth 账号才撤销服务端 token
                                    if (!isTokenAccount && !token.isNullOrBlank()) {
                                        com.gitmob.android.auth.OAuthManager.revokeToken(token)
                                    }
                                    val login = activeLogin
                                    if (login != null) {
                                        // 有已知登录账号：从账号列表移除，按剩余账号决策
                                        val remaining = accountStore.removeAccount(login)
                                        if (remaining.isNotEmpty()) {
                                            val next = remaining.first()
                                            tokenStorage.syncActiveAccount(next)
                                            com.gitmob.android.api.ApiClient.rebuild()
                                            onSwitchAccount(next)
                                        } else {
                                            tokenStorage.clearActiveAccount()
                                            onLogout(false)
                                        }
                                    } else {
                                        // activeLogin 为 null（首次登录未保存用户信息）
                                        // 仍需清除本地 token 并跳转登录页
                                        tokenStorage.clearActiveAccount()
                                        onLogout(false)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RedColor),
                        ) { Text(if (isTokenAccount) "移除" else "退出登录") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutDialog = false }) {
                            Text("取消", color = c.textSecondary)
                        }
                    },
                )
            }

            // ── 取消所有授权确认弹窗 ──
            if (showRevokeDialog && activeAccount?.authType != AuthType.TOKEN) {
                AlertDialog(
                    onDismissRequest = { showRevokeDialog = false },
                    containerColor   = c.bgCard,
                    icon  = { Icon(Icons.Default.NoAccounts, null, tint = RedColor) },
                    title = { Text("取消授权", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
                    text  = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "这将彻底删除 GitMob 在你的 GitHub 账号中的授权记录，包括所有关联 Token。",
                                fontSize = 14.sp, color = c.textSecondary, lineHeight = 22.sp,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        androidx.compose.ui.graphics.Color(0x22F87171),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Warning, null,
                                    tint = RedColor, modifier = Modifier.size(16.dp))
                                Text("操作后需重新完整授权才能登录",
                                    fontSize = 12.sp, color = RedColor)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showRevokeDialog = false
                                scope.launch {
                                    val token = tokenStorage.accessToken.first()
                                    // 彻底删除 Grant（结果不影响本地清除流程）
                                    if (!token.isNullOrBlank()) {
                                        com.gitmob.android.auth.OAuthManager.deleteGrant(token)
                                    }
                                    val login = activeLogin
                                    if (login != null) {
                                        // 从账号列表移除，按剩余账号决策
                                        val remaining = accountStore.removeAccount(login)
                                        if (remaining.isNotEmpty()) {
                                            val next = remaining.first()
                                            tokenStorage.syncActiveAccount(next)
                                            com.gitmob.android.api.ApiClient.rebuild()
                                            onSwitchAccount(next)
                                        } else {
                                            tokenStorage.clearActiveAccount()
                                            onLogout(true)
                                        }
                                    } else {
                                        // activeLogin 为 null（登录信息不完整）
                                        // 仍需清除本地 token 并跳转登录页
                                        tokenStorage.clearActiveAccount()
                                        onLogout(true)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RedColor),
                        ) { Text("确认取消授权") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRevokeDialog = false }) {
                            Text("取消", color = c.textSecondary)
                        }
                    },
                )
            }

            // ── Token显示弹窗 ───────────────────────────────────
            if (showTokenDialog && activeLogin != null) {
                val token by tokenStorage.accessToken.collectAsState(initial = null)
                if (token != null) {
                    TokenRevealDialog(
                        token = token!!,
                        onConfirm = {
                            scope.launch {
                                accountStore.setTokenRevealed(activeLogin!!, true)
                            }
                            showTokenDialog = false
                        },
                        onDismiss = { showTokenDialog = false },
                        onTokenCopied = {
                            toastMessage = "已复制到剪贴板"
                        },
                    )
                }
            }

            // ── 关于 ──────────────────────────────────────────
            SLabel("关于", c)
            SCard(c) {
                SRow(
                    title = "关于 GitMob",
                    leadingIcon = { SIconBox(CoralDim, Icons.Default.Info, Coral) },
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
                            tint = c.textTertiary, modifier = Modifier.size(16.dp))
                    },
                    onClick = { showAbout = true }, c = c,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("GitMob v${BuildConfig.VERSION_NAME} · Apache 2.0",
                fontSize = 11.sp, color = c.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        }
    }
}

// ─── Token显示弹窗 ─────────────────────────────────────────────────────────────
@Composable
fun TokenRevealDialog(
    token: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onTokenCopied: () -> Unit,
) {
    val c = LocalGmColors.current
    val context = LocalContext.current
    var isHighlighted by remember { mutableStateOf(false) }

    fun copyToClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("github_token", token)
        clipboard.setPrimaryClip(clip)
        isHighlighted = true
        onTokenCopied()
        kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.delay(200)
            isHighlighted = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = { Icon(Icons.Default.Key, null, tint = Coral) },
        title = {
            Text("查看Token", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x22F87171), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = RedColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            "此Token具有完整GitHub账号权限",
                            fontSize = 13.sp,
                            color = RedColor,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "请勿截图或分享，关闭后不再显示",
                            fontSize = 12.sp,
                            color = c.textSecondary
                        )
                    }
                }

                // Token显示区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isHighlighted) Coral.copy(alpha = 0.15f) else c.bgItem,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(onClick = { copyToClipboard() })
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        null,
                        tint = c.textTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        token,
                        fontSize = 13.sp,
                        color = c.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    "复制后粘贴到插件设置页面中",
                    fontSize = 12.sp,
                    color = c.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) {
                Text("我已复制，不再显示")
            }
        }
    )
}

// ─── 关于弹窗 ─────────────────────────────────────────────────────────────────
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val c = LocalGmColors.current
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(c.bgCard, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // App 图标（使用项目真实 PNG logo）
            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = "GitMob Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Text("GitMob", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.textPrimary)
            // 版本号从 BuildConfig 动态读取
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = c.textTertiary)

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            // 开发者
            AboutSection(title = "开发者", c = c) {
                DeveloperRow(
                    login = "xiaobaiweinuli",
                    displayName = "xiaobaiweinuli",
                    avatarUrl = "https://avatars.githubusercontent.com/u/94781176?v=4&s=80",
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/xiaobaiweinuli")))
                    }, c = c,
                )
            }

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            // 项目
            AboutSection(title = "项目", c = c) {
                AboutLinkRow(
                    icon = Icons.Default.Code,
                    label = "GitHub 仓库",
                    sub = "xiaobaiweinuli/GitMob-Android · Apache 2.0",
                    url = "https://github.com/xiaobaiweinuli/GitMob-Android",
                    context = context, c = c,
                )
            }

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            // 权限
            AboutSection(title = "权限", c = c) {
                AboutLinkRow(
                    icon = Icons.Default.Security,
                    label = "GitHub 授权管理",
                    sub = "查看、撤销 GitMob 对你账号的授权",
                    url = "https://github.com/settings/connections/applications/Ov23liP9mC2HXALHsFpk",
                    context = context, c = c,
                )
            }

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            // 社区
            AboutSection(title = "社区", c = c) {
                AboutLinkRow(
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = "Telegram 群组",
                    sub = "t.me/MyResNav",
                    url = "https://t.me/MyResNav",
                    context = context, c = c,
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("关闭", color = Coral)
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, c: GmColors, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), fontSize = 10.sp, color = c.textTertiary,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        content()
    }
}

@Composable
private fun DeveloperRow(
    login: String, displayName: String, avatarUrl: String,
    onClick: () -> Unit, c: GmColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgItem, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = avatarUrl, contentDescription = null,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(c.bgCard),
        )
        Column(Modifier.weight(1f)) {
            Text(displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.textPrimary)
            Text("@$login", fontSize = 12.sp, color = c.textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
            tint = c.textTertiary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun AboutLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, sub: String, url: String,
    context: android.content.Context, c: GmColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(c.bgItem, RoundedCornerShape(12.dp))
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.textPrimary)
            Text(sub, fontSize = 11.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null,
            tint = c.textTertiary, modifier = Modifier.size(14.dp))
    }
}

// ─── 日志导出工具 ──────────────────────────────────────────────────────────────

/**
 * 将当日日志文件复制到用户选择的目录。
 * 若内存队列有日志但文件不存在，先把内存日志落盘再复制。
 */
private suspend fun exportLogs(destDir: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    LogManager.d("Settings", "exportLogs 开始执行，目标目录: $destDir")
    try {
        LogManager.d("Settings", "正在调用 LogManager.currentLogFile()")
        val logFile = LogManager.currentLogFile()
        LogManager.d("Settings", "LogManager.currentLogFile() 返回: $logFile")
        
        if (logFile == null) {
            LogManager.e("Settings", "logFile 为 null，无法导出日志")
            return@withContext
        }
        
        LogManager.d("Settings", "日志文件路径: ${logFile.absolutePath}")
        LogManager.d("Settings", "日志文件是否存在: ${logFile.exists()}")
        
        // 若文件尚不存在，把内存队列强制写盘
        if (!logFile.exists()) {
            LogManager.d("Settings", "日志文件不存在，尝试从内存队列落盘")
            val entries = LogManager.recent(5000)
            LogManager.d("Settings", "从内存队列获取到 ${entries.size} 条日志")
            
            if (entries.isEmpty()) {
                LogManager.w("Settings", "内存队列为空，没有日志可导出")
                return@withContext
            }
            
            LogManager.d("Settings", "创建日志文件目录: ${logFile.parentFile?.absolutePath}")
            logFile.parentFile?.mkdirs()
            
            LogManager.d("Settings", "开始写入日志文件")
            logFile.bufferedWriter().use { w ->
                entries.reversed().forEach { e ->
                    w.write("${e.time} [${e.level.tag}] ${e.tag}: ${e.msg}")
                    w.newLine()
                }
            }
            LogManager.d("Settings", "日志文件写入完成")
        }
        
        val dest = java.io.File(destDir, logFile.name)
        LogManager.d("Settings", "目标文件路径: ${dest.absolutePath}")
        
        LogManager.d("Settings", "开始复制文件...")
        logFile.copyTo(dest, overwrite = true)
        LogManager.i("Settings", "日志已导出到 ${dest.absolutePath}")
        
    } catch (e: Exception) {
        LogManager.e("Settings", "日志导出失败", e)
    }
    LogManager.d("Settings", "exportLogs 执行结束")
}

// ─── 通用子组件 ────────────────────────────────────────────────────────────────

@Composable
fun SLabel(text: String, c: GmColors) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = c.textTertiary, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
fun SCard(c: GmColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
fun SDivider(c: GmColors) {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp),
        color = c.border, thickness = 0.5.dp)
}

@Composable
fun SRow(
    title: String,
    titleColor: Color? = null,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    c: GmColors,
) {
    val localC = LocalGmColors.current
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leadingIcon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor ?: localC.textPrimary)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp,
                color = subtitleColor ?: localC.textSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
fun SIconBox(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier.size(36.dp).background(color, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * 检查是否有安装未知应用权限
 */
private fun hasInstallPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.packageManager.canRequestPackageInstalls()
    } else {
        true
    }
}

// ─── 收藏夹导出导入工具函数 ──────────────────────────────────────────────────────

/**
 * 导出收藏夹数据到文件
 */
private suspend fun exportFavorites(destDir: String, jsonString: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val dest = java.io.File(destDir, "gitmob_favorites_$timestamp.json")
        dest.writeText(jsonString)
        LogManager.i("Settings", "收藏夹已导出到 ${dest.absolutePath}")
    } catch (e: Exception) {
        LogManager.e("Settings", "收藏夹导出失败", e)
    }
}

/**
 * 读取导入文件内容
 */
private suspend fun readImportFile(filePath: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val file = java.io.File(filePath)
        if (file.exists() && file.extension.lowercase() == "json") {
            file.readText()
        } else {
            LogManager.e("Settings", "无效的文件格式，请选择 JSON 文件")
            null
        }
    } catch (e: Exception) {
        LogManager.e("Settings", "读取文件失败", e)
        null
    }
}

// ─── 冲突处理弹窗 ──────────────────────────────────────────────────────────────

@Composable
fun ConflictResolutionDialog(
    conflicts: List<ImportConflict>,
    onConfirm: (Map<Int, ConflictResolution>) -> Unit,
    onDismiss: () -> Unit,
    c: GmColors
) {
    val localResolutions = remember { mutableStateMapOf<Int, ConflictResolution>() }
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(c.bgCard, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SIconBox(YellowDim, Icons.Default.Warning, Yellow)
                Column {
                    Text("发现冲突", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
                    Text("共 ${conflicts.size} 个冲突需要处理", fontSize = 13.sp, color = c.textSecondary)
                }
            }

            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .weight(1f, false),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                conflicts.forEachIndexed { idx, conflict ->
                    ConflictItem(
                        conflict = conflict,
                        currentResolution = localResolutions[idx] ?: ConflictResolution.SKIP,
                        onResolutionChange = { localResolutions[idx] = it },
                        c = c
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消", color = c.textSecondary)
                }
                Button(
                    onClick = { onConfirm(localResolutions.toMap()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                ) {
                    Text("确认导入")
                }
            }
        }
    }
}

@Composable
fun ConflictItem(
    conflict: ImportConflict,
    currentResolution: ConflictResolution,
    onResolutionChange: (ConflictResolution) -> Unit,
    c: GmColors
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgItem, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (conflict.type == ImportConflictType.GROUP_NAME) Icons.Default.Folder else Icons.Default.Star,
                null,
                tint = Yellow,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    when (conflict.type) {
                        ImportConflictType.GROUP_NAME -> "分组名称冲突"
                        ImportConflictType.REPO_EXISTS -> "仓库已存在"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.textPrimary
                )
                Text(
                    conflict.groupName ?: conflict.repoFullName ?: "",
                    fontSize = 12.sp,
                    color = c.textSecondary
                )
            }
        }

        val options = when (conflict.type) {
            ImportConflictType.GROUP_NAME -> listOf(
                ConflictResolution.SKIP to "跳过",
                ConflictResolution.OVERWRITE to "覆盖",
                ConflictResolution.MERGE to "合并",
                ConflictResolution.RENAME to "重命名"
            )
            ImportConflictType.REPO_EXISTS -> listOf(
                ConflictResolution.SKIP to "跳过",
                ConflictResolution.OVERWRITE to "覆盖"
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEach { (resolution, label) ->
                FilterChip(
                    selected = currentResolution == resolution,
                    onClick = { onResolutionChange(resolution) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (currentResolution == resolution) CoralDim else c.bgItem,
                        selectedLabelColor = if (currentResolution == resolution) Coral else c.textSecondary,
                    ),
                    modifier = Modifier.height(32.dp)
                )
            }
        }
    }
}

// ─── 导入结果弹窗 ──────────────────────────────────────────────────────────────

@Composable
fun ImportResultDialog(
    result: ImportResult,
    onDismiss: () -> Unit,
    c: GmColors
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(c.bgCard, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (result.success) {
                SIconBox(GreenDim, Icons.Default.CheckCircle, Green)
                Text("导入成功", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("新增分组: ${result.importedGroups}", fontSize = 14.sp, color = c.textSecondary)
                    Text("新增仓库: ${result.importedRepos}", fontSize = 14.sp, color = c.textSecondary)
                    if (result.conflicts.isNotEmpty()) {
                        Text("处理冲突: ${result.conflicts.size}", fontSize = 14.sp, color = c.textSecondary)
                    }
                }
            } else {
                SIconBox(RedDim, Icons.Default.Error, RedColor)
                Text("导入失败", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary)
                Text(result.errorMessage ?: "未知错误", fontSize = 14.sp, color = c.textSecondary)
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) {
                Text("确定")
            }
        }
    }
}
