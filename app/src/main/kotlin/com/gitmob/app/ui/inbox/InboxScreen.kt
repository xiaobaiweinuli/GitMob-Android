package com.gitmob.app.ui.inbox

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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.data.model.InboxNotification

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
                title = { Text("收件箱") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilterChip(
                    selected = !state.showAll,
                    onClick = { if (state.showAll) viewModel.toggleShowAll() },
                    label = { Text("未读") },
                )
                FilterChip(
                    selected = state.showAll,
                    onClick = { if (!state.showAll) viewModel.toggleShowAll() },
                    label = { Text("全部") },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

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
                            Text("加载失败")
                            Button(
                                onClick = viewModel::retry,
                                modifier = Modifier.padding(top = 12.dp),
                            ) { Text("重试") }
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
                                                "没有${if (state.showAll) "" else "未读"}通知",
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
