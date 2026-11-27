package com.gitmob.app.ui.shortcuts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.LauncherShortcut
import com.gitmob.app.data.model.ShortcutPublishResult
import com.gitmob.app.ui.common.ActionButtonContent
import com.gitmob.app.ui.common.pressSelectable

@Composable
fun ShortcutAddDialog(
    target: LauncherShortcut,
    onDismiss: () -> Unit,
    viewModel: ShortcutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val existing = state?.shortcuts?.firstOrNull { it.id == target.id }
    var title by rememberSaveable(target.id) { mutableStateOf<String?>(null) }
    val name = title ?: existing?.title ?: target.title
    var replacing by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    val ready = state != null && !busy && name.isNotBlank()
    LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose {} }

    fun added(result: ShortcutPublishResult) {
        if (result == ShortcutPublishResult.Full) { replacing = true; replacement = null }
        else if (result == ShortcutPublishResult.Saved) onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shortcuts_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortcutNameField(name, { title = it }, busy)
                Text(target.subtitle, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.shortcuts_account_hint), style = MaterialTheme.typography.bodySmall)
                if (state == null) {
                    if (failed) TextButton(onClick = viewModel::refresh, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_retry)) }
                    else CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                }
                Button(
                    onClick = { viewModel.add(target.copy(title = name), done = ::added) },
                    modifier = Modifier.fillMaxWidth(), enabled = ready, shapes = ButtonDefaults.shapes(),
                ) {
                    ActionButtonContent(busy, stringResource(if (existing?.dynamic == true) R.string.shortcuts_update_menu else R.string.shortcuts_add_menu))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.pin(target.copy(title = name)) {
                            if (it == ShortcutPublishResult.PinUnavailable) help = true else onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = ready, shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.shortcuts_add_desktop)) }
                if (state?.pinSupported == false) Text(stringResource(R.string.shortcuts_pin_unavailable), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
    )
    if (replacing) AlertDialog(
        onDismissRequest = { if (!busy) replacing = false },
        title = { Text(stringResource(R.string.shortcuts_full_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.shortcuts_full_message, state?.capacity ?: 0))
                state?.dynamic.orEmpty().forEach { item ->
                    Row(Modifier.fillMaxWidth().pressSelectable(selected = replacement == item.id, enabled = !busy, onClick = { replacement = item.id })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = replacement == item.id, onClick = null)
                        Text(item.title, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.add(target.copy(title = name), replacement, ::added) },
                enabled = !busy && replacement != null && state?.dynamic?.any { it.id == replacement } == true,
                shapes = ButtonDefaults.shapes()) { ActionButtonContent(busy, stringResource(R.string.shortcuts_replace)) }
        },
        dismissButton = { TextButton(onClick = { replacing = false }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (help) ShortcutHelpDialog({ help = false }, viewModel::settingsUnavailable)
}

@Composable
internal fun ShortcutRenameDialog(item: LauncherShortcut, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.title) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.shortcuts_rename)) },
        text = { ShortcutNameField(name, { name = it }, busy) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = !busy && name.isNotBlank(), shapes = ButtonDefaults.shapes()) {
            ActionButtonContent(busy, stringResource(R.string.common_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ShortcutNameField(name: String, onChange: (String) -> Unit, busy: Boolean) {
    OutlinedTextField(
        value = name, onValueChange = onChange, enabled = !busy, singleLine = true,
        label = { Text(stringResource(R.string.shortcuts_name)) }, modifier = Modifier.fillMaxWidth(),
        isError = name.isBlank(), supportingText = { if (name.isBlank()) Text(stringResource(R.string.shortcuts_name_required)) },
    )
}

@Composable
internal fun ShortcutHelpDialog(onDismiss: () -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcuts_help)) },
        text = { Text(stringResource(R.string.shortcuts_help_message), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                onDismiss()
            } catch (_: ActivityNotFoundException) { onUnavailable() }
            catch (_: SecurityException) { onUnavailable() }
        }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.shortcuts_open_settings)) } },
    )
}
