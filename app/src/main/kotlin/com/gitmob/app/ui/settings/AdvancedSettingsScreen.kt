package com.gitmob.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.HistoryLimitResult
import com.gitmob.app.ui.library.*

@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit, viewModel: AccountLibraryViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pending by viewModel.pendingSettings.collectAsStateWithLifecycle()
    val count = if (settings.value?.historyEnabled == true) {
        val value by viewModel.historyCount.collectAsStateWithLifecycle()
        value
    } else HistoryCountState()
    AdvancedSettingsContent(settings, count, pending, onBack, viewModel::retrySettings, viewModel::retryHistoryCount,
        viewModel::favoritesEnabled, viewModel::historyEnabled, viewModel::historyLimit, viewModel::clearHistory)
}

@Composable
internal fun AdvancedSettingsContent(
    settingsState: LibrarySettingsState, count: HistoryCountState, pending: Set<LibrarySettingOperation>,
    back: () -> Unit, retry: () -> Unit, retryCount: () -> Unit,
    favorites: (Boolean) -> Unit, history: (Boolean) -> Unit,
    limit: (Int, Boolean, (HistoryLimitResult) -> Unit) -> Unit, clear: (() -> Unit) -> Unit,
) {
    val settings = settingsState.value
    val limitBusy = LibrarySettingOperation.Limit in pending
    val clearBusy = LibrarySettingOperation.Clear in pending
    var showLimit by rememberSaveable { mutableStateOf(false) }
    var limitText by rememberSaveable { mutableStateOf("") }
    var trimLimit by rememberSaveable { mutableStateOf<Int?>(null) }
    var trimCount by rememberSaveable { mutableStateOf(0) }
    var showClear by rememberSaveable { mutableStateOf(false) }
    LibraryScaffold(stringResource(R.string.advanced_settings), back) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { LibraryLoading(settings != null, settingsState.failed, retry, Modifier.fillMaxWidth()) {
                if (settings == null) return@LibraryLoading
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SettingsSection(stringResource(R.string.favorites_title)) {
                        AdvancedSwitch(stringResource(R.string.favorites_enable), stringResource(R.string.favorites_enable_summary), Icons.Default.BookmarkBorder,
                            settings.favoritesEnabled, LibrarySettingOperation.Favorites !in pending, favorites)
                    }
                    SettingsSection(stringResource(R.string.history_title)) {
                        AdvancedSwitch(stringResource(R.string.history_enable), stringResource(R.string.history_enable_summary), Icons.Default.History,
                            settings.historyEnabled, LibrarySettingOperation.History !in pending, history)
                        if (settings.historyEnabled) {
                            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsNavigationRow(stringResource(R.string.history_limit),
                                summary = stringResource(R.string.history_limit_summary, settings.historyLimit),
                                icon = Icons.Default.History, enabled = !limitBusy,
                                onClick = { limitText = settings.historyLimit.toString(); showLimit = true })
                            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsNavigationRow(stringResource(R.string.history_clear),
                                summary = count.value?.let { stringResource(R.string.history_count, it) },
                                icon = Icons.Default.Delete, contentColor = MaterialTheme.colorScheme.error,
                                enabled = !clearBusy && (count.value?.let { it > 0 } == true), onClick = { showClear = true })
                            if (count.failed) TextButton(onClick = retryCount, modifier = Modifier.padding(start = 56.dp)) { Text(stringResource(R.string.common_retry)) }
                        }
                    }
                }
            } }
        }
    }
    if (showLimit) {
        val value = limitText.toIntOrNull()
        val valid = value != null && value in 1..1000
        AlertDialog(onDismissRequest = { if (!limitBusy) showLimit = false }, title = { Text(stringResource(R.string.history_limit)) },
            text = { Column {
                OutlinedTextField(limitText, { limitText = it }, singleLine = true, enabled = !limitBusy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid, supportingText = { Text(stringResource(if (valid) R.string.history_limit_hint else R.string.library_limit_invalid)) })
            } },
            dismissButton = { TextButton(onClick = { showLimit = false }, enabled = !limitBusy) { Text(stringResource(R.string.library_cancel)) } },
            confirmButton = { Button(onClick = {
                val target = value ?: return@Button
                limit(target, false) { result ->
                    when (result) {
                        HistoryLimitResult.Saved -> showLimit = false
                        is HistoryLimitResult.NeedsConfirmation -> { trimLimit = target; trimCount = result.count }
                    }
                }
            }, enabled = valid && !limitBusy) { Text(stringResource(R.string.library_save)) } },
        )
    }
    trimLimit?.let { target -> LibraryConfirm(stringResource(R.string.history_limit), stringResource(R.string.history_trim_confirm, (trimCount - target).coerceAtLeast(0), target), limitBusy, { trimLimit = null }) { limit(target, true) { result ->
        when (result) {
            HistoryLimitResult.Saved -> { trimLimit = null; showLimit = false }
            is HistoryLimitResult.NeedsConfirmation -> trimCount = result.count
        }
    } } }
    if (showClear) LibraryConfirm(stringResource(R.string.history_clear), stringResource(R.string.history_clear_confirm), clearBusy, { showClear = false }) { clear { showClear = false } }
}

@Composable
private fun AdvancedSwitch(title: String, summary: String, icon: ImageVector, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch) {
        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        change(it)
    }.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
