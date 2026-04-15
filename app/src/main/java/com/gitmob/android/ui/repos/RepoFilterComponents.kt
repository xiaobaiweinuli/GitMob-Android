@file:OptIn(ExperimentalMaterial3Api::class)

package com.gitmob.android.ui.repos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.LanguageEntry
import com.gitmob.android.util.LanguageManager

/**
 * 远程仓库筛选工具栏组件
 */
@Composable
fun RepoFilterToolbar(
    state: RepoListState,
    c: GmColors,
    vm: RepoListViewModel,
) {
    val context = LocalContext.current
    var showTypeDropdown by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    val hasFilters = hasAnyRepoFiltersApplied(state.filterState)
    // 从当前已加载仓库提取出现的语言
    val repoLanguages = remember(state.repos) {
        state.repos.mapNotNull { it.language }.toSet()
    }
    // 构建语言列表（本地数据优先，仓库已出现的置顶）
    val allLanguages = remember(repoLanguages) {
        LanguageManager.buildLanguageList(context, repoLanguages)
    }

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
                    onClick = { vm.clearFilters() },
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
                        text = state.filterState.typeFilter.displayName,
                        isActive = state.filterState.typeFilter != RepoTypeFilter.ALL,
                        c = c,
                        onClick = { showTypeDropdown = true }
                    )
                    DropdownMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false },
                        modifier = Modifier.background(c.bgCard)
                    ) {
                        RepoTypeFilter.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = state.filterState.typeFilter == type,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Coral)
                                        )
                                        Text(type.displayName, fontSize = 13.sp, color = c.textPrimary)
                                    }
                                },
                                onClick = {
                                    vm.setTypeFilter(type)
                                    showTypeDropdown = false
                                }
                            )
                        }
                    }
                }

                FilterButton(
                    text = state.filterState.sortBy.displayName,
                    isActive = state.filterState.sortBy != RepoSortBy.PUSHED_DESC,
                    c = c,
                    onClick = { showSortSheet = true }
                )

                if (allLanguages.isNotEmpty()) {
                    FilterButton(
                        text = state.filterState.languageFilter?.name ?: "语言",
                        isActive = state.filterState.languageFilter != null,
                        c = c,
                        onClick = { showLanguageSheet = true }
                    )
                }
            }
        }
    }

    if (showSortSheet) {
        RepoFilterBottomSheet(
            title = "排序方式",
            c = c,
            onDismiss = { showSortSheet = false }
        ) {
            RepoSortBy.values().forEach { sortBy ->
                val isSelected = state.filterState.sortBy == sortBy
                FilterOptionItem(
                    text = sortBy.displayName,
                    isSelected = isSelected,
                    isRadio = true,
                    c = c,
                    onClick = {
                        vm.setSortBy(sortBy)
                        showSortSheet = false
                    }
                )
            }
        }
    }

    if (showLanguageSheet && allLanguages.isNotEmpty()) {
        RepoFilterBottomSheet(
            title = "选择语言",
            c = c,
            onDismiss = { showLanguageSheet = false }
        ) {
            LanguageFilterOptionItem(
                entry = null,
                label = "全部语言",
                isSelected = state.filterState.languageFilter == null,
                c = c,
                onClick = { vm.setLanguageFilter(null); showLanguageSheet = false }
            )
            allLanguages.forEach { entry ->
                val isSelected = state.filterState.languageFilter?.id == entry.id
                LanguageFilterOptionItem(
                    entry = entry,
                    label = entry.name,
                    isSelected = isSelected,
                    c = c,
                    onClick = { vm.setLanguageFilter(entry); showLanguageSheet = false }
                )
            }
        }
    }
}

/**
 * 标星仓库筛选工具栏组件
 */
@Composable
fun StarFilterToolbar(
    state: StarListState,
    c: GmColors,
    vm: StarListViewModel,
) {
    val context = LocalContext.current
    var showTypeDropdown by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    val hasFilters = hasAnyStarFiltersApplied(state.filterState)
    val repoLanguages = remember(state.starredRepos) {
        state.starredRepos.mapNotNull { it.language }.toSet()
    }
    val allLanguages = remember(repoLanguages) {
        LanguageManager.buildLanguageList(context, repoLanguages)
    }

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
                    onClick = { vm.clearStarFilters() },
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
                        text = state.filterState.typeFilter.displayName,
                        isActive = state.filterState.typeFilter != RepoTypeFilter.ALL,
                        c = c,
                        onClick = { showTypeDropdown = true }
                    )
                    DropdownMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false },
                        modifier = Modifier.background(c.bgCard)
                    ) {
                        RepoTypeFilter.values().forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        RadioButton(
                                            selected = state.filterState.typeFilter == type,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Coral)
                                        )
                                        Text(type.displayName, fontSize = 13.sp, color = c.textPrimary)
                                    }
                                },
                                onClick = {
                                    vm.setStarTypeFilter(type)
                                    showTypeDropdown = false
                                }
                            )
                        }
                    }
                }

                FilterButton(
                    text = state.filterState.sortBy.displayName,
                    isActive = state.filterState.sortBy != StarSortBy.STARRED_DESC,
                    c = c,
                    onClick = { showSortSheet = true }
                )

                if (allLanguages.isNotEmpty()) {
                    FilterButton(
                        text = state.filterState.languageFilter?.name ?: "语言",
                        isActive = state.filterState.languageFilter != null,
                        c = c,
                        onClick = { showLanguageSheet = true }
                    )
                }
            }
        }
    }

    if (showSortSheet) {
        RepoFilterBottomSheet(
            title = "排序方式",
            c = c,
            onDismiss = { showSortSheet = false }
        ) {
            StarSortBy.values().forEach { sortBy ->
                val isSelected = state.filterState.sortBy == sortBy
                FilterOptionItem(
                    text = sortBy.displayName,
                    isSelected = isSelected,
                    isRadio = true,
                    c = c,
                    onClick = {
                        vm.setStarSortBy(sortBy)
                        showSortSheet = false
                    }
                )
            }
        }
    }

    if (showLanguageSheet && allLanguages.isNotEmpty()) {
        RepoFilterBottomSheet(
            title = "选择语言",
            c = c,
            onDismiss = { showLanguageSheet = false }
        ) {
            LanguageFilterOptionItem(
                entry = null,
                label = "全部语言",
                isSelected = state.filterState.languageFilter == null,
                c = c,
                onClick = { vm.setStarLanguageFilter(null); showLanguageSheet = false }
            )
            allLanguages.forEach { entry ->
                val isSelected = state.filterState.languageFilter?.id == entry.id
                LanguageFilterOptionItem(
                    entry = entry,
                    label = entry.name,
                    isSelected = isSelected,
                    c = c,
                    onClick = { vm.setStarLanguageFilter(entry); showLanguageSheet = false }
                )
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
            Icon(
                Icons.Default.ExpandMore,
                null,
                modifier = Modifier.size(16.dp),
                tint = if (isActive) Coral else c.textTertiary
            )
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = Coral,
                            shape = androidx.compose.foundation.shape.CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        count.toString(),
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/**
 * 筛选底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoFilterBottomSheet(
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
            HorizontalDivider(color = c.border)
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

/**
 * 语言选项条目：带颜色小圆点
 * entry = null 时显示"全部语言"（无颜色点）
 */
@Composable
fun LanguageFilterOptionItem(
    entry: LanguageEntry?,
    label: String,
    isSelected: Boolean,
    c: GmColors,
    onClick: () -> Unit,
) {
    // 解析 color 字符串 → Compose Color
    val dotColor: Color = remember(entry?.color) {
        entry?.color?.let { hex ->
            try { Color(android.graphics.Color.parseColor(hex)) }
            catch (_: Exception) { Color.Black }
        } ?: Color.Black
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Coral),
        )
        // 颜色小圆点（始终显示，颜色为 null 时显示黑色）
        if (entry != null) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape),
            )
        }
        Text(label, fontSize = 13.sp, color = c.textPrimary, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
