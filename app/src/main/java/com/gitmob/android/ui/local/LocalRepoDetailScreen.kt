package com.gitmob.android.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.local.GitRunner
import com.gitmob.android.local.LocalRepo
import com.gitmob.android.local.LocalRepoStorage
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "LocalRepoDetailScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalRepoDetailScreen(
    repoId: String,
    onBack: () -> Unit,
) {
    val c = LocalGmColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val storage = remember { LocalRepoStorage(context) }
    val tokenStorage = remember { TokenStorage(context) }
    
    var repo by remember { mutableStateOf<LocalRepo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf("") }
    var userProfile by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    
    var changedFiles by remember { mutableStateOf<List<GitRunner.ChangedFile>>(emptyList()) }
    var commitHistory by remember { mutableStateOf<List<GitRunner.CommitInfo>>(emptyList()) }
    var branches by remember { mutableStateOf<List<GitRunner.BranchInfo>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("") }
    var isRepoClean by remember { mutableStateOf(true) }
    var repoStatusStats by remember { mutableStateOf(GitRunner.RepoStatusStats(0, 0, 0, 0)) }
    
    var selectedCommitChangedFiles by remember { mutableStateOf<List<GitRunner.ChangedFile>>(emptyList()) }
    
    var commitMessage by remember { mutableStateOf("") }
    var isCommitting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isPushing by remember { mutableStateOf(false) }
    var isPulling by remember { mutableStateOf(false) }
    
    var showBranchManager by remember { mutableStateOf(false) }
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var showDeleteBranchDialog by remember { mutableStateOf<String?>(null) }
    var showRenameBranchDialog by remember { mutableStateOf<String?>(null) }
    
    var selectedDiffFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedCommit by remember { mutableStateOf<GitRunner.CommitInfo?>(null) }
    var showCommitDetail by remember { mutableStateOf(false) }
    
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectAll by remember { mutableStateOf(false) }
    
    var toastMessage by remember { mutableStateOf<String?>(null) }
    
    var showConflictDialog by remember { mutableStateOf(false) }
    var conflictCheckResult by remember { mutableStateOf<GitRunner.ConflictCheckResult?>(null) }
    var conflictActionType by remember { mutableStateOf<String?>(null) }
    var conflictForceFlag by remember { mutableStateOf(false) }
    
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            kotlinx.coroutines.delay(2500)
            toastMessage = null
        }
    }
    
    LaunchedEffect(Unit) {
        tokenStorage.accessToken.collect { t ->
            token = t ?: ""
        }
    }
    
    LaunchedEffect(Unit) {
        tokenStorage.userProfile.collect { profile ->
            userProfile = profile
        }
    }
    
    suspend fun refreshData(r: LocalRepo) = withContext(Dispatchers.IO) {
        isRefreshing = true
        try {
            changedFiles = GitRunner.getChangedFiles(r.path)
            commitHistory = GitRunner.getCommitHistory(r.path)
            branches = GitRunner.getBranches(r.path)
            currentBranch = GitRunner.currentBranch(r.path) ?: ""
            isRepoClean = changedFiles.isEmpty()
            repoStatusStats = GitRunner.getRepoStatusStats(r.path)
        } catch (e: Exception) {
            LogManager.e(TAG, "刷新数据失败", e)
        }
        isRefreshing = false
    }

    suspend fun loadCommitChangedFiles(sha: String) = withContext(Dispatchers.IO) {
        try {
            selectedCommitChangedFiles = GitRunner.getCommitChangedFiles(repo!!.path, sha)
        } catch (e: Exception) {
            LogManager.e(TAG, "加载提交变更文件失败", e)
            selectedCommitChangedFiles = emptyList()
        }
    }

    suspend fun showCommitFileDiff(sha: String, filePath: String) = withContext(Dispatchers.IO) {
        try {
            val diff = GitRunner.getCommitFileDiff(repo!!.path, sha, filePath)
            selectedDiffFile = filePath to diff
        } catch (e: Exception) {
            LogManager.e(TAG, "获取提交文件diff失败", e)
        }
    }
    
    LaunchedEffect(repoId) {
        loading = true
        try {
            storage.repos.collect { repos ->
                repo = repos.find { it.id == repoId }
                repo?.let {
                    refreshData(it)
                }
                loading = false
                error = null
            }
        } catch (e: Exception) {
            error = e.message
            loading = false
        }
    }
    
    suspend fun stageFile(filePath: String) = withContext(Dispatchers.IO) {
        try {
            GitRunner.addFile(repo!!.path, filePath)
            refreshData(repo!!)
        } catch (e: Exception) {
            LogManager.e(TAG, "暂存文件失败", e)
        }
    }
    
    suspend fun unstageFile(filePath: String) = withContext(Dispatchers.IO) {
        try {
            GitRunner.unstageFile(repo!!.path, filePath)
            refreshData(repo!!)
        } catch (e: Exception) {
            LogManager.e(TAG, "取消暂存失败", e)
        }
    }
    
    suspend fun stageAllFiles() = withContext(Dispatchers.IO) {
        try {
            GitRunner.addAll(repo!!.path)
            refreshData(repo!!)
            selectedFiles = emptySet()
            selectAll = false
        } catch (e: Exception) {
            LogManager.e(TAG, "全部暂存失败", e)
        }
    }
    
    suspend fun performCommit() = withContext(Dispatchers.IO) {
        if (commitMessage.isBlank() || repo == null || userProfile == null) return@withContext
        isCommitting = true
        try {
            val (authorName, authorEmail) = userProfile!!
            
            val result = GitRunner.commit(repo!!.path, commitMessage, authorName, authorEmail)
            if (result.success) {
                commitMessage = ""
                selectedFiles = emptySet()
                selectAll = false
                refreshData(repo!!)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "提交失败", e)
        }
        isCommitting = false
    }
    
    suspend fun showDiff(filePath: String) = withContext(Dispatchers.IO) {
        try {
            val diff = GitRunner.getDiff(repo!!.path, filePath)
            selectedDiffFile = filePath to diff
        } catch (e: Exception) {
            LogManager.e(TAG, "获取diff失败", e)
        }
    }
    
    suspend fun checkoutBranch(branchName: String) = withContext(Dispatchers.IO) {
        try {
            GitRunner.checkoutBranch(repo!!.path, branchName)
            refreshData(repo!!)
            showBranchManager = false
        } catch (e: Exception) {
            LogManager.e(TAG, "切换分支失败", e)
        }
    }
    
    suspend fun createBranch(branchName: String) = withContext(Dispatchers.IO) {
        try {
            GitRunner.createBranch(repo!!.path, branchName)
            refreshData(repo!!)
            showNewBranchDialog = false
        } catch (e: Exception) {
            LogManager.e(TAG, "创建分支失败", e)
        }
    }
    
    suspend fun deleteBranch(branchName: String) = withContext(Dispatchers.IO) {
        try {
            GitRunner.deleteBranch(repo!!.path, branchName)
            refreshData(repo!!)
            showDeleteBranchDialog = null
        } catch (e: Exception) {
            LogManager.e(TAG, "删除分支失败", e)
        }
    }
    
    suspend fun renameBranch(oldName: String, newName: String) = withContext(Dispatchers.IO) {
        try {
            GitRunner.renameBranch(repo!!.path, oldName, newName)
            refreshData(repo!!)
            showRenameBranchDialog = null
        } catch (e: Exception) {
            LogManager.e(TAG, "重命名分支失败", e)
        }
    }
    
    suspend fun performPush(force: Boolean = false, skipCheck: Boolean = false) = withContext(Dispatchers.IO) {
        if (repo == null || repo!!.remoteUrl == null) return@withContext
        
        isPushing = true
        
        if (!skipCheck && !force) {
            val checkResult = GitRunner.checkForConflicts(
                repo!!.path, repo!!.remoteUrl!!, currentBranch, token
            )
            conflictCheckResult = checkResult
            conflictActionType = "push"
            conflictForceFlag = force
            
            if (checkResult.hasLocalChanges || checkResult.isConflicting || checkResult.hasRemoteChanges) {
                showConflictDialog = true
                isPushing = false
                return@withContext
            }
        }
        
        try {
            val result = if (force) {
                GitRunner.forcePush(repo!!.path, repo!!.remoteUrl!!, currentBranch, token)
            } else {
                GitRunner.push(repo!!.path, repo!!.remoteUrl!!, currentBranch, token)
            }
            if (result.success) {
                LogManager.i(TAG, "推送成功")
                toastMessage = "推送成功"
            } else {
                LogManager.e(TAG, "推送失败: ${result.error}")
                toastMessage = "推送失败: ${result.error}"
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "推送失败", e)
            toastMessage = "推送失败: ${e.message}"
        }
        isPushing = false
    }
    
    suspend fun performPull(force: Boolean = false, skipCheck: Boolean = false) = withContext(Dispatchers.IO) {
        if (repo == null) return@withContext
        
        isPulling = true
        
        if (!skipCheck && !force && repo!!.remoteUrl != null) {
            val checkResult = GitRunner.checkForConflicts(
                repo!!.path, repo!!.remoteUrl!!, currentBranch, token
            )
            conflictCheckResult = checkResult
            conflictActionType = "pull"
            conflictForceFlag = force
            
            if (checkResult.hasLocalChanges || checkResult.isConflicting || checkResult.hasRemoteChanges) {
                showConflictDialog = true
                isPulling = false
                return@withContext
            }
        }
        
        try {
            val result = if (force) {
                GitRunner.forcePull(repo!!.path, token, currentBranch)
            } else {
                GitRunner.pull(repo!!.path, token, currentBranch)
            }
            if (result.success) {
                LogManager.i(TAG, "拉取成功")
                toastMessage = "拉取成功"
                refreshData(repo!!)
            } else {
                LogManager.e(TAG, "拉取失败: ${result.error}")
                toastMessage = "拉取失败: ${result.error}"
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "拉取失败", e)
            toastMessage = "拉取失败: ${e.message}"
        }
        isPulling = false
    }
    
    suspend fun resetCommit(sha: String, mode: String) = withContext(Dispatchers.IO) {
        if (repo == null) return@withContext
        try {
            val result = GitRunner.reset(repo!!.path, sha, mode)
            if (result.success) {
                refreshData(repo!!)
                showCommitDetail = false
                selectedCommit = null
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "重置提交失败", e)
        }
    }
    
    LaunchedEffect(changedFiles) {
        if (selectAll) {
            selectedFiles = changedFiles.map { it.path }.toSet()
        }
    }
    
    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = { Text(repo?.name ?: "本地仓库", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        scope.launch {
                            repo?.let { 
                                refreshData(it)
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, null, tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
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
    ) { padding ->
        when {
            loading -> LoadingBox(Modifier.padding(padding))
            error != null -> ErrorBox(error!!) {}
            repo == null -> ErrorBox("仓库不存在") {}
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        RepoStatusCard(
                            repo = repo!!,
                            c = c,
                            currentBranch = currentBranch,
                            repoStatusStats = repoStatusStats,
                            onShowBranchManager = { showBranchManager = true },
                        )
                    }
                    
                    item {
                        ChangedFilesCard(
                            c = c,
                            changedFiles = changedFiles,
                            selectedFiles = selectedFiles,
                            selectAll = selectAll,
                            onToggleSelectAll = { 
                                selectAll = it
                                selectedFiles = if (it) changedFiles.map { f -> f.path }.toSet() else emptySet()
                            },
                            onToggleFileSelect = { path ->
                                selectedFiles = if (selectedFiles.contains(path)) {
                                    selectedFiles - path
                                } else {
                                    selectedFiles + path
                                }
                                selectAll = selectedFiles.size == changedFiles.size
                            },
                            onStageAll = { scope.launch { stageAllFiles() } },
                            onStageFile = { scope.launch { stageFile(it) } },
                            onUnstageFile = { scope.launch { unstageFile(it) } },
                            onShowDiff = { scope.launch { showDiff(it) } },
                        )
                    }
                    
                    if (changedFiles.isNotEmpty()) {
                        item {
                            CommitCard(
                                c = c,
                                commitMessage = commitMessage,
                                onCommitMessageChange = { commitMessage = it },
                                isCommitting = isCommitting,
                                onCommit = { scope.launch { performCommit() } },
                            )
                        }
                    }
                    
                    if (repo?.remoteUrl != null) {
                        item {
                            PushPullCard(
                                c = c,
                                isPushing = isPushing,
                                isPulling = isPulling,
                                onPush = { scope.launch { performPush(false) } },
                                onForcePush = { scope.launch { performPush(true) } },
                                onPull = { scope.launch { performPull(false) } },
                                onForcePull = { scope.launch { performPull(true) } },
                            )
                        }
                    }
                    
                    item {
                        CommitHistoryCard(
                            c = c,
                            commitHistory = commitHistory,
                            onCommitClick = { commit ->
                                scope.launch {
                                    loadCommitChangedFiles(commit.sha)
                                    selectedCommit = commit
                                    showCommitDetail = true
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    
    if (showBranchManager && repo != null) {
        BranchManagerSheet(
            repo = repo!!,
            c = c,
            branches = branches,
            currentBranch = currentBranch,
            onCheckout = { scope.launch { checkoutBranch(it) } },
            onCreate = { showNewBranchDialog = true },
            onDelete = { showDeleteBranchDialog = it },
            onRename = { showRenameBranchDialog = it },
            onDismiss = { showBranchManager = false },
        )
    }
    
    if (showNewBranchDialog) {
        NewBranchDialog(
            c = c,
            onConfirm = { scope.launch { createBranch(it) } },
            onDismiss = { showNewBranchDialog = false },
        )
    }
    
    showDeleteBranchDialog?.let { branch ->
        DeleteBranchDialog(
            branchName = branch,
            c = c,
            onConfirm = { scope.launch { deleteBranch(branch) } },
            onDismiss = { showDeleteBranchDialog = null },
        )
    }
    
    showRenameBranchDialog?.let { branch ->
        RenameBranchDialog(
            oldName = branch,
            c = c,
            onConfirm = { newName -> scope.launch { renameBranch(branch, newName) } },
            onDismiss = { showRenameBranchDialog = null },
        )
    }
    
    selectedDiffFile?.let { (filename, diff) ->
        FileDiffSheet(
            filename = filename,
            patch = diff,
            c = c,
            onDismiss = { selectedDiffFile = null },
        )
    }
    
    if (showCommitDetail && selectedCommit != null) {
        CommitDetailSheet(
            commit = selectedCommit!!,
            c = c,
            repoPath = repo!!.path,
            commitChangedFiles = selectedCommitChangedFiles,
            onReset = { sha, mode -> scope.launch { resetCommit(sha, mode) } },
            onShowDiff = { filePath -> scope.launch { showCommitFileDiff(selectedCommit!!.sha, filePath) } },
            onDismiss = { 
                showCommitDetail = false
                selectedCommit = null
            },
        )
    }
    
    if (showConflictDialog && conflictCheckResult != null) {
        ConflictCheckSheet(
            checkResult = conflictCheckResult!!,
            c = c,
            onContinue = {
                showConflictDialog = false
                when (conflictActionType) {
                    "push" -> scope.launch { performPush(conflictForceFlag, skipCheck = true) }
                    "pull" -> scope.launch { performPull(conflictForceFlag, skipCheck = true) }
                }
            },
            onCancel = {
                showConflictDialog = false
            },
        )
    }
}

@Composable
private fun RepoStatusCard(
    repo: LocalRepo,
    c: GmColors,
    currentBranch: String,
    repoStatusStats: GitRunner.RepoStatusStats,
    onShowBranchManager: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("仓库状态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textTertiary)
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(BlueColor.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(onClick = onShowBranchManager),
                ) {
                    Icon(Icons.Default.AccountTree, null, tint = BlueColor, modifier = Modifier.size(16.dp))
                    Text(currentBranch, fontSize = 12.sp, color = BlueColor, fontFamily = FontFamily.Monospace)
                }
            }
            
            GmDivider()
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusCard(
                    value = repoStatusStats.modifiedCount.toString(),
                    label = "修改",
                    color = Yellow,
                    c = c,
                    modifier = Modifier.weight(1f),
                )
                StatusCard(
                    value = repoStatusStats.addedCount.toString(),
                    label = "新增",
                    color = Green,
                    c = c,
                    modifier = Modifier.weight(1f),
                )
                StatusCard(
                    value = repoStatusStats.removedCount.toString(),
                    label = "删除",
                    color = RedColor,
                    c = c,
                    modifier = Modifier.weight(1f),
                )
                StatusCard(
                    value = repoStatusStats.stagedCount.toString(),
                    label = "已暂存",
                    color = PurpleColor,
                    c = c,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    value: String,
    label: String,
    color: Color,
    c: GmColors,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = c.bgItem),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                text = label,
                fontSize = 11.sp,
                color = c.textTertiary,
            )
        }
    }
}

@Composable
private fun StatusItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    c: GmColors,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, fontSize = 11.sp, color = c.textTertiary)
        }
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun PushPullCard(
    c: GmColors,
    isPushing: Boolean,
    isPulling: Boolean,
    onPush: () -> Unit,
    onForcePush: () -> Unit,
    onPull: () -> Unit,
    onForcePull: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("推送/拉取", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPull,
                    modifier = Modifier.weight(1f),
                    enabled = !isPulling,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueColor),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (isPulling) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.textPrimary)
                    } else {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("拉取", fontSize = 13.sp)
                    }
                }
                
                Button(
                    onClick = onForcePull,
                    modifier = Modifier.weight(1f),
                    enabled = !isPulling,
                    colors = ButtonDefaults.buttonColors(containerColor = c.bgItem),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("强制", fontSize = 13.sp, color = c.textPrimary)
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPush,
                    modifier = Modifier.weight(1f),
                    enabled = !isPushing,
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (isPushing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.textPrimary)
                    } else {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("推送", fontSize = 13.sp)
                    }
                }
                
                Button(
                    onClick = onForcePush,
                    modifier = Modifier.weight(1f),
                    enabled = !isPushing,
                    colors = ButtonDefaults.buttonColors(containerColor = c.bgItem),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("强制", fontSize = 13.sp, color = c.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun ChangedFilesCard(
    c: GmColors,
    changedFiles: List<GitRunner.ChangedFile>,
    selectedFiles: Set<String>,
    selectAll: Boolean,
    onToggleSelectAll: (Boolean) -> Unit,
    onToggleFileSelect: (String) -> Unit,
    onStageAll: () -> Unit,
    onStageFile: (String) -> Unit,
    onUnstageFile: (String) -> Unit,
    onShowDiff: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("变更文件 (${changedFiles.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                
                if (changedFiles.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onToggleSelectAll(!selectAll) },
                        ) {
                            Checkbox(
                                checked = selectAll,
                                onCheckedChange = onToggleSelectAll,
                                colors = CheckboxDefaults.colors(checkedColor = Coral),
                            )
                            Text("全选", fontSize = 12.sp, color = c.textSecondary)
                        }
                        
                        Button(
                            onClick = onStageAll,
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Coral),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.AddCircle, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("全部暂存", fontSize = 12.sp)
                        }
                    }
                }
            }
            
            if (changedFiles.isEmpty()) {
                Text("没有变更文件", fontSize = 12.sp, color = c.textTertiary)
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    changedFiles.forEach { file ->
                        ChangedFileItem(
                            file = file,
                            c = c,
                            isSelected = selectedFiles.contains(file.path),
                            onToggleSelect = { onToggleFileSelect(file.path) },
                            onStage = { onStageFile(file.path) },
                            onUnstage = { onUnstageFile(file.path) },
                            onShowDiff = { onShowDiff(file.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangedFileItem(
    file: GitRunner.ChangedFile,
    c: GmColors,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
    onShowDiff: () -> Unit,
) {
    val (statusColor, statusLabel) = when (file.status) {
        GitRunner.FileStatus.MODIFIED -> Yellow to "M"
        GitRunner.FileStatus.ADDED -> Green to "A"
        GitRunner.FileStatus.REMOVED -> RedColor to "D"
        GitRunner.FileStatus.CHANGED -> Yellow to "C"
        GitRunner.FileStatus.UNTRACKED -> c.textTertiary to "?"
        GitRunner.FileStatus.MISSING -> RedColor to "!"
        GitRunner.FileStatus.CONFLICTING -> RedColor to "U"
        GitRunner.FileStatus.STAGED -> Green to "S"
    }
    
    val isStaged = file.status == GitRunner.FileStatus.STAGED
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isStaged) c.bgItem else c.bgDeep, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelect() },
            colors = CheckboxDefaults.colors(checkedColor = Coral),
            modifier = Modifier.size(20.dp),
        )
        
        Text(
            statusLabel,
            fontSize = 11.sp,
            color = statusColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                file.path,
                fontSize = 12.sp,
                color = c.textPrimary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onShowDiff,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.Visibility, null, tint = BlueColor, modifier = Modifier.size(16.dp))
            }
            
            if (isStaged) {
                IconButton(
                    onClick = onUnstage,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Default.RemoveCircle, null, tint = Yellow, modifier = Modifier.size(16.dp))
                }
            } else {
                IconButton(
                    onClick = onStage,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(Icons.Default.AddCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitCard(
    c: GmColors,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    isCommitting: Boolean,
    onCommit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("提交", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            
            OutlinedTextField(
                value = commitMessage,
                onValueChange = onCommitMessageChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入提交信息...", color = c.textTertiary) },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
                shape = RoundedCornerShape(12.dp),
            )
            
            Button(
                onClick = onCommit,
                modifier = Modifier.fillMaxWidth(),
                enabled = commitMessage.isNotBlank() && !isCommitting,
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isCommitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = c.textPrimary)
                } else {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("提交")
                }
            }
        }
    }
}

@Composable
private fun CommitHistoryCard(
    c: GmColors,
    commitHistory: List<GitRunner.CommitInfo>,
    onCommitClick: (GitRunner.CommitInfo) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("提交历史", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
            
            if (isExpanded) {
                if (commitHistory.isEmpty()) {
                    Text("暂无提交记录", fontSize = 12.sp, color = c.textTertiary)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        commitHistory.take(10).forEach { commit ->
                            CommitHistoryItem(
                                commit = commit,
                                c = c,
                                onClick = { onCommitClick(commit) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitHistoryItem(
    commit: GitRunner.CommitInfo,
    c: GmColors,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgDeep, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            null,
            tint = Coral,
            modifier = Modifier.size(20.dp),
        )
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                commit.message,
                fontSize = 13.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    commit.shortSha,
                    fontSize = 11.sp,
                    color = Coral,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(CoralDim, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                
                Text(
                    commit.author,
                    fontSize = 11.sp,
                    color = c.textSecondary,
                )
                
                Text(
                    "·",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                
                Text(
                    dateFormat.format(commit.time),
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitDetailSheet(
    commit: GitRunner.CommitInfo,
    c: GmColors,
    repoPath: String,
    commitChangedFiles: List<GitRunner.ChangedFile>,
    onReset: (String, String) -> Unit,
    onShowDiff: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    var showResetConfirm by remember { mutableStateOf(false) }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    commit.shortSha,
                    fontSize = 12.sp,
                    color = Coral,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(CoralDim, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            
            Spacer(Modifier.height(4.dp))
            
            Text(
                commit.message,
                fontSize = 14.sp,
                color = c.textPrimary,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            
            GmDivider()
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Person, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                Text(commit.author, fontSize = 12.sp, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                Text(dateFormat.format(commit.time), fontSize = 11.sp, color = c.textTertiary)
            }
            
            GmDivider()
            
            Text("变更文件 (${commitChangedFiles.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
            
            if (commitChangedFiles.isEmpty()) {
                Text("没有变更文件", fontSize = 12.sp, color = c.textTertiary)
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    commitChangedFiles.forEach { file ->
                        val (statusColor, statusLabel) = when (file.status) {
                            GitRunner.FileStatus.MODIFIED -> Yellow to "M"
                            GitRunner.FileStatus.ADDED -> Green to "A"
                            GitRunner.FileStatus.REMOVED -> RedColor to "D"
                            GitRunner.FileStatus.CHANGED -> Yellow to "C"
                            GitRunner.FileStatus.UNTRACKED -> c.textTertiary to "?"
                            GitRunner.FileStatus.MISSING -> RedColor to "!"
                            GitRunner.FileStatus.CONFLICTING -> RedColor to "U"
                            GitRunner.FileStatus.STAGED -> Green to "S"
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.bgDeep, RoundedCornerShape(8.dp))
                                .clickable { onShowDiff(file.path) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                statusLabel,
                                fontSize = 11.sp,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                            
                            Text(
                                file.path,
                                fontSize = 12.sp,
                                color = c.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            
                            Icon(
                                Icons.Default.Visibility,
                                null,
                                tint = BlueColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            
            GmDivider()
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RedColor),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Restore, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重置", fontSize = 13.sp)
                }
            }
        }
    }
    
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = c.bgCard,
            title = { Text("重置提交", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "确认重置到提交 ${commit.shortSha}？",
                        fontSize = 12.sp,
                        color = c.textSecondary,
                    )
                    Text(
                        "选择重置模式：",
                        fontSize = 12.sp,
                        color = c.textTertiary,
                    )
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            onReset(commit.sha, "soft")
                            showResetConfirm = false
                        },
                    ) {
                        Text("Soft（保留更改）", color = Coral)
                    }
                    TextButton(
                        onClick = {
                            onReset(commit.sha, "mixed")
                            showResetConfirm = false
                        },
                    ) {
                        Text("Mixed（默认）", color = BlueColor)
                    }
                    TextButton(
                        onClick = {
                            onReset(commit.sha, "hard")
                            showResetConfirm = false
                        },
                    ) {
                        Text("Hard（丢弃更改）", color = RedColor)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("取消", color = c.textSecondary)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchManagerSheet(
    repo: LocalRepo,
    c: GmColors,
    branches: List<GitRunner.BranchInfo>,
    currentBranch: String,
    onCheckout: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "分支管理",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            
            Spacer(Modifier.height(8.dp))
            
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CoralDim, contentColor = Coral),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建分支", fontSize = 13.sp)
            }
            
            Spacer(Modifier.height(12.dp))
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(branches.filter { !it.isRemote }) { branch ->
                    BranchItem(
                        branchName = branch.name,
                        isCurrent = branch.isCurrent,
                        c = c,
                        onCheckout = { onCheckout(branch.name) },
                        onRename = { onRename(branch.name) },
                        onDelete = { onDelete(branch.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchItem(
    branchName: String,
    isCurrent: Boolean,
    c: GmColors,
    onCheckout: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isCurrent) CoralDim else c.bgItem,
                RoundedCornerShape(12.dp),
            )
            .then(
                if (!isCurrent) {
                    Modifier.clickable(onClick = onCheckout)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AccountTree,
            null,
            tint = if (isCurrent) Coral else c.textTertiary,
            modifier = Modifier.size(15.dp),
        )
        
        Spacer(Modifier.width(8.dp))
        
        Column(Modifier.weight(1f)) {
            Text(
                branchName,
                fontSize = 13.sp,
                color = if (isCurrent) Coral else c.textPrimary,
                fontFamily = FontFamily.Monospace,
            )
        }
        
        if (isCurrent) {
            GmBadge("当前", CoralDim, Coral)
            Spacer(Modifier.width(4.dp))
        }
        
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    null,
                    tint = c.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(c.bgCard),
            ) {
                if (!isCurrent) {
                    DropdownMenuItem(
                        text = { Text("切换到此分支", fontSize = 13.sp, color = c.textPrimary) },
                        leadingIcon = { Icon(Icons.Default.AccountTree, null, tint = BlueColor, modifier = Modifier.size(15.dp)) },
                        onClick = { onCheckout(); showMenu = false },
                    )
                }
                
                DropdownMenuItem(
                    text = { Text("重命名", fontSize = 13.sp, color = c.textPrimary) },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null, tint = c.textSecondary, modifier = Modifier.size(15.dp)) },
                    onClick = { onRename(); showMenu = false },
                )
                
                if (!isCurrent) {
                    DropdownMenuItem(
                        text = { Text("删除分支", fontSize = 13.sp, color = RedColor) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedColor, modifier = Modifier.size(15.dp)) },
                        onClick = { onDelete(); showMenu = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewBranchDialog(
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var branchName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("新建分支", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "请输入新的分支名称。",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                )
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("feature/my-branch", color = c.textTertiary) },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueColor,
                        unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (branchName.isNotBlank()) onConfirm(branchName) },
                enabled = branchName.isNotBlank(),
            ) {
                Text("创建", color = Coral)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

@Composable
private fun DeleteBranchDialog(
    branchName: String,
    c: GmColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("删除分支", color = c.textPrimary) },
        text = { Text("确认删除分支 \"$branchName\"？此操作不可撤销。", color = c.textSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RedColor),
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

@Composable
private fun RenameBranchDialog(
    oldName: String,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(oldName) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("重命名分支", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "当前分支：$oldName",
                    fontSize = 12.sp,
                    color = c.textSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新名称") },
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BlueColor,
                        unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                        focusedContainerColor = c.bgItem,
                        unfocusedContainerColor = c.bgItem,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank() && newName != oldName) onConfirm(newName) },
                enabled = newName.isNotBlank() && newName != oldName,
            ) {
                Text("重命名", color = Coral)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDiffSheet(
    filename: String,
    patch: String,
    c: GmColors,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1117),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF30363D)) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                Icon(
                    Icons.Default.Description,
                    null,
                    tint = Color(0xFF8B949E),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    filename,
                    fontSize = 13.sp,
                    color = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
            }
            
            HorizontalDivider(color = Color(0xFF30363D), thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            
            val lines = patch.lines()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                lines.forEach { line ->
                    val (bg, fg) = when {
                        line.startsWith("+") && !line.startsWith("+++") ->
                            Color(0xFF0D4A29) to Color(0xFF85E89D)
                        line.startsWith("-") && !line.startsWith("---") ->
                            Color(0xFF430D18) to Color(0xFFFFA198)
                        line.startsWith("@@") ->
                            Color(0xFF1B3A5E) to Color(0xFF79C0FF)
                        else -> Color.Transparent to Color(0xFF8B949E)
                    }
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        color = fg,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictCheckSheet(
    checkResult: GitRunner.ConflictCheckResult,
    c: GmColors,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = if (checkResult.isConflicting) RedColor else Yellow,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = if (checkResult.isConflicting) "检测到冲突" else "检测到变更",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
            }
            
            GmDivider()
            
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (checkResult.hasLocalChanges) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                null,
                                tint = Yellow,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "本地有未提交的变更",
                                fontSize = 14.sp,
                                color = c.textPrimary,
                            )
                        }
                    }
                }
                
                if (checkResult.hasRemoteChanges) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                null,
                                tint = BlueColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "远程有新的变更",
                                fontSize = 14.sp,
                                color = c.textPrimary,
                            )
                        }
                    }
                }
                
                if (checkResult.localCommitsAhead > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "本地领先远程",
                            fontSize = 14.sp,
                            color = c.textSecondary,
                        )
                        Text(
                            text = "${checkResult.localCommitsAhead} 个提交",
                            fontSize = 14.sp,
                            color = Green,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                
                if (checkResult.remoteCommitsAhead > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "远程领先本地",
                            fontSize = 14.sp,
                            color = c.textSecondary,
                        )
                        Text(
                            text = "${checkResult.remoteCommitsAhead} 个提交",
                            fontSize = 14.sp,
                            color = RedColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            
            GmDivider()
            
            Text(
                text = if (checkResult.isConflicting) {
                    "本地和远程都有变更，继续操作可能会产生冲突。建议先提交本地变更或拉取远程变更。"
                } else {
                    "检测到远程有新的变更，建议先拉取再操作。"
                },
                fontSize = 13.sp,
                color = c.textTertiary,
                lineHeight = 18.sp,
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.textSecondary),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("取消")
                }
                
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (checkResult.isConflicting) RedColor else Coral
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("继续")
                }
            }
        }
    }
}
