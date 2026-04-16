@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.gitmob.android.api.*
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueDetailScreen(
    owner: String,
    repoName: String,
    issueNumber: Int,
    onBack: () -> Unit,
    vm: IssueDetailViewModel = viewModel(
        factory = IssueDetailViewModel.factory(owner, repoName, issueNumber)
    ),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val repoForPermission = remember(owner) {
        GHRepo(
            id = 0,
            name = repoName,
            fullName = "$owner/$repoName",
            description = null,
            homepage = null,
            private = false,
            htmlUrl = "",
            sshUrl = "",
            cloneUrl = "",
            defaultBranch = "",
            updatedAt = null,
            owner = GHOwner(
                login = owner,
                avatarUrl = null
            ),
            pushedAt = null,
            language = null
        )
    }
    val permission = rememberRepoPermission(repoForPermission, state.userLogin)
    
    var showMenu by remember { mutableStateOf(false) }
    var showEditTitleDialog by remember { mutableStateOf(false) }
    var showEditBodyDialog by remember { mutableStateOf(false) }
    var showCloseSubMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCommentEditor by remember { mutableStateOf<GHComment?>(null) }
    var showCommentInputDialog by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    var replyingToComment by remember { mutableStateOf<GHComment?>(null) }
    var replyingToIssue by remember { mutableStateOf(false) }
    var showEditHistory by remember { mutableStateOf<EditHistoryType?>(null) }

    state.toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2500)
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("#$issueNumber", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
                        Text("$owner/$repoName", fontSize = 12.sp, color = c.textTertiary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                actions = {
                    Row {
                        IconButton(onClick = { state.issue?.let { shareIssue(context, it) } }) {
                            Icon(Icons.Default.Share, null, tint = c.textSecondary)
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, null, tint = c.textSecondary)
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(c.bgCard),
                            ) {
                                val isSubscribed = state.isSubscribed
                                val isArchived = state.isArchived
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isSubscribed) "取消订阅" else "订阅",
                                            fontSize = 14.sp,
                                            color = c.textPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isSubscribed) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        vm.toggleSubscription()
                                    },
                                )
                                val isIssueAuthor = state.issue?.user?.login == state.userLogin
                                val canEditOrClose = permission.isOwner || isIssueAuthor
                                
                                if (canEditOrClose) {
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("编辑标题", fontSize = 14.sp) },
                                        isArchived = isArchived,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Edit,
                                                null,
                                                tint = c.textSecondary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showEditTitleDialog = true
                                        },
                                    )
                                    if (state.issue?.state == "open") {
                                        ArchivedAwareDropdownMenuItem(
                                            text = { Text("关闭议题", fontSize = 14.sp) },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Close,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showCloseSubMenu = true
                                            },
                                        )
                                    } else {
                                        ArchivedAwareDropdownMenuItem(
                                            text = { Text("重新打开议题", fontSize = 14.sp) },
                                            isArchived = isArchived,
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Check,
                                                    null,
                                                    tint = c.textSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                vm.updateIssue(state = "open")
                                            },
                                        )
                                    }
                                }
                                PermissionRequired(permission = permission, requireOwner = true) {
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("置顶", fontSize = 14.sp) },
                                        isArchived = isArchived,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.PushPin,
                                                null,
                                                tint = c.textSecondary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                        },
                                    )
                                    ArchivedAwareDropdownMenuItem(
                                        text = { Text("删除", fontSize = 14.sp) },
                                        isArchived = isArchived,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                null,
                                                tint = Color.Red,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showDeleteDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
            PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadIssueDetail(forceRefresh = true) },
            modifier = Modifier.padding(paddingValues),
        ) {
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (state.error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = c.textSecondary)
                }
            } else if (state.issue != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        IssueHeader(issue = state.issue!!, c = c)
                    }

                    item {
                        IssueBodyCard(
                            issue = state.issue!!,
                            c = c,
                            userLogin = state.userLogin,
                            permission = permission,
                            isArchived = state.isArchived,
                            onReply = { 
                                replyingToIssue = true
                                showCommentInputDialog = true
                            },
                            onEdit = { showEditBodyDialog = true },
                            onShare = { shareIssue(context, state.issue!!) },
                            onShowEditHistory = { 
                                showEditHistory = EditHistoryType.IssueBody(state.issue!!)
                            }
                        )
                    }

                    items(state.comments, key = { it.id }) { comment ->
                        CommentCard(
                            comment = comment,
                            c = c,
                            userLogin = state.userLogin,
                            permission = permission,
                            isArchived = state.isArchived,
                            onReply = { 
                                replyingToComment = it
                                showCommentInputDialog = true
                            },
                            onEdit = { showCommentEditor = it },
                            onDelete = { vm.deleteComment(it.id) },
                            onShare = { shareComment(context, it) },
                            onShowEditHistory = { 
                                showEditHistory = EditHistoryType.Comment(it)
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }

        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FloatingActionButton(
                onClick = { 
                    if (!state.isArchived) {
                        showCommentInputDialog = true 
                    }
                },
                containerColor = if (state.isArchived) Coral.copy(alpha = 0.38f) else Coral,
                contentColor = if (state.isArchived) Color.White.copy(alpha = 0.38f) else Color.White,
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Comment, null)
            }
        }
    }

    if (showCommentInputDialog) {
        CommentInputDialog(
            currentText = commentText,
            replyingTo = replyingToComment,
            replyingToIssue = replyingToIssue,
            issueUser = state.issue?.user,
            c = c,
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    val finalText = if (replyingToComment != null) {
                        // GitHub 标准：每行加 "> " 前缀构成 blockquote，空行后写回复
                        val quoted = replyingToComment!!.body
                            .lines()
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else if (replyingToIssue) {
                        val issueBody = state.issue?.body?.orEmpty() ?: ""
                        val quoted = issueBody
                            .lines()
                            .take(10) // 只引用前10行，避免过长
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else {
                        text
                    }
                    vm.createComment(finalText)
                    commentText = ""
                    replyingToComment = null
                    replyingToIssue = false
                    showCommentInputDialog = false
                }
            },
            onDismiss = {
                showCommentInputDialog = false
            },
            onCancelReply = {
                replyingToComment = null
                replyingToIssue = false
            },
        )
    }

    if (showEditTitleDialog && state.issue != null) {
        EditIssueTitleDialog(
            currentTitle = state.issue!!.title,
            c = c,
            onConfirm = { title ->
                vm.updateIssue(title = title, body = state.issue!!.body)
                showEditTitleDialog = false
            },
            onDismiss = { showEditTitleDialog = false },
        )
    }

    if (showEditBodyDialog && state.issue != null) {
        EditIssueBodyDialog(
            currentBody = state.issue!!.body,
            c = c,
            onConfirm = { body ->
                vm.updateIssue(title = state.issue!!.title, body = body)
                showEditBodyDialog = false
            },
            onDismiss = { showEditBodyDialog = false },
        )
    }

    if (showCloseSubMenu) {
        CloseIssueDialog(
            c = c,
            onConfirm = { reason ->
                vm.updateIssue(state = "closed", stateReason = reason)
                showCloseSubMenu = false
            },
            onDismiss = { showCloseSubMenu = false },
            onDuplicate = {
                showCloseSubMenu = false
            },
        )
    }

    if (showDeleteDialog) {
        DeleteIssueDialog(
            c = c,
            onConfirm = {
                vm.deleteIssue(onBack)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showCommentEditor != null) {
        EditCommentDialog(
            comment = showCommentEditor!!,
            c = c,
            onConfirm = { newBody ->
                vm.updateComment(showCommentEditor!!.id, newBody)
                showCommentEditor = null
            },
            onDismiss = { showCommentEditor = null },
        )
    }

    if (showEditHistory != null) {
        LaunchedEffect(showEditHistory) {
            when (val type = showEditHistory!!) {
                is EditHistoryType.IssueBody -> {
                    vm.loadIssueBodyEditHistory()
                }
                is EditHistoryType.Comment -> {
                    vm.loadIssueCommentEditHistory(type.comment.nodeId)
                }
            }
        }
        EditHistorySheet(
            type = showEditHistory!!,
            editHistory = state.editHistory,
            loading = state.editHistoryLoading,
            c = c,
            onDismiss = { showEditHistory = null }
        )
    }
}

@Composable
private fun IssueHeader(
    issue: GHIssue,
    c: GmColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            issue.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = c.textPrimary,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (issue.state == "open") Green.copy(alpha = 0.2f) else RedColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    if (issue.state == "open") "开放" else "已关闭",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (issue.state == "open") Green else RedColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            AsyncImage(
                model = issue.user.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape),
            )
            Text(
                issue.user.login,
                fontSize = 12.sp,
                color = c.textSecondary,
            )
            Text(
                formatDate(issue.createdAt),
                fontSize = 12.sp,
                color = c.textTertiary,
            )
        }
    }
}

@Composable
private fun IssueBodyCard(
    issue: GHIssue,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onShowEditHistory: () -> Unit,
) {
    val isOwnIssue = issue.user.login == userLogin
    val canEdit = isOwnIssue || permission.isOwner
    val isIssueEdited = isEdited(issue.createdAt, issue.updatedAt)
    var showMenu by remember { mutableStateOf(false) }
    
    Surface(
        color = c.bgCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = issue.user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            issue.user.login,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.textPrimary,
                        )
                        if (isIssueEdited) {
                            EditedButton(
                                onClick = onShowEditHistory,
                                c = c,
                                enabled = true
                            )
                        }
                    }
                    Text(
                        formatDate(issue.createdAt),
                        fontSize = 12.sp,
                        color = c.textTertiary,
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(c.bgCard),
                    ) {
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Share,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShare()
                            },
                        )
                        ArchivedAwareDropdownMenuItem(
                                text = { Text("引用回复", fontSize = 14.sp) },
                                isArchived = isArchived,
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Reply,
                                        null,
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onReply()
                                },
                            )
                            if (canEdit) {
                                ArchivedAwareDropdownMenuItem(
                                    text = { Text("编辑描述", fontSize = 14.sp) },
                                    isArchived = isArchived,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Edit,
                                            null,
                                            tint = c.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        onEdit()
                                    },
                                )
                            }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (issue.body.isNullOrBlank()) {
                Text(
                    "暂无描述",
                    fontSize = 14.sp,
                    color = c.textTertiary,
                    lineHeight = 20.sp,
                )
            } else {
                GmMarkdownWebView(
                    markdown = issue.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CommentCard(
    comment: GHComment,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: (GHComment) -> Unit,
    onEdit: (GHComment) -> Unit,
    onDelete: (GHComment) -> Unit,
    onShare: (GHComment) -> Unit,
    onShowEditHistory: (GHComment) -> Unit,
) {
    val isOwnComment = comment.user.login == userLogin
    val canEditOrDelete = isOwnComment || permission.isOwner
    val isCommentEdited = isEdited(comment.createdAt, comment.updatedAt)
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = c.bgCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = comment.user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            comment.user.login,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.textPrimary,
                        )
                        if (isCommentEdited) {
                            EditedButton(
                                onClick = { onShowEditHistory(comment) },
                                c = c,
                                enabled = true
                            )
                        }
                    }
                    Text(
                        formatDate(comment.createdAt),
                        fontSize = 12.sp,
                        color = c.textTertiary,
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(c.bgCard),
                    ) {
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp, color = c.textPrimary) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Share,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onShare(comment)
                            },
                        )
                        ArchivedAwareDropdownMenuItem(
                            text = { Text("引用回复", fontSize = 14.sp) },
                            isArchived = isArchived,
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Reply,
                                    null,
                                    tint = c.textSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                showMenu = false
                                onReply(comment)
                            },
                        )
                        if (canEditOrDelete) {
                            ArchivedAwareDropdownMenuItem(
                                text = { Text("编辑", fontSize = 14.sp) },
                                isArchived = isArchived,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        null,
                                        tint = c.textSecondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onEdit(comment)
                                },
                            )
                            ArchivedAwareDropdownMenuItem(
                                text = { Text("删除", fontSize = 14.sp) },
                                isArchived = isArchived,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        null,
                                        tint = Color.Red,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete(comment)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            GmMarkdownWebView(
                markdown = comment.body,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditIssueTitleDialog(
    currentTitle: String,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标题", color = c.textPrimary) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) {
                Text("保存", color = Coral)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
        containerColor = c.bgCard,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditIssueBodyDialog(
    currentBody: String?,
    c: GmColors,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var body by remember { mutableStateOf(currentBody ?: "") }
    
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "编辑描述",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("描述") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = c.textSecondary)
                }
                TextButton(onClick = { onConfirm(body.ifBlank { null }) }) {
                    Text("保存", color = Coral)
                }
            }
        }
    }
}

@Composable
private fun CloseIssueDialog(
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onDuplicate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关闭议题", color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onConfirm("completed") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = Green)
                        Text("已完成", color = c.textPrimary)
                    }
                }
                TextButton(
                    onClick = { onConfirm("not_planned") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Cancel, null, tint = c.textSecondary)
                        Text("未计划", color = c.textPrimary)
                    }
                }
                TextButton(
                    onClick = onDuplicate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = c.textSecondary)
                        Text("重复", color = c.textPrimary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
        containerColor = c.bgCard,
    )
}

@Composable
fun DeleteIssueDialog(
    c: GmColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除议题", color = c.textPrimary) },
        text = { Text("确定要删除这个议题吗？此操作无法撤销。", color = c.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textSecondary)
            }
        },
        containerColor = c.bgCard,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCommentDialog(
    comment: GHComment,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var body by remember { mutableStateOf(comment.body) }
    
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "编辑评论",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("评论内容") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = c.textSecondary)
                }
                TextButton(onClick = { onConfirm(body) }) {
                    Text("保存", color = Coral)
                }
            }
        }
    }
}

private fun formatDate(dateStr: String): String {
    return try {
        val date = OffsetDateTime.parse(dateStr)
        val localDateTime = date.atZoneSameInstant(java.time.ZoneId.systemDefault())
        val formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")
        localDateTime.format(formatter)
    } catch (_: Exception) {
        dateStr
    }
}

private fun shareComment(context: Context, comment: GHComment) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, comment.htmlUrl)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Comment")
    }
    context.startActivity(Intent.createChooser(intent, "分享评论"))
}

private fun shareIssue(context: Context, issue: GHIssue) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, issue.htmlUrl)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Issue")
    }
    context.startActivity(Intent.createChooser(intent, "分享议题"))
}

/**
 * 评论输入底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentInputDialog(
    currentText: String,
    replyingTo: GHComment?,
    replyingToIssue: Boolean,
    issueUser: GHOwner?,
    c: GmColors,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onCancelReply: () -> Unit,
) {
    var text by remember { mutableStateOf(currentText) }
    
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "发表评论",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            if (replyingTo != null || replyingToIssue) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        null,
                        tint = c.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (replyingTo != null) "回复 @${replyingTo.user.login}" else "回复 @${issueUser?.login}",
                        fontSize = 12.sp,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onCancelReply,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("评论内容") },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bgDeep,
                    unfocusedContainerColor = c.bgDeep,
                    focusedBorderColor = Coral,
                    unfocusedBorderColor = c.border,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = c.textSecondary)
                }
                TextButton(
                    onClick = { onConfirm(text) },
                    enabled = text.isNotBlank(),
                ) {
                    Text("发表", color = Coral)
                }
            }
        }
    }
}

/** 编辑历史类型 */
sealed class EditHistoryType {
    data class IssueBody(val issue: GHIssue) : EditHistoryType()
    data class Comment(val comment: GHComment) : EditHistoryType()
}

/**
 * 编辑历史底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHistorySheet(
    type: EditHistoryType,
    editHistory: List<GHUserContentEdit>,
    loading: Boolean,
    c: GmColors,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "编辑历史",
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Coral)
                }
            } else if (editHistory.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无编辑历史",
                        fontSize = 14.sp,
                        color = c.textTertiary
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(editHistory.reversed(), key = { it.id }) { edit ->
                        EditHistoryItem(edit = edit, c = c)
                    }
                }
            }
        }
    }
}

/**
 * 编辑历史单项组件
 */
@Composable
private fun EditHistoryItem(
    edit: GHUserContentEdit,
    c: GmColors,
) {
    var showDiff by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.bgItem),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (edit.editor != null) {
                        AsyncImage(
                            model = edit.editor.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                        )
                        Text(
                            edit.editor.login,
                            fontSize = 13.sp,
                            color = c.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    formatDate(edit.createdAt),
                    fontSize = 12.sp,
                    color = c.textTertiary
                )
            }
            
            val diff = edit.diff
            if (diff != null && diff.isNotBlank()) {
                GmDivider()
                Surface(
                    onClick = { showDiff = !showDiff },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showDiff) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = Coral,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            if (showDiff) "隐藏差异" else "查看差异",
                            fontSize = 13.sp,
                            color = Coral,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (showDiff) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = c.bgDeep,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            diff,
                            fontSize = 12.sp,
                            color = c.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 编辑历史内容组件（保留用于兼容性）
 */
@Composable
private fun EditHistoryContent(
    title: String,
    createdAt: String?,
    updatedAt: String?,
    c: GmColors,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            fontSize = 14.sp,
            color = c.textSecondary,
            fontWeight = FontWeight.Medium
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "创建时间",
                    fontSize = 11.sp,
                    color = c.textTertiary
                )
                Text(
                    createdAt?.let { formatDate(it) } ?: "-",
                    fontSize = 13.sp,
                    color = c.textPrimary
                )
            }
            
            if (updatedAt != null && updatedAt != createdAt) {
                Column {
                    Text(
                        "最后编辑",
                        fontSize = 11.sp,
                        color = c.textTertiary
                    )
                    Text(
                        formatDate(updatedAt),
                        fontSize = 13.sp,
                        color = Coral
                    )
                }
            }
        }
    }
}

/**
 * 已编辑按钮组件
 */
@Composable
private fun EditedButton(
    onClick: () -> Unit,
    c: GmColors,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        color = if (enabled) c.bgItem else c.bgItem,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(24.dp),
        enabled = enabled
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            Icon(
                Icons.Default.History,
                null,
                tint = if (enabled) c.textTertiary else c.textTertiary.copy(alpha = 0.38f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                "已编辑",
                fontSize = 10.sp,
                color = if (enabled) c.textTertiary else c.textTertiary.copy(alpha = 0.38f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 判断内容是否被编辑过
 */
private fun isEdited(createdAt: String?, updatedAt: String?): Boolean {
    if (createdAt == null || updatedAt == null) return false
    return createdAt != updatedAt
}
