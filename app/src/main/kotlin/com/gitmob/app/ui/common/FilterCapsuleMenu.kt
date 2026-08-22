package com.gitmob.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

/**
 * 胶囊筛选菜单族：
 * - [FilterCapsuleMenu]：单选下拉胶囊；未筛选时可显示维度名，选中后显示完整值；
 * - [FilterMultiCapsuleMenu]：多选勾选胶囊（标签类），未选显示维度名，已选显示「已选 N」。
 *
 * 二者共用文件内私有外壳 [FilterCapsuleSurface]：
 * 背景 [MaterialTheme.colorScheme.primaryContainer]，随主题（浅色/深色/种子色/调色板/动态取色）变化。
 * 胶囊最大宽度为 280dp，长文字通过换行完整显示，不使用省略号。
 * 单选 options/多选 options 为调用方的动态数据（里程碑标题、用户 login 等）。
 */
@Composable
fun <T> FilterCapsuleMenu(
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    filterLabel: String,
    modifier: Modifier = Modifier,
    neutralLabel: String? = null,
    isNeutral: (T) -> Boolean = { false },
    optionContent: (@Composable (T) -> Unit)? = null,
    selectedContent: (@Composable (T) -> Unit)? = null,
) {
    val selectedOptionLabel = optionLabel(selected)
    val selectedLabel = if (neutralLabel != null && isNeutral(selected)) neutralLabel else selectedOptionLabel
    FilterCapsuleSurface(
        label = selectedLabel,
        selectedText = selectedOptionLabel,
        filterLabel = filterLabel,
        labelContent = selectedContent?.let { content -> { content(selected) } },
        modifier = modifier,
    ) { dismiss ->
        options.forEach { option ->
            DropdownMenuItem(
                text = { optionContent?.invoke(option) ?: Text(optionLabel(option)) },
                onClick = {
                    dismiss()
                    onSelected(option)
                },
                leadingIcon = {
                    if (option == selected) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                },
            )
        }
    }
}

@Composable
fun FilterMultiCapsuleMenu(
    selected: Set<String>,
    options: List<String>,
    emptyLabel: String,
    @StringRes selectedCountRes: Int,
    clearLabel: String,
    onSelect: (Set<String>) -> Unit,
    filterLabel: String,
    modifier: Modifier = Modifier,
) {
    val label = if (selected.isEmpty()) emptyLabel else stringResource(selectedCountRes, selected.size)
    FilterCapsuleSurface(
        label = if (selected.isEmpty()) filterLabel else label,
        selectedText = label,
        filterLabel = filterLabel,
        modifier = modifier,
    ) { dismiss ->
        DropdownMenuItem(
            text = { Text(clearLabel) },
            onClick = {
                dismiss()
                onSelect(emptySet())
            },
        )
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option) },
                onClick = {
                    onSelect(if (option in selected) selected - option else selected + option)
                },
                leadingIcon = { Checkbox(checked = option in selected, onCheckedChange = null) },
            )
        }
    }
}

/**
 * 胶囊外壳：圆形主题容器 + 气泡弹层锚点。单选/多选各自向 [content] 提供菜单项；
 * 需要关闭菜单的操作通过 [content] 的 [dismiss] 回调显式收起弹层。
 */
@Composable
private fun FilterCapsuleSurface(
    label: String,
    selectedText: String,
    filterLabel: String,
    labelContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectDescription = stringResource(R.string.work_select_filter_value, filterLabel, selectedText)
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = selectDescription },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f, fill = false)) {
                    labelContent?.invoke() ?: Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}
