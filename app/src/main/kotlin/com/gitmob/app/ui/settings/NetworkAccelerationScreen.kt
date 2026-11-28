package com.gitmob.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.core.network.RawUrlPolicy
import com.gitmob.app.core.preferences.RawAccelerationService
import com.gitmob.app.data.repository.AccelerationProbeResult
import com.gitmob.app.ui.common.ActionIconButton
import com.gitmob.app.ui.common.pressToggleable
import com.gitmob.app.ui.library.LibraryLoading
import com.gitmob.app.ui.library.LibraryScaffold
import kotlin.math.roundToInt

@Composable
fun NetworkAccelerationScreen(onBack: () -> Unit, viewModel: NetworkAccelerationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NetworkAccelerationContent(state, onBack, viewModel::initialize, viewModel::enable,
        viewModel::select, viewModel::test, viewModel::save, viewModel::delete)
}

@Composable
internal fun AdvancedNetworkAccelerationEntry(onClick: () -> Unit, viewModel: NetworkAccelerationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val currentName = settings?.services?.find { it.id == settings.selectedId }?.name
    SettingsSection(stringResource(R.string.acceleration_network)) {
        SettingsNavigationRow(stringResource(R.string.acceleration_title),
            summary = stringResource(R.string.acceleration_entry, if (settings?.enabled == true && currentName != null)
                currentName else stringResource(R.string.acceleration_disabled)),
            icon = Icons.Default.Language, onClick = onClick)
    }
}

@Composable
internal fun NetworkAccelerationContent(
    state: NetworkAccelerationState,
    back: () -> Unit,
    retry: () -> Unit,
    enable: (Boolean) -> Unit,
    select: (String) -> Unit,
    test: () -> Unit,
    save: (String?, String, String, () -> Unit) -> Unit,
    delete: (String) -> Unit,
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var revealedId by remember { mutableStateOf<String?>(null) }
    val settings = state.settings
    fun add() { editingId = null; editorOpen = true }
    LibraryScaffold(stringResource(R.string.acceleration_title), back, actions = {
        ActionIconButton(onClick = test, enabled = !settings?.services.isNullOrEmpty() && !state.saving,
            modifier = Modifier.testTag("acceleration/test")) {
            Icon(Icons.Outlined.Bolt, stringResource(if (state.testing) R.string.acceleration_stop else R.string.acceleration_test),
                tint = if (state.testing) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
        ActionIconButton(onClick = ::add, enabled = settings != null && !state.saving,
            modifier = Modifier.testTag("acceleration/add")) {
            Icon(Icons.Default.Add, stringResource(R.string.acceleration_add))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup(), contentPadding = PaddingValues(16.dp)) {
            if (settings == null) item {
                LibraryLoading(false, state.failed, retry) {}
            } else {
                item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().testTag("acceleration/enable")
                            .pressToggleable(settings.enabled, enabled = !state.saving, role = Role.Switch) {
                                if (!it || settings.services.isNotEmpty()) enable(it) else add()
                            }.heightIn(min = 80.dp).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.acceleration_enable), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(if (settings.enabled) R.string.acceleration_enabled_summary else R.string.acceleration_disabled_summary),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(settings.enabled, onCheckedChange = null, enabled = !state.saving)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                if (settings.services.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp).testTag("acceleration/empty"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.acceleration_empty), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.acceleration_empty_summary), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.orderedServices, key = { it.id }) { service ->
                    AccelerationServiceRow(service, settings.selectedId == service.id,
                        state.results[service.id], service.id in state.pending, !state.saving,
                        revealedId == service.id, { revealedId = if (it) service.id else null },
                        { select(service.id) }, { revealedId = null; editingId = service.id; editorOpen = true }, { deletingId = service.id })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (editorOpen && settings != null) ServiceEditor(
        settings.services.find { it.id == editingId }, settings.services,
        settings.enabled && settings.selectedId == editingId && editingId != null,
        state.saving, { editorOpen = false },
    ) { name, address -> save(editingId, name, address) { editorOpen = false } }
    settings?.services?.find { it.id == deletingId }?.let { service ->
        AlertDialog(onDismissRequest = { deletingId = null }, shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.acceleration_delete_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.acceleration_delete_message, service.name))
                if (settings.enabled && settings.selectedId == service.id) Text(stringResource(R.string.acceleration_delete_active),
                    color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = { deletingId = null }) { Text(stringResource(R.string.library_cancel)) } },
            confirmButton = { Button(shapes = ButtonDefaults.shapes(), onClick = { delete(service.id); deletingId = null },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.acceleration_delete))
            } })
    }
}

@Composable
private fun AccelerationServiceRow(
    service: RawAccelerationService, selected: Boolean, result: AccelerationProbeResult?, testing: Boolean,
    enabled: Boolean, revealed: Boolean, setRevealed: (Boolean) -> Unit,
    select: () -> Unit, edit: () -> Unit, delete: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val reveal = with(LocalDensity.current) { 80.dp.toPx() }
    var dragOffset by remember(service.id) { mutableStateOf<Float?>(null) }
    val offset = dragOffset ?: if (revealed) -reveal else 0f
    val detailColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val editLabel = stringResource(R.string.acceleration_edit)
    val deleteLabel = stringResource(R.string.acceleration_delete)
    val testingLabel = stringResource(R.string.acceleration_testing)
    Box(Modifier.fillMaxWidth().clip(shape)) {
        if (offset < 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.error)) {
            TextButton(shapes = ButtonDefaults.shapes(), onClick = delete, enabled = enabled,
                modifier = Modifier.align(Alignment.CenterEnd).width(80.dp).fillMaxHeight(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onError)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, null)
                    Text(deleteLabel)
                }
            }
        }
        Surface(shape = shape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.offset { IntOffset(offset.roundToInt(), 0) }.fillMaxWidth()
                .testTag("acceleration/service/" + service.id)
                .semantics {
                    this.selected = selected
                    customActions = if (enabled) listOf(CustomAccessibilityAction(editLabel) { edit(); true },
                        CustomAccessibilityAction(deleteLabel) { delete(); true }) else emptyList()
                }
                .pointerInput(service.id, enabled, reveal, revealed) {
                    if (enabled) detectHorizontalDragGestures(
                        onDragStart = { dragOffset = if (revealed) -reveal else 0f },
                        onDragEnd = { setRevealed((dragOffset ?: 0f) < -reveal / 2); dragOffset = null },
                        onDragCancel = { dragOffset = null },
                    ) { change, distance -> change.consume(); dragOffset = ((dragOffset ?: 0f) + distance).coerceIn(-reveal, 0f) }
                }
                .combinedClickable(enabled = enabled, role = Role.RadioButton, onLongClickLabel = editLabel,
                    onLongClick = edit, onClick = { if (revealed) setRevealed(false) else select() }),
        ) {
            Row(Modifier.heightIn(min = 88.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected, onClick = null, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(service.name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(service.prefix, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = detailColor)
                }
                Box(Modifier.widthIn(min = 58.dp, max = 94.dp), contentAlignment = Alignment.CenterEnd) {
                    if (testing) CircularProgressIndicator(Modifier.size(20.dp).semantics {
                        contentDescription = testingLabel
                    }.testTag("acceleration/progress/" + service.id), strokeWidth = 2.dp)
                    else if (result != null) Text(
                        when (result) {
                            is AccelerationProbeResult.Success -> stringResource(R.string.acceleration_millis, result.millis)
                            AccelerationProbeResult.Timeout -> stringResource(R.string.acceleration_timeout)
                            AccelerationProbeResult.Failed -> stringResource(R.string.acceleration_failed)
                        }, fontSize = 13.sp, textAlign = TextAlign.End,
                        color = if (result is AccelerationProbeResult.Success) detailColor else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ServiceEditor(
    service: RawAccelerationService?, services: List<RawAccelerationService>, active: Boolean,
    saving: Boolean, dismiss: () -> Unit, save: (String, String) -> Unit,
) {
    var name by rememberSaveable(service?.id) { mutableStateOf(service?.name.orEmpty()) }
    var address by rememberSaveable(service?.id) { mutableStateOf(service?.prefix.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val prefix = remember(address) { RawUrlPolicy().normalizePrefix(address) }
    val nameError = name.trim().isEmpty() || name.length > 40
    val addressError = when {
        prefix == null -> R.string.acceleration_invalid_address
        services.any { it.id != service?.id && it.prefix == prefix } -> R.string.acceleration_duplicate
        else -> null
    }
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(if (service == null) R.string.acceleration_add else R.string.acceleration_edit)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { if (it.length <= 40) name = it }, modifier = Modifier.fillMaxWidth(),
                enabled = !saving, singleLine = true, shape = RoundedCornerShape(12.dp),
                label = { Text(stringResource(R.string.acceleration_name)) },
                placeholder = { Text(stringResource(R.string.acceleration_name_placeholder)) },
                isError = submitted && nameError,
                supportingText = if (submitted && nameError) { { Text(stringResource(R.string.acceleration_invalid_name)) } } else null)
            OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(),
                enabled = !saving, singleLine = true, shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                label = { Text(stringResource(R.string.acceleration_address)) },
                placeholder = { Text("https://proxy.example/") },
                isError = submitted && addressError != null,
                supportingText = if (submitted && addressError != null) { { Text(stringResource(addressError)) } } else null)
            if (active) Text(stringResource(R.string.acceleration_edit_active), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        dismissButton = { TextButton(shapes = ButtonDefaults.shapes(), onClick = dismiss, enabled = !saving) { Text(stringResource(R.string.library_cancel)) } },
        confirmButton = { Button(shapes = ButtonDefaults.shapes(), onClick = { submitted = true; if (!nameError && addressError == null) save(name.trim(), address) }, enabled = !saving) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.library_save))
        } })
}
