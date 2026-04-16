@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.gitmob.android.api.*
import com.gitmob.android.ui.common.*
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LogManager
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.defaultMinSize
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * 判断是否有任何筛选条件被应用
 */
fun hasAnyFiltersApplied(filterState: IssueFilterState): Boolean {
    return filterState.status != IssueStatusFilter.OPEN ||
           filterState.sortBy != IssueSortBy.CREATED_DESC ||
           filterState.selectedLabels.isNotEmpty() ||
           filterState.selectedCreator != null
}

/**
 * Issue筛选工具栏组件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueFilterToolbar(
    state: RepoDetailState,
    c: GmColors,
    vm: RepoDetailViewModel,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onAddIssueClick: () -> Unit = {},
) {
    var showStatusDropdown by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showLabelsSheet by remember { mutableStateOf(false) }
    var showAuthorsSheet by remember { mutableStateOf(false) }

    val hasFilters = hasAnyFiltersApplied(state.issueFilterState)
    val allLabels = vm.getAllLabels().sorted()
    val allAuthors = vm.getAllAuthors().sorted()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasFilters) {
                TextButton(
                    onClick = { 
                        vm.clearIssueFilters()
                        vm.loadIssuesByState(forceRefresh = true)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = RedColor)
                ) {
                    Text("清除", fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FilterButton(
                        text = state.issueFilterState.status.displayName,
                        isActive = state.issueFilterState.status != IssueStatusFilter.OPEN,
                        c = c,
                        onClick = { showStatusDropdown = true }
                    )
                    DropdownMenu(
                        expanded = showStatusDropdown,
                        onDismissRequest = { showStatusDropdown = false },
                        modifier = Modifier.background(c.bgCard)
                    ) {
                        IssueStatusFilter.values().forEach { status ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = state.issueFilterState.status == status,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Coral)
                                        )
                                        Text(status.displayName, fontSize = 13.sp, color = c.textPrimary)
                                    }
                                },
                                onClick = {
                                    vm.setIssueStatusFilter(status)
                                    showStatusDropdown = false
                                }
                            )
                        }
                    }
                }

                FilterButton(
                    text = state.issueFilterState.sortBy.displayName,
                    isActive = state.issueFilterState.sortBy != IssueSortBy.CREATED_DESC,
                    c = c,
                    onClick = { showSortSheet = true }
                )

                FilterButton(
                    text = "标签",
                    isActive = state.issueFilterState.selectedLabels.isNotEmpty(),
                    count = state.issueFilterState.selectedLabels.size,
                    c = c,
                    onClick = { showLabelsSheet = true }
                )

                FilterButton(
                    text = "作者",
                    isActive = state.issueFilterState.selectedCreator != null,
                    c = c,
                    onClick = { showAuthorsSheet = true }
                )
            }

            IconButton(
                onClick = onAddIssueClick,
                modifier = Modifier.size(36.dp),
                enabled = !isArchived
            ) {
                Icon(
                    Icons.Default.Add,
                    null,
                    tint = if (isArchived) c.textTertiary else Coral,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    if (showSortSheet) {
        IssueFilterBottomSheet(
            title = "排序方式",
            c = c,
            onDismiss = { showSortSheet = false }
        ) {
            IssueSortBy.values().forEach { sortBy ->
                val isSelected = state.issueFilterState.sortBy == sortBy
                FilterOptionItem(
                    text = sortBy.displayName,
                    isSelected = isSelected,
                    isRadio = true,
                    c = c,
                    onClick = {
                        vm.setIssueSortBy(sortBy)
                        showSortSheet = false
                    }
                )
            }
        }
    }

    if (showLabelsSheet) {
        IssueFilterBottomSheet(
            title = "选择标签",
            c = c,
            onDismiss = { showLabelsSheet = false }
        ) {
            if (allLabels.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无标签", fontSize = 13.sp, color = c.textTertiary)
                }
            } else {
                allLabels.forEach { label ->
                    val isSelected = state.issueFilterState.selectedLabels.contains(label)
                    FilterOptionItem(
                        text = label,
                        isSelected = isSelected,
                        isRadio = false,
                        c = c,
                        onClick = { vm.toggleIssueLabel(label) }
                    )
                }
            }
        }
    }

    if (showAuthorsSheet) {
        IssueFilterBottomSheet(
            title = "选择作者",
            c = c,
            onDismiss = { showAuthorsSheet = false }
        ) {
            val currentCreator = state.issueFilterState.selectedCreator
            FilterOptionItem(
                text = "全部",
                isSelected = currentCreator == null,
                isRadio = true,
                c = c,
                onClick = {
                    vm.setIssueCreator(null)
                    showAuthorsSheet = false
                }
            )
            if (allAuthors.isNotEmpty()) {
                allAuthors.forEach { author ->
                    val isSelected = currentCreator == author
                    FilterOptionItem(
                        text = author,
                        isSelected = isSelected,
                        isRadio = true,
                        c = c,
                        onClick = {
                            vm.setIssueCreator(author)
                            showAuthorsSheet = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 筛选按钮组件
 */
@Composable
fun FilterButton(
    text: String,
    isActive: Boolean,
    c: GmColors,
    count: Int = 0,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (isActive) Coral.copy(alpha = 0.15f) else c.bgItem,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text,
                fontSize = 13.sp,
                color = if (isActive) Coral else c.textPrimary
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = Coral,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        count.toString(),
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/**
 * Issue筛选底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueFilterBottomSheet(
    title: String,
    c: GmColors,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontSize = 16.sp,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            GmDivider()
            content()
        }
    }
}

/**
 * 筛选选项项
 */
@Composable
fun FilterOptionItem(
    text: String,
    isSelected: Boolean,
    isRadio: Boolean,
    c: GmColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Coral)
        )
        Text(text, fontSize = 13.sp, color = c.textPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesTab(
    state: RepoDetailState, 
    c: GmColors, 
    vm: RepoDetailViewModel,
    permission: RepoPermission,
    isArchived: Boolean = false,
    onRefresh: () -> Unit = {},
    onIssueClick: (Int) -> Unit = {},
) {
    var showCreateIssueDialog by remember { mutableStateOf(false) }
    var templatesLoading by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 滚动到底部时加载更多
    val isAtBottom = remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last != null && last.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(isAtBottom.value) {
        if (isAtBottom.value && state.issuesHasMore && !state.issuesLoadingMore) {
            vm.loadMoreIssues()
        }
    }

    PullToRefreshBox(
        isRefreshing = state.issuesRefreshing,
        onRefresh = onRefresh,
    ) {
        Box {
            Column {
                IssueFilterToolbar(
                    state = state,
                    c = c,
                    vm = vm,
                    permission = permission,
                    isArchived = isArchived,
                    onAddIssueClick = {
                        // 先加载模板，加载完成/失败后再打开弹窗
                        templatesLoading = true
                        vm.loadIssueTemplates {
                            templatesLoading = false
                            showCreateIssueDialog = true
                        }
                    }
                )

                val issuesList = state.issues.filter { !it.isPR }
                if (issuesList.isEmpty() && !state.issuesRefreshing) {
                    EmptyBox("暂无 Issues")
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(issuesList, key = { it.number }) { issue ->
                            SwipeableIssueCard(
                                issue = issue,
                                c = c,
                                permission = permission,
                                onDelete = { vm.deleteIssue(issue.number) },
                                onClick = { onIssueClick(issue.number) }
                            )
                        }
                        // 加载更多 footer
                        if (state.issuesLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Coral)
                                }
                            }
                        }
                    }
                }
            }

            // 模板加载中指示（在整个内容上覆盖显示）
            if (templatesLoading) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Coral, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                }
            }
        }
    }
    if (showCreateIssueDialog) {
        CreateIssueDialog(
            c = c,
            templates = state.issueTemplates,
            templatesLoading = state.issueTemplatesLoading,
            onConfirm = { title, body ->
                vm.createIssue(title, body)
                showCreateIssueDialog = false
            },
            onDismiss = { showCreateIssueDialog = false }
        )
    }
}

/**
 * 创建Issue底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateIssueDialog(
    c: GmColors,
    templates: List<IssueTemplate> = emptyList(),
    templatesLoading: Boolean = false,
    onConfirm: (title: String, body: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // ── 阶段：选模板 or 直接填写 ──
    var selectedTemplate by remember { mutableStateOf<IssueTemplate?>(null) }
    var phase by remember { mutableStateOf(if (templates.isEmpty()) "edit" else "pick") }

    // ── 公共字段 ──
    var title by remember { mutableStateOf("") }
    var body  by remember { mutableStateOf("") }

    // ── YAML Forms 字段状态（mutableStateMap 保存每个字段的当前值） ──
    // key = field.id, value = String (input/textarea/dropdown) | Set<Int> (checkboxes)
    val fieldValues = remember { mutableStateMapOf<String, Any>() }

    // 选择模板后初始化字段默认值
    fun applyTemplate(tmpl: IssueTemplate) {
        selectedTemplate = tmpl
        title = tmpl.title
        body  = if (!tmpl.isForm) tmpl.body else ""
        fieldValues.clear()
        tmpl.fields.forEach { f ->
            when (f) {
                is IssueField.InputField    -> fieldValues[f.id] = f.value
                is IssueField.TextareaField -> fieldValues[f.id] = f.value
                is IssueField.DropdownField -> fieldValues[f.id] = if (f.multiple) emptySet<Int>() else -1
                is IssueField.CheckboxesField -> fieldValues[f.id] =
                    f.options.indices.filter { f.options[it].checked }.toMutableSet()
                else -> Unit
            }
        }
        phase = "edit"
    }

    // 将 YAML Forms 字段值拼装成 Markdown body
    fun buildFormBody(tmpl: IssueTemplate): String = buildString {
        tmpl.fields.forEach { f ->
            when (f) {
                is IssueField.MarkdownField  -> { appendLine(f.value); appendLine() }
                is IssueField.InputField -> {
                    appendLine("### ${f.label}")
                    appendLine(fieldValues[f.id]?.toString()?.ifBlank { "_无_" } ?: "_无_")
                    appendLine()
                }
                is IssueField.TextareaField -> {
                    appendLine("### ${f.label}")
                    val v = fieldValues[f.id]?.toString()?.trim()
                    if (f.render.isNotBlank() && !v.isNullOrBlank())
                        appendLine("```${f.render}\n$v\n```")
                    else appendLine(v?.ifBlank { "_无_" } ?: "_无_")
                    appendLine()
                }
                is IssueField.DropdownField -> {
                    appendLine("### ${f.label}")
                    val sel = fieldValues[f.id]
                    val text = when {
                        f.multiple -> (sel as? Set<*>)?.mapNotNull { i -> f.options.getOrNull(i as Int) }
                            ?.joinToString(", ")?.ifBlank { "_未选择_" } ?: "_未选择_"
                        else -> f.options.getOrNull((sel as? Int) ?: -1) ?: "_未选择_"
                    }
                    appendLine(text); appendLine()
                }
                is IssueField.CheckboxesField -> {
                    appendLine("### ${f.label}")
                    val checked = (fieldValues[f.id] as? Set<*>) ?: emptySet<Int>()
                    f.options.forEachIndexed { i, opt ->
                        appendLine("- [${if (i in checked) "x" else " "}] ${opt.label}")
                    }
                    appendLine()
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.PartiallyExpanded },
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = c.bgCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.border) },
        sheetState = sheetState,
        modifier   = Modifier.fillMaxHeight(),
    ) {
        // ── 阶段一：选择模板 ──────────────────────────────────────────
        if (phase == "pick") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选择 Issue 模板", fontSize = 16.sp, color = c.textPrimary,
                        fontWeight = FontWeight.SemiBold)
                    if (templatesLoading)
                        CircularProgressIndicator(modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp, color = Coral)
                }
                GmDivider()

                // 每个模板卡片
                templates.forEach { tmpl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.bgItem, RoundedCornerShape(12.dp))
                            .clickable { applyTemplate(tmpl) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (tmpl.isForm) Icons.AutoMirrored.Filled.Article else Icons.Default.Description,
                            null, tint = Coral, modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tmpl.name, fontSize = 14.sp, color = c.textPrimary,
                                fontWeight = FontWeight.Medium)
                            if (tmpl.about.isNotBlank())
                                Text(tmpl.about, fontSize = 12.sp, color = c.textSecondary,
                                    maxLines = 2)
                        }
                        Icon(Icons.Default.ChevronRight, null,
                            tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }

                // 空白 Issue（不使用模板）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(12.dp))
                        .clickable { phase = "edit" }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Edit, null,
                        tint = c.textTertiary, modifier = Modifier.size(20.dp))
                    Text("空白 Issue", fontSize = 14.sp, color = c.textSecondary,
                        modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null,
                        tint = c.textTertiary, modifier = Modifier.size(18.dp))
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("取消", color = c.textSecondary)
                }
            }
        } else {
            // ── 阶段二：填写 Issue ────────────────────────────────────
            val tmpl = selectedTemplate
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 返回模板选择
                    if (templates.isNotEmpty()) {
                        IconButton(onClick = { phase = "pick"; selectedTemplate = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                        }
                    }
                    Text(
                        if (tmpl != null) tmpl.name else "创建新 Issue",
                        fontSize = 16.sp, color = c.textPrimary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                GmDivider()

                // 标题
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("标题${if (tmpl != null) " *" else ""}") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.bgDeep, unfocusedContainerColor = c.bgDeep,
                        focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                    ),
                )

                // YAML Forms 字段 或 Markdown 正文
                if (tmpl != null && tmpl.isForm) {
                    tmpl.fields.forEach { field ->
                        IssueFieldItem(field = field, c = c, fieldValues = fieldValues)
                    }
                } else {
                    OutlinedTextField(
                        value = body, onValueChange = { body = it },
                        label = { Text("正文") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        minLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = c.bgDeep, unfocusedContainerColor = c.bgDeep,
                            focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalBody = if (tmpl != null && tmpl.isForm)
                                buildFormBody(tmpl) else body
                            onConfirm(title, finalBody)
                        },
                        enabled = title.isNotBlank(),
                        colors  = ButtonDefaults.buttonColors(containerColor = Coral),
                    ) { Text("创建") }
                }
            }
        }
    }
}

/** 单个 YAML Forms 字段渲染 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueFieldItem(
    field: IssueField,
    c: GmColors,
    fieldValues: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Any>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (field) {
            is IssueField.MarkdownField -> {
                if (field.value.isNotBlank())
                    Text(field.value, fontSize = 13.sp, color = c.textSecondary, lineHeight = 20.sp)
            }
            is IssueField.InputField -> {
                val v = fieldValues[field.id]?.toString() ?: field.value
                if (field.label.isNotBlank())
                    FieldLabel(field.label, field.required, c)
                if (field.description.isNotBlank())
                    Text(field.description, fontSize = 11.sp, color = c.textTertiary)
                OutlinedTextField(
                    value = v, onValueChange = { fieldValues[field.id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 5,
                    placeholder = { Text(field.placeholder, color = c.textTertiary, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.bgDeep, unfocusedContainerColor = c.bgDeep,
                        focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                    ),
                )
            }
            is IssueField.TextareaField -> {
                if (field.label.isNotBlank()) FieldLabel(field.label, field.required, c)
                if (field.description.isNotBlank())
                    Text(field.description, fontSize = 11.sp, color = c.textTertiary)
                val v = fieldValues[field.id]?.toString() ?: field.value
                OutlinedTextField(
                    value = v, onValueChange = { fieldValues[field.id] = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), minLines = 3,
                    placeholder = { Text(field.placeholder, color = c.textTertiary, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = c.bgDeep, unfocusedContainerColor = c.bgDeep,
                        focusedBorderColor = Coral, unfocusedBorderColor = c.border,
                    ),
                )
            }
            is IssueField.DropdownField -> {
                if (field.label.isNotBlank()) FieldLabel(field.label, field.required, c)
                if (field.description.isNotBlank())
                    Text(field.description, fontSize = 11.sp, color = c.textTertiary)
                val sel = fieldValues[field.id]
                field.options.forEachIndexed { idx, opt ->
                    val isChecked = if (field.multiple)
                        idx in ((sel as? Set<*>) ?: emptySet<Int>())
                    else idx == (sel as? Int ?: -1)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (field.multiple) {
                                    @Suppress("UNCHECKED_CAST")
                                val cur = (fieldValues[field.id] as? MutableSet<Int>)
                                        ?: mutableSetOf()
                                    if (idx in cur) cur.remove(idx) else cur.add(idx)
                                    fieldValues[field.id] = cur.toMutableSet()
                                } else {
                                    fieldValues[field.id] = if (sel == idx) -1 else idx
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (field.multiple) {
                            Checkbox(checked = isChecked, onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Coral))
                        } else {
                            RadioButton(selected = isChecked, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Coral))
                        }
                        Text(opt, fontSize = 13.sp, color = c.textPrimary)
                    }
                }
            }
            is IssueField.CheckboxesField -> {
                if (field.label.isNotBlank()) FieldLabel(field.label, field.required, c)
                if (field.description.isNotBlank())
                    Text(field.description, fontSize = 11.sp, color = c.textTertiary)
                @Suppress("UNCHECKED_CAST")
                val checked = (fieldValues[field.id] as? MutableSet<Int>) ?: mutableSetOf()
                field.options.forEachIndexed { idx, opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                @Suppress("UNCHECKED_CAST")
                                val cur = (fieldValues[field.id] as? MutableSet<Int>) ?: mutableSetOf()
                                if (idx in cur) cur.remove(idx) else cur.add(idx)
                                fieldValues[field.id] = cur.toMutableSet()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Checkbox(
                            checked = idx in checked, onCheckedChange = null,
                            colors  = CheckboxDefaults.colors(checkedColor = Coral),
                        )
                        Column {
                            Text(opt.label, fontSize = 13.sp, color = c.textPrimary)
                            if (opt.required)
                                Text("必填", fontSize = 10.sp, color = RedColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FieldLabel(label: String, required: Boolean, c: GmColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.textPrimary)
        if (required) Text("*", fontSize = 13.sp, color = RedColor)
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableIssueCard(
    issue: GHIssue,
    c: GmColors,
    permission: RepoPermission,
    onDelete: () -> Unit,
    onClick: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    
    if (permission.isOwner) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            onDismiss = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    showDeleteDialog = true
                }
                scope.launch {
                    dismissState.reset()
                }
            },
            backgroundContent = {
                val color by animateColorAsState(
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) RedColor else c.border,
                    label = "swipe_bg",
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(color, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 20.dp),
                    ) {
                        Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Text("删除", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            },
        ) {
            IssueCardContent(issue = issue, c = c, onClick = onClick)
        }
    } else {
        IssueCardContent(issue = issue, c = c, onClick = onClick)
    }

    if (showDeleteDialog) {
        DeleteIssueConfirmDialog(
            issueNumber = issue.number,
            title = issue.title,
            onConfirm = { onDelete(); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
            c = c,
        )
    }
}

@Composable
fun IssueCardContent(issue: GHIssue, c: GmColors, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.Circle, 
                null,
                tint = if (issue.state == "open") Green else RedColor, 
                modifier = Modifier.size(10.dp),
            )
            Text(
                issue.title, 
                fontSize = 13.sp, 
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        
        if (issue.labels.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                issue.labels.forEach { label ->
                    val labelColor = try {
                        Color(android.graphics.Color.parseColor("#${label.color}"))
                    } catch (_: Exception) {
                        Coral
                    }
                    val textColor = if (isColorLight(labelColor)) Color.Black else Color.White
                    
                    Surface(
                        color = labelColor,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            label.name,
                            fontSize = 10.sp,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "#${issue.number} · ${issue.user.login}",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    "·",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    repoFormatDate(issue.createdAt),
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
            }
            // 评论数量徽章（与 GitHub 网页一致，0条时不显示）
            val commentCount = issue.comments ?: 0
            if (commentCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Comment,
                        contentDescription = "评论数",
                        tint = c.textTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = commentCount.toString(),
                        fontSize = 11.sp,
                        color = c.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteIssueConfirmDialog(
    issueNumber: Int,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    c: GmColors,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bgCard,
        icon = {
            Icon(Icons.Default.DeleteForever, null, tint = RedColor, modifier = Modifier.size(28.dp))
        },
        title = { Text("删除 Issue", color = c.textPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.bgItem, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Report,
                        null,
                        tint = RedColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "#$issueNumber",
                        fontSize = 13.sp,
                        color = c.textPrimary,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    title,
                    fontSize = 13.sp,
                    color = c.textSecondary,
                )
                Text(
                    "此操作不可撤销。确认要删除这个 Issue 吗？",
                    fontSize = 13.sp,
                    color = c.textTertiary,
                    lineHeight = 20.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RedColor),
            ) {
                Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) }
        },
    )
}

// ─── Releases Tab ─────────────────────────────────────────────────────

/**
 * 发行版标签页组件
 * 
 * @param state 仓库详情状态
 * @param c 颜色主题
 * @param onRefresh 刷新回调
 */
