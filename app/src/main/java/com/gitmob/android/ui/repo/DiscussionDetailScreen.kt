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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.gitmob.android.api.*
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussionDetailScreen(
    owner: String,
    repoName: String,
    discussionNumber: Int,
    onBack: () -> Unit,
    vm: DiscussionDetailViewModel = viewModel(
        factory = DiscussionDetailViewModel.factory(owner, repoName, discussionNumber)
    ),
) {
    val c = LocalGmColors.current
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val repoForPermission = remember(owner) {
        com.gitmob.android.api.GHRepo(
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
            owner = com.gitmob.android.api.GHOwner(
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCommentInputDialog by remember { mutableStateOf(false) }
    var showCommentEditor by remember { mutableStateOf<GHDiscussionComment?>(null) }
    var commentText by remember { mutableStateOf("") }
    var replyingToComment by remember { mutableStateOf<GHDiscussionComment?>(null) }
    var replyingToDiscussion by remember { mutableStateOf(false) }
    var showEditHistory by remember { mutableStateOf<DiscussionEditHistoryType?>(null) }

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
                        Text("#$discussionNumber", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.textPrimary)
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
                        IconButton(onClick = { state.discussion?.let { shareDiscussion(context, it) } }) {
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
                                val isArchived = state.isArchived
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (state.isSubscribed) "取消订阅" else "订阅",
                                            fontSize = 14.sp,
                                            color = c.textPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (state.isSubscribed) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
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
                                val isDiscussionAuthor = state.discussion?.author?.login == state.userLogin
                                val canEdit = permission.isOwner || isDiscussionAuthor

                                if (canEdit) {
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
                                }
                                PermissionRequired(permission = permission, requireOwner = true) {
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
            onRefresh = { vm.loadDiscussionDetail(forceRefresh = true) },
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
            } else if (state.discussion != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        DiscussionHeader(discussion = state.discussion!!, c = c)
                    }

                    item {
                        DiscussionBodyCard(
                            discussion = state.discussion!!,
                            c = c,
                            userLogin = state.userLogin,
                            permission = permission,
                            isArchived = state.isArchived,
                            onReply = {
                                replyingToDiscussion = true
                                showCommentInputDialog = true
                            },
                            onEdit = { showEditBodyDialog = true },
                            onShare = { shareDiscussion(context, state.discussion!!) },
                            onShowEditHistory = { 
                                showEditHistory = DiscussionEditHistoryType.DiscussionBody(state.discussion!!)
                            }
                        )
                    }

                    items(state.comments, key = { it.id }) { comment ->
                        DiscussionCommentCard(
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
                                showEditHistory = DiscussionEditHistoryType.Comment(it)
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

    if (showDeleteDialog) {
        DeleteDiscussionDialog(
            c = c,
            onConfirm = {
                vm.deleteDiscussion()
                showDeleteDialog = false
                onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    if (showEditTitleDialog) {
        EditTitleDialog(
            c = c,
            initialTitle = state.discussion?.title ?: "",
            onConfirm = { newTitle ->
                vm.updateDiscussion(title = newTitle)
                showEditTitleDialog = false
            },
            onDismiss = { showEditTitleDialog = false },
        )
    }

    if (showEditBodyDialog && state.discussion != null) {
        EditDiscussionBodyDialog(
            currentBody = state.discussion?.body,
            c = c,
            onConfirm = { body ->
                vm.updateDiscussion(body = body)
                showEditBodyDialog = false
            },
            onDismiss = { showEditBodyDialog = false },
        )
    }

    if (showCommentInputDialog) {
        DiscussionCommentInputDialog(
            currentText = commentText,
            replyingTo = replyingToComment,
            replyingToDiscussion = replyingToDiscussion,
            discussionAuthor = state.discussion?.author,
            c = c,
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    val finalText = if (replyingToComment != null) {
                        val quoted = replyingToComment!!.body
                            .lines()
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else if (replyingToDiscussion) {
                        val discussionBody = state.discussion?.body.orEmpty()
                        val quoted = discussionBody
                            .lines()
                            .take(10)
                            .joinToString("\n") { "> $it" }
                        "$quoted\n\n$text"
                    } else {
                        text
                    }
                    vm.createComment(finalText, replyToId = replyingToComment?.id)
                    commentText = ""
                    replyingToComment = null
                    replyingToDiscussion = false
                    showCommentInputDialog = false
                }
            },
            onDismiss = {
                showCommentInputDialog = false
            },
            onCancelReply = {
                replyingToComment = null
                replyingToDiscussion = false
            },
        )
    }

    if (showCommentEditor != null) {
        EditDiscussionCommentDialog(
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
                is DiscussionEditHistoryType.DiscussionBody -> {
                    vm.loadDiscussionBodyEditHistory()
                }
                is DiscussionEditHistoryType.Comment -> {
                    LogManager.d("DiscussionDetailScreen", "加载评论编辑历史, commentId: ${type.comment.id}, nodeId: ${type.comment.nodeId}")
                    vm.loadDiscussionCommentEditHistory(type.comment.nodeId)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDiscussionBodyDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscussionCommentInputDialog(
    currentText: String,
    replyingTo: GHDiscussionComment?,
    replyingToDiscussion: Boolean,
    discussionAuthor: GHOwner?,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (replyingTo != null || replyingToDiscussion) "引用回复" else "发表评论",
                    fontSize = 16.sp,
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (replyingTo != null || replyingToDiscussion) {
                    TextButton(onClick = onCancelReply) {
                        Text("取消引用", color = Coral)
                    }
                }
            }
            GmDivider()
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("评论") },
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
                    enabled = text.isNotBlank()
                ) {
                    Text("发表", color = Coral)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDiscussionCommentDialog(
    comment: GHDiscussionComment,
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

@Composable
private fun EditTitleDialog(
    c: GmColors,
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text("编辑标题", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = c.textPrimary),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.bgItem,
                    unfocusedContainerColor = c.bgItem,
                    cursorColor = Coral,
                    focusedIndicatorColor = Coral,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

@Composable
private fun DiscussionHeader(
    discussion: GHDiscussion,
    c: GmColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            discussion.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = c.textPrimary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (discussion.category?.isAnswerable == true) {
                    Surface(
                        color = if (discussion.isAnswered) Green.copy(alpha = 0.2f) else c.bgItem,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            if (discussion.isAnswered) "已回答" else "未回答",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (discussion.isAnswered) Green else c.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                discussion.category?.let { cat ->
                    Surface(
                        color = c.bgItem,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "${cat.emoji} ${cat.name}",
                            fontSize = 12.sp,
                            color = c.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                discussion.author?.let { author ->
                    AsyncImage(
                        model = author.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape),
                    )
                    Text(
                        author.login,
                        fontSize = 12.sp,
                        color = c.textSecondary,
                    )
                }
            }
            Text(
                formatDate(discussion.createdAt),
                fontSize = 12.sp,
                color = c.textTertiary,
            )
        }
    }
}

@Composable
private fun DiscussionBodyCard(
    discussion: GHDiscussion,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onShowEditHistory: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isOwnerOrAuthor = permission.isOwner || (discussion.author?.login == userLogin)
    val isDiscussionEdited = hasDiscussionBeenEdited(discussion)

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
                discussion.author?.let { author ->
                    AsyncImage(
                        model = author.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            discussion.author?.login ?: "unknown",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.textPrimary,
                        )
                        if (isDiscussionEdited) {
                            EditedButton(
                                onClick = onShowEditHistory,
                                c = c,
                                enabled = true
                            )
                        }
                    }
                    Text(
                        formatDate(discussion.createdAt),
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
                        if (isOwnerOrAuthor) {
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
            if (discussion.body.isNullOrBlank()) {
                Text(
                    "暂无描述",
                    fontSize = 14.sp,
                    color = c.textTertiary,
                    lineHeight = 20.sp,
                )
            } else {
                GmMarkdownWebView(
                    markdown = discussion.body,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DiscussionCommentCard(
    comment: GHDiscussionComment,
    c: GmColors,
    userLogin: String,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onReply: (GHDiscussionComment) -> Unit,
    onEdit: (GHDiscussionComment) -> Unit,
    onDelete: (GHDiscussionComment) -> Unit,
    onShare: (GHDiscussionComment) -> Unit,
    onShowEditHistory: (GHDiscussionComment) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isOwnerOrAuthor = permission.isOwner || (comment.author?.login == userLogin)
    val isCommentEdited = hasDiscussionCommentBeenEdited(comment)

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
                comment.author?.let { author ->
                    AsyncImage(
                        model = author.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            comment.author?.login ?: "unknown",
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
                        if (isOwnerOrAuthor && comment.viewerCanUpdate) {
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
                        }
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
                        if (isOwnerOrAuthor && comment.viewerCanDelete) {
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

@Composable
fun DeleteDiscussionDialog(
    c: GmColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.DeleteForever, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("删除讨论", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = { Text("确定要删除这个讨论吗？此操作无法撤销。", color = c.textSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RedColor),
            ) { Text("确认删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
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

private fun shareDiscussion(context: Context, discussion: GHDiscussion) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, discussion.url)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Discussion")
    }
    context.startActivity(Intent.createChooser(intent, "分享讨论"))
}

private fun shareComment(context: Context, comment: GHDiscussionComment) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, comment.url)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub Discussion Comment")
    }
    context.startActivity(Intent.createChooser(intent, "分享评论"))
}

/** 讨论编辑历史类型 */
sealed class DiscussionEditHistoryType {
    data class DiscussionBody(val discussion: GHDiscussion) : DiscussionEditHistoryType()
    data class Comment(val comment: GHDiscussionComment) : DiscussionEditHistoryType()
}

/**
 * 编辑历史底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHistorySheet(
    type: DiscussionEditHistoryType,
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
 * 编辑历史内容组件
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
 * 判断讨论主体是否被编辑过
 */
private fun hasDiscussionBeenEdited(discussion: GHDiscussion): Boolean {
    return discussion.lastEditedAt != null
}

/**
 * 判断讨论评论是否被编辑过
 */
private fun hasDiscussionCommentBeenEdited(comment: GHDiscussionComment): Boolean {
    return comment.lastEditedAt != null
}
