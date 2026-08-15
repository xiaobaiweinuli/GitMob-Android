package com.gitmob.app.ui.inbox

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.InboxNotification
import com.gitmob.app.data.model.InboxReadFilter

/**
 * 收件箱 Push 页（通知列表）。
 *
 * TopAppBar 的返回按钮走 [onBack]（= Nav3 backStack.removeLastOrNull()）；
 * 内容用 Scaffold 的 innerPadding(Top+Horizontal) 避开状态栏 + AppBar 高度；
 * 底部高度从 WindowInsets.navigationBars + captionBar 直接取（Push 路由没有外层
 * MainTabHost 的 NavigationBar，所以自己读系统 inset，和 KernelSU 保持一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit = {},
    onNotificationClick: (InboxNotification) -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                // ★ 与 Scaffold contentWindowInsets 一致，不用 WindowInsets(0)
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            InboxReadFilterMenu(
                selected = state.readFilter,
                onSelected = viewModel::setReadFilter,
            )
            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.notifications.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.loadFailed && state.notifications.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(stringResource(R.string.common_load_failed))
                            Button(
                                onClick = viewModel::retry,
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text(stringResource(R.string.common_retry)) }
                        }
                    }
                    else -> {
                        PullToRefreshBox(
                            isRefreshing = state.isRefreshing,
                            onRefresh = viewModel::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                if (state.notifications.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier.fillParentMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                stringResource(state.readFilter.emptyStateRes),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                } else {
                                    items(state.notifications) { notification ->
                                        NotificationRow(
                                            notification,
                                            onClick = {
                                                if (notification.isUnread) viewModel.markAsRead(notification)
                                                onNotificationClick(notification)
                                            },
                                        )
                                    }
                                }
                                if (state.hasNextPage) {
                                    item {
                                        LaunchedEffect(state.notifications.size) { viewModel.loadMore() }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                                // Push route 底部：navigationBars + captionBar 高度
                                item(key = "bottom_spacer") {
                                    Spacer(
                                        Modifier.height(
                                            WindowInsets.navigationBars.asPaddingValues()
                                                .calculateBottomPadding() +
                                                WindowInsets.captionBar.asPaddingValues()
                                                    .calculateBottomPadding(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxReadFilterMenu(
    selected: InboxReadFilter,
    onSelected: (InboxReadFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.work_filter_state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(selected.labelRes), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(
                    R.string.work_select_filter,
                    stringResource(R.string.work_filter_state),
                ),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            InboxReadFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                    leadingIcon = {
                        if (option == selected) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}

private val InboxReadFilter.labelRes: Int
    @StringRes get() = when (this) {
        InboxReadFilter.UNREAD -> R.string.inbox_filter_unread
        InboxReadFilter.READ -> R.string.inbox_filter_read
        InboxReadFilter.ALL -> R.string.common_all
    }

/** 空态整句（中/繁/英语序不同，不能用"没有 + 筛选词 + 通知"拼接，必须整句建 key） */
private val InboxReadFilter.emptyStateRes: Int
    @StringRes get() = when (this) {
        InboxReadFilter.UNREAD -> R.string.inbox_empty_unread
        InboxReadFilter.READ -> R.string.inbox_empty_read
        InboxReadFilter.ALL -> R.string.inbox_empty_all
    }

@Composable
private fun NotificationRow(notification: InboxNotification, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(subjectTypeIcon(notification.subjectType), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(notification.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(
                "${notification.repoOwner}/${notification.repoName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (notification.isUnread) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(8.dp)) {}
        }
    }
}

private fun subjectTypeIcon(type: String): ImageVector = when (type) {
    "Issue" -> Icons.Default.Adjust
    "PullRequest" -> Icons.AutoMirrored.Default.CallSplit
    "Discussion" -> Icons.Default.ChatBubble
    "Release" -> Icons.Default.Sell
    else -> Icons.Default.Circle // Commit/CheckSuite 等其它类型，先用一个通用图标兜底
}
