package com.gitmob.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight,
) {
    val destructive = contentColor == MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 76.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (destructive) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (destructive) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (destructive) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailingIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun SelectionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun SettingsDropdownSection(
    title: String,
    currentValue: String,
    icon: ImageVector? = null,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    SettingsSection(title = title) {
        SettingsDropdownRow(title, currentValue, icon, content = content)
    }
}

@Composable
internal fun SettingsDropdownRow(
    title: String,
    currentValue: String,
    icon: ImageVector? = null,
    onClosed: (() -> Unit)? = null,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var menuRemoved by remember { mutableStateOf(false) }
    val latestOnClosed by rememberUpdatedState(onClosed)

    fun requestClose() {
        closing = true
        expanded = false
    }

    // Keep the callback outside the popup's disposal. If the row itself leaves composition,
    // this effect is cancelled instead of applying a pending selection from a destroyed page.
    LaunchedEffect(menuRemoved) {
        if (menuRemoved) {
            menuRemoved = false
            if (closing) {
                closing = false
                latestOnClosed?.invoke()
            }
        }
    }

    Box {
        SettingsNavigationRow(
            title = title,
            summary = currentValue,
            onClick = { if (!closing) expanded = true },
            icon = icon,
            trailingIcon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        )
        // The popup keeps option lists out of the settings page's measured height.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { requestClose() },
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).selectableGroup(),
            shape = RoundedCornerShape(20.dp),
        ) {
            // Material3 1.4.0 retains this content throughout its exit transition. Observe
            // removal only; onDispose itself is not a general animation-completion callback.
            DisposableEffect(Unit) {
                onDispose { menuRemoved = true }
            }
            content { requestClose() }
        }
    }
}
