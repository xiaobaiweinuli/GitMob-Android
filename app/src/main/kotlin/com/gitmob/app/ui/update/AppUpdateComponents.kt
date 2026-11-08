package com.gitmob.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.BuildConfig
import com.gitmob.app.R
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.data.model.AppUpdate
import com.gitmob.app.ui.common.MarkdownWebView
import com.gitmob.app.ui.settings.AdvancedSwitch
import com.gitmob.app.ui.settings.SettingsNavigationRow
import com.gitmob.app.ui.settings.SettingsSection

@Composable
fun AppUpdateSettings(viewModel: AppUpdateViewModel, epoch: AccountEpoch) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppUpdateSettingsContent(state, { viewModel.setAutomatic(it, epoch) },
        { viewModel.checkManually(epoch) }, viewModel::retryPreferences)
}

@Composable
internal fun AppUpdateSettingsContent(
    state: AppUpdateState,
    onAutomatic: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onRetryPreferences: () -> Unit,
) {
    SettingsSection(stringResource(R.string.app_update_title)) {
        val preferences = state.preferences
        if (preferences != null) {
            AdvancedSwitch(stringResource(R.string.app_update_automatic), null, Icons.Default.Sync,
                preferences.automatic, !state.savingAutomatic, onAutomatic)
        } else {
            SettingsNavigationRow(
                title = stringResource(R.string.app_update_automatic),
                summary = if (state.preferenceFailed) stringResource(R.string.common_retry) else null,
                icon = Icons.Default.Sync,
                enabled = state.preferenceFailed,
                trailingIcon = null,
                onClick = onRetryPreferences,
            )
        }
        HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SettingsNavigationRow(
            title = stringResource(R.string.app_update_check),
            summary = if (state.checking) stringResource(R.string.app_update_checking)
                else stringResource(R.string.app_update_current_version, BuildConfig.VERSION_NAME),
            icon = Icons.Default.SystemUpdateAlt,
            onClick = onCheck,
            // 自动检查运行时仍允许手动加入请求，以便获得明确反馈。
            trailingContent = if (state.checking) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else null,
        )
    }
}

@Composable
fun AppUpdateDialogHost(viewModel: AppUpdateViewModel, epoch: AccountEpoch) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    state.update?.let { update ->
        key(update.versionCode) {
            AppUpdateDialog(update, state.savingIgnore || state.downloading, viewModel::dismiss,
                { viewModel.ignore(epoch) }, { viewModel.download(epoch) })
        }
    }
}

@Composable
internal fun AppUpdateDialog(
    update: AppUpdate,
    busy: Boolean,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.app_update_available)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.app_update_version_change, BuildConfig.VERSION_NAME, update.versionName),
                    style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (update.title.isNotBlank()) {
                        Text(update.title, style = MaterialTheme.typography.titleMedium)
                    }
                    if (update.notesHtml.isBlank() && update.title.isBlank()) {
                        Text(stringResource(R.string.app_update_no_notes))
                    } else if (update.notesHtml.isNotBlank()) {
                        MarkdownWebView(update.notesHtml,
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onIgnore, enabled = !busy) { Text(stringResource(R.string.app_update_ignore)) }
        },
        confirmButton = {
            TextButton(onClick = onDownload, enabled = !busy) { Text(stringResource(R.string.app_update_download)) }
        },
    )
}
