@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repos

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.ui.theme.*

// ─── 星标模式顶栏按钮 ──────────────────────────────────────────────────────────
@Composable
fun StarModeToggleButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = if (active) "退出星标模式" else "进入星标模式",
            tint = if (active) Yellow else LocalGmColors.current.textSecondary,
        )
    }
}

// ─── "我的列表" 管理 Header ────────────────────────────────────────────────────
@Composable
fun UserListsHeader(
    lists: List<UserList>,
    loading: Boolean,
    expanded: Boolean,
    selectedListId: String?,
    onToggleExpand: () -> Unit,
    onSelectList: (String?) -> Unit,
    onCreate: (() -> Unit)?,
    onEdit: ((UserList) -> Unit)?,
    onDelete: ((UserList) -> Unit)?,
    c: GmColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard)
    ) {
        // ── Header 行 ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = Yellow, modifier = Modifier.size(18.dp))
            Text(
                text = if (selectedListId != null)
                    lists.find { it.id == selectedListId }?.name ?: "我的列表"
                else "我的列表",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Yellow)
            } else {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = c.textTertiary, modifier = Modifier.size(18.dp),
                )
            }
            // 添加列表按钮（仅自己的列表可见）
            if (onCreate != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(CoralDim, RoundedCornerShape(6.dp))
                        .clickable(onClick = onCreate),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, null, tint = Coral, modifier = Modifier.size(16.dp))
                }
            }
        }

        // ── 展开的列表 ─────────────────────────────────────────────────────
        if (expanded) {
            HorizontalDivider(color = c.border, thickness = 0.5.dp)

            // 全部星标入口
            UserListItem(
                name = "全部星标",
                description = "",
                isPrivate = false,
                itemCount = -1,
                selected = selectedListId == null,
                onClick = { onSelectList(null) },
                onEdit = null,
                onDelete = null,
                c = c,
            )

            lists.forEach { list ->
                // 独立的删除确认状态，每个列表项各自管理
                var showDeleteConfirm by remember(list.id) { mutableStateOf(false) }

                val dismissState = rememberSwipeToDismissBoxState()

                LaunchedEffect(dismissState.targetValue) {
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                        showDeleteConfirm = true
                        dismissState.reset()
                    }
                }

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        val color by animateColorAsState(
                            if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) Color(0xFFD32F2F)
                            else Color.Transparent, label = "swipe_bg"
                        )
                        Box(
                            Modifier.fillMaxSize().background(color),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(end = 20.dp),
                                ) {
                                    Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Text("删除", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    },
                ) {
                    UserListItem(
                        name = list.name,
                        description = list.description,
                        isPrivate = list.isPrivate,
                        itemCount = list.itemCount,
                        selected = selectedListId == list.id,
                        onClick = { onSelectList(list.id) },
                        onEdit   = if (onEdit != null) { { onEdit(list) } } else null,
                        onDelete = if (onDelete != null) { { showDeleteConfirm = true } } else null,
                        c = c,
                    )
                }
                HorizontalDivider(color = c.border, thickness = 0.3.dp, modifier = Modifier.padding(start = 48.dp))

                // ── 删除确认弹窗 ────────────────────────────────────────────
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        containerColor = c.bgCard,
                        icon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFD32F2F)) },
                        title = { Text("删除列表？", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
                        text = {
                            Text(
                                "确定要删除「${list.name}」吗？列表内的仓库不会被删除，只是移出该分类。",
                                color = c.textSecondary, fontSize = 13.sp,
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { onDelete?.invoke(list); showDeleteConfirm = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            ) { Text("删除") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) {
                                Text("取消", color = c.textSecondary)
                            }
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = c.border, thickness = 0.5.dp)
    }
}

@Composable
private fun UserListItem(
    name: String,
    description: String,
    isPrivate: Boolean,
    itemCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    c: GmColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) CoralDim else c.bgCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (isPrivate) Icons.Default.Lock else Icons.Default.Star,
            null,
            tint = if (selected) Coral else Yellow,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, color = if (selected) Coral else c.textPrimary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) {
                Text(description, fontSize = 11.sp, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (itemCount >= 0) {
            Text("$itemCount", fontSize = 11.sp, color = c.textTertiary)
        }
        if (isPrivate) {
            Text("私有", fontSize = 9.sp, color = c.textTertiary,
                modifier = Modifier.background(c.bgItem, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Edit, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ─── 创建/编辑列表弹窗 ────────────────────────────────────────────────────────
@Composable
fun UserListDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    initialIsPrivate: Boolean = false,
    c: GmColors,
    onConfirm: (name: String, description: String, isPrivate: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var isPrivate by remember { mutableStateOf(initialIsPrivate) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Coral, unfocusedBorderColor = c.border,
        focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
        focusedContainerColor = c.bgItem, unfocusedContainerColor = c.bgItem,
        focusedLabelColor = Coral, unfocusedLabelColor = c.textTertiary,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        title = { Text(title, color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("列表名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors,
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    maxLines = 4,
                    colors = fieldColors,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(8.dp))
                        .clickable { isPrivate = !isPrivate }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = isPrivate, onCheckedChange = { isPrivate = it },
                        colors = CheckboxDefaults.colors(checkedColor = Coral),
                    )
                    Column {
                        Text("私有列表", fontSize = 13.sp, color = c.textPrimary)
                        Text("仅自己可见", fontSize = 11.sp, color = c.textTertiary)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), description.trim(), isPrivate) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

// ─── 仓库分类管理 BottomSheet ─────────────────────────────────────────────────
@Composable
fun RepoListClassifySheet(
    repo: StarredRepo,
    userLists: List<UserList>,
    c: GmColors,
    onUpdate: (newListIds: List<String>) -> Unit,
    onCreateList: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地勾选状态（初始化为当前仓库所属列表）
    val checked = remember(repo.nodeId, userLists) {
        mutableStateMapOf<String, Boolean>().apply {
            userLists.forEach { list -> put(list.id, list.id in repo.listIds) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            // 关闭时提交变更
            val newIds = userLists.filter { checked[it.id] == true }.map { it.id }
            if (newIds != repo.listIds) onUpdate(newIds)
            onDismiss()
        },
        containerColor = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
        ) {
            // 标题
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Star, null, tint = Yellow, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("添加到列表", fontSize = 15.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text(repo.nameWithOwner, fontSize = 11.sp, color = c.textTertiary, maxLines = 1)
                }
            }
            HorizontalDivider(color = c.border)

            if (userLists.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("暂无列表，请先创建", color = c.textTertiary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(userLists, key = { it.id }) { list ->
                        val isChecked = checked[list.id] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checked[list.id] = !isChecked }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked[list.id] = it },
                                colors = CheckboxDefaults.colors(checkedColor = Coral),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(list.name, fontSize = 13.sp, color = c.textPrimary, fontWeight = FontWeight.Medium)
                                    if (list.isPrivate) {
                                        Icon(Icons.Default.Lock, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
                                    }
                                }
                                if (list.description.isNotBlank()) {
                                    Text(list.description, fontSize = 11.sp, color = c.textTertiary, maxLines = 1)
                                }
                            }
                            Text("${list.itemCount}", fontSize = 11.sp, color = c.textTertiary)
                        }
                        HorizontalDivider(color = c.border, thickness = 0.3.dp, modifier = Modifier.padding(start = 60.dp))
                    }
                }
            }

            HorizontalDivider(color = c.border)
            // 底部：新建列表入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCreateList)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Add, null, tint = Coral, modifier = Modifier.size(18.dp))
                Text("创建新列表", fontSize = 14.sp, color = Coral, fontWeight = FontWeight.Medium)
            }
            // 提交按钮
            Button(
                onClick = {
                    val newIds = userLists.filter { checked[it.id] == true }.map { it.id }
                    if (newIds != repo.listIds) onUpdate(newIds)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("完成") }
        }
    }
}
