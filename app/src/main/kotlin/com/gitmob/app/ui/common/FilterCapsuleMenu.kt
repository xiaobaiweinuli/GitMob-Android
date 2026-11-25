package com.gitmob.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CheckableDropdownMenuItem
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

/** Single-select filter menu. Neutral values remain unselected; long labels wrap within 280dp. */
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
        selected = !isNeutral(selected),
        selectedText = selectedOptionLabel,
        filterLabel = filterLabel,
        labelContent = selectedContent?.let { content -> { content(selected) } },
        modifier = modifier,
    ) { dismiss ->
        options.forEachIndexed { index, option ->
            SelectableDropdownMenuItem(
                selected = option == selected,
                shapes = MenuDefaults.itemShape(index, options.size),
                text = { optionContent?.invoke(option) ?: Text(optionLabel(option)) },
                onClick = {
                    dismiss()
                    onSelected(option)
                },
                leadingIcon = { Spacer(Modifier.size(24.dp)) },
                selectedLeadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
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
        selected = selected.isNotEmpty(),
        selectedText = label,
        filterLabel = filterLabel,
        modifier = modifier,
    ) { dismiss ->
        DropdownMenuItem(
            shape = MenuDefaults.itemShape(0, options.size + 1).shape,
            leadingIcon = { Spacer(Modifier.size(24.dp)) },
            text = { Text(clearLabel) },
            onClick = {
                dismiss()
                onSelect(emptySet())
            },
        )
        options.forEachIndexed { index, option ->
            CheckableDropdownMenuItem(
                checked = option in selected,
                shapes = MenuDefaults.itemShape(index + 1, options.size + 1),
                text = { Text(option) },
                onCheckedChange = { checked ->
                    onSelect(if (checked) selected + option else selected - option)
                },
                leadingIcon = { Checkbox(checked = option in selected, onCheckedChange = null) },
            )
        }
    }
}

/** Shared chip anchor. The menu controls whether a selection dismisses the popup. */
@Composable
private fun FilterCapsuleSurface(
    label: String,
    selected: Boolean,
    selectedText: String,
    filterLabel: String,
    modifier: Modifier = Modifier,
    labelContent: (@Composable () -> Unit)? = null,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterMenuChip(
            label = label,
            selected = selected,
            expanded = expanded,
            selectedText = selectedText,
            filterLabel = filterLabel,
            onClick = { expanded = true },
            labelContent = labelContent,
        )
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                content { expanded = false }
            }
        }
    }
}

/** Chip anchor shared by flat and nested selection menus. */
@Composable
internal fun FilterMenuChip(
    label: String,
    selected: Boolean,
    expanded: Boolean,
    selectedText: String,
    filterLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelContent: (@Composable () -> Unit)? = null,
) {
    val motionScheme = MaterialTheme.motionScheme
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motionScheme.fastSpatialSpec(),
        label = "Filter menu arrow",
    )
    val selectDescription = stringResource(R.string.work_select_filter_value, filterLabel, selectedText)
    FilterChip(
        selected = selected,
        onClick = onClick,
        shapes = FilterChipDefaults.shapes(),
        modifier = modifier
            .animateContentSize(motionScheme.defaultSpatialSpec(), alignment = Alignment.CenterStart)
            .widthIn(max = 280.dp)
            .semantics { contentDescription = selectDescription },
        label = { labelContent?.invoke() ?: Text(label) },
        trailingIcon = {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(arrowRotation),
            )
        },
    )
}
