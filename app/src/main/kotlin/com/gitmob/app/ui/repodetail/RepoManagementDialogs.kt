package com.gitmob.app.ui.repodetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoDetail
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.areValidRepositoryTopics
import com.gitmob.app.data.model.isValidRepositoryName
import com.gitmob.app.data.model.isValidRepositoryWebsite
import com.gitmob.app.data.model.parseRepositoryTopics
import kotlinx.coroutines.launch

@Composable
internal fun RepoManagementMenu(detail: RepoDetail?, enabled: Boolean, onShare: () -> Unit, onAction: (RepoManagementAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = onShare, enabled = detail != null) {
        Icon(Icons.Default.Share, stringResource(R.string.conversation_share))
    }
    if (detail == null) return
    val capabilities = detail.capabilities
    val showVisibility = capabilities.canChangeVisibility && !detail.isFork
    if (!(capabilities.canRenameRepo || capabilities.canManageSomeSettings || showVisibility ||
            capabilities.canTransferRepo || capabilities.canArchiveRepo || capabilities.canToggleIssues ||
            capabilities.canToggleDiscussions || capabilities.canDeleteRepo)) return
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.repo_manage_more))
        }
        DropdownMenu(expanded, { expanded = false }) {
                if (capabilities.canRenameRepo) ManagementMenuItem(R.string.repo_manage_rename, enabled) { expanded = false; onAction(RepoManagementAction.RENAME) }
                if (capabilities.canManageSomeSettings) ManagementMenuItem(R.string.repo_manage_information, enabled) { expanded = false; onAction(RepoManagementAction.INFORMATION) }
                if (showVisibility) ManagementMenuItem(
                    if (detail.visibility !in listOf("PUBLIC", "PRIVATE")) R.string.repo_manage_visibility_unavailable else R.string.repo_manage_visibility,
                    enabled && detail.visibility in listOf("PUBLIC", "PRIVATE"),
                ) { expanded = false; onAction(RepoManagementAction.VISIBILITY) }
                if (capabilities.canTransferRepo) ManagementMenuItem(R.string.repo_transfer_title, enabled) { expanded = false; onAction(RepoManagementAction.TRANSFER) }
                if (capabilities.canArchiveRepo) ManagementMenuItem(if (detail.isArchived) R.string.repo_manage_unarchive else R.string.repo_manage_archive, enabled) { expanded = false; onAction(RepoManagementAction.ARCHIVE) }
                if (capabilities.canToggleIssues) ManagementMenuItem(if (detail.hasIssuesEnabled) R.string.repo_manage_disable_issues else R.string.repo_manage_enable_issues, enabled) { expanded = false; onAction(RepoManagementAction.ISSUES) }
                if (capabilities.canToggleDiscussions) ManagementMenuItem(if (detail.hasDiscussionsEnabled) R.string.repo_manage_disable_discussions else R.string.repo_manage_enable_discussions, enabled) { expanded = false; onAction(RepoManagementAction.DISCUSSIONS) }
                if (capabilities.canDeleteRepo) ManagementMenuItem(R.string.repo_manage_delete, enabled, destructive = true) { expanded = false; onAction(RepoManagementAction.DELETE) }
        }
    }
}

@Composable
private fun ManagementMenuItem(label: Int, enabled: Boolean, destructive: Boolean = false, click: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label), color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified) }, onClick = click, enabled = enabled)
}

@Composable
internal fun RepoManagementDialogs(state: RepoDetailUiState, viewModel: RepoDetailViewModel) {
    val action = state.managementAction ?: return
    val detail = state.detail ?: return
    if (action == RepoManagementAction.TRANSFER) {
        TransferDialog(state, viewModel)
        return
    }
    val title = when (action) {
        RepoManagementAction.RENAME -> stringResource(R.string.repo_manage_rename)
        RepoManagementAction.INFORMATION -> stringResource(R.string.repo_manage_information)
        RepoManagementAction.VISIBILITY -> stringResource(if (detail.isPrivate) R.string.repo_manage_make_public else R.string.repo_manage_make_private)
        RepoManagementAction.ARCHIVE -> stringResource(if (detail.isArchived) R.string.repo_manage_unarchive else R.string.repo_manage_archive)
        RepoManagementAction.NOTIFICATIONS -> stringResource(R.string.repo_manage_notifications)
        RepoManagementAction.ISSUES -> stringResource(if (detail.hasIssuesEnabled) R.string.repo_manage_disable_issues else R.string.repo_manage_enable_issues)
        RepoManagementAction.DISCUSSIONS -> stringResource(if (detail.hasDiscussionsEnabled) R.string.repo_manage_disable_discussions else R.string.repo_manage_enable_discussions)
        RepoManagementAction.DELETE -> stringResource(R.string.repo_manage_delete_title, detail.ownerLogin, detail.name)
        RepoManagementAction.TRANSFER -> return
    }
    val valid = when (action) {
        RepoManagementAction.RENAME -> isValidRepositoryName(state.editName)
        RepoManagementAction.INFORMATION -> state.topicsLoaded && isValidRepositoryWebsite(state.editWebsite) && areValidRepositoryTopics(parseRepositoryTopics(state.editTopics))
        RepoManagementAction.DELETE -> state.confirmationName == detail.name
        else -> true
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissManagement,
        modifier = Modifier.padding(horizontal = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (action) {
                    RepoManagementAction.RENAME -> RepositoryNameField(state.editName, !state.isManaging, viewModel::editName)
                    RepoManagementAction.INFORMATION -> {
                        OutlinedTextField(state.editWebsite, viewModel::editWebsite, label = { Text(stringResource(R.string.repo_manage_website)) }, enabled = !state.isManaging, singleLine = true, isError = !isValidRepositoryWebsite(state.editWebsite), supportingText = { if (!isValidRepositoryWebsite(state.editWebsite)) Text(stringResource(R.string.repo_manage_invalid_website)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(state.editDescription, viewModel::editDescription, label = { Text(stringResource(R.string.repo_create_description)) }, enabled = !state.isManaging, modifier = Modifier.fillMaxWidth())
                        if (state.isLoadingTopics) CircularProgressIndicator(Modifier.size(24.dp))
                        else if (!state.topicsLoaded) TextButton(onClick = viewModel::loadTopics) { Text(stringResource(R.string.common_retry)) }
                        OutlinedTextField(state.editTopics, viewModel::editTopics, label = { Text(stringResource(R.string.repo_manage_topics)) }, enabled = state.topicsLoaded && !state.isManaging, isError = !areValidRepositoryTopics(parseRepositoryTopics(state.editTopics)), supportingText = { Text(stringResource(if (areValidRepositoryTopics(parseRepositoryTopics(state.editTopics))) R.string.repo_manage_topics_hint else R.string.repo_manage_invalid_topics)) }, modifier = Modifier.fillMaxWidth())
                    }
                    RepoManagementAction.VISIBILITY -> Text(stringResource(if (detail.isPrivate) R.string.repo_manage_public_warning else R.string.repo_manage_private_warning))
                    RepoManagementAction.ARCHIVE -> Text(stringResource(if (detail.isArchived) R.string.repo_manage_unarchive_message else R.string.repo_manage_archive_message))
                    RepoManagementAction.ISSUES, RepoManagementAction.DISCUSSIONS -> Text(stringResource(R.string.repo_manage_feature_confirm))
                    RepoManagementAction.DELETE -> {
                        Text(stringResource(R.string.repo_manage_delete_warning))
                        TextButton(onClick = { viewModel.editConfirmation(detail.name) }, enabled = !state.isManaging) { Text(stringResource(R.string.repo_manage_fill_name, detail.name)) }
                        OutlinedTextField(state.confirmationName, viewModel::editConfirmation, label = { Text(stringResource(R.string.repo_manage_confirm_name)) }, enabled = !state.isManaging, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    RepoManagementAction.NOTIFICATIONS -> Column(Modifier.selectableGroup()) {
                        listOf("UNSUBSCRIBED" to R.string.repo_subscription_participating, "SUBSCRIBED" to R.string.repo_subscription_all, "IGNORED" to R.string.repo_subscription_ignored).forEach { (value, label) ->
                            Text(stringResource(label), modifier = Modifier.fillMaxWidth().selectable(selected = state.subscription == value, enabled = !state.isManaging, role = Role.RadioButton, onClick = { viewModel.selectSubscription(value) }).background(if (state.subscription == value) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.medium).padding(16.dp))
                        }
                    }
                    RepoManagementAction.TRANSFER -> Unit
                }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissManagement, enabled = !state.isManaging) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            Button(
                onClick = viewModel::confirmManagement, enabled = valid && !state.isManaging,
                colors = if (action == RepoManagementAction.DELETE) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { if (state.isManaging) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(if (action == RepoManagementAction.DELETE) R.string.common_delete else R.string.common_save)) }
        },
    )
}

@Composable
private fun RepositoryNameField(value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(stringResource(R.string.repo_create_name)) }, singleLine = true, enabled = enabled, isError = !isValidRepositoryName(value), supportingText = { if (!isValidRepositoryName(value)) Text(stringResource(R.string.repo_manage_invalid_name)) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TransferDialog(state: RepoDetailUiState, viewModel: RepoDetailViewModel) {
    val detail = state.detail ?: return
    val target = state.checkedTransferTarget
    if (state.showTransferConfirmation && target != null) {
        AlertDialog(
            onDismissRequest = { viewModel.showTransferConfirmation(false) },
            title = { Text(stringResource(R.string.repo_transfer_confirm)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.repo_transfer_verified))
                    Text(stringResource(R.string.repo_transfer_warning, detail.ownerLogin, detail.name, target.account.login, state.editName))
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.showTransferConfirmation(false) }, enabled = !state.isManaging) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = {
                Button(onClick = viewModel::confirmManagement, enabled = !state.isManaging) {
                    if (state.isManaging) CircularProgressIndicator(Modifier.size(20.dp))
                    else Text(stringResource(R.string.repo_transfer_submit))
                }
            },
        )
        return
    }
    val enabled = !state.isManaging
    val logins = state.transferTargets.map { it.login } + null
    val focusRequesters = remember(logins) { logins.map { FocusRequester() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pagingRowVisible = state.isLoadingTargets || state.transferHasNextPage || state.targetsLoadFailed
    fun listItemIndex(targetIndex: Int): Int = if (targetIndex < state.transferTargets.size) {
        targetIndex + 1
    } else {
        state.transferTargets.size + 1 + if (pagingRowVisible) 1 else 0
    }
    fun rowModifier(index: Int, selected: Boolean, canSelect: Boolean, choose: () -> Unit): Modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequesters[index])
        .onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionDown, Key.DirectionRight -> 1
                Key.DirectionUp, Key.DirectionLeft -> -1
                else -> 0
            }
            if (event.type == KeyEventType.KeyDown && direction != 0) {
                val next = generateSequence(index + direction) { it + direction }.takeWhile { it in logins.indices }
                    .firstOrNull { it == state.transferTargets.size || state.transferTargets[it].canCreateRepository }
                if (next != null) {
                    viewModel.selectTransferTarget(logins[next])
                    scope.launch {
                        listState.animateScrollToItem(listItemIndex(next))
                        focusRequesters[next].requestFocus()
                    }
                }
                true
            } else false
        }
        .selectable(selected = selected, enabled = enabled && canSelect, role = Role.RadioButton, onClick = choose)
    AlertDialog(
        onDismissRequest = viewModel::dismissManagement,
        modifier = Modifier.padding(horizontal = 24.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.repo_transfer_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp).selectableGroup(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { RepositoryNameField(state.editName, enabled, viewModel::editName) }
                state.transferTargets.forEachIndexed { index, account ->
                    item(key = account.login) {
                        TransferAccountRow(account, state.selectedTransferLogin == account.login && !state.transferOtherSelected, rowModifier(index, state.selectedTransferLogin == account.login && !state.transferOtherSelected, account.canCreateRepository) { viewModel.selectTransferTarget(account.login) })
                    }
                }
                if (state.isLoadingTargets) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                else if (state.transferHasNextPage || state.targetsLoadFailed) item { TextButton(onClick = viewModel::loadTransferTargets) { Text(stringResource(if (state.targetsLoadFailed) R.string.common_retry else R.string.common_continue)) } }
                item {
                    Text(stringResource(R.string.repo_transfer_other), modifier = rowModifier(state.transferTargets.size, state.transferOtherSelected, true) { viewModel.selectTransferTarget(null) }.background(if (state.transferOtherSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.medium).padding(16.dp))
                }
                if (state.transferOtherSelected) item {
                    OutlinedTextField(state.transferOtherLogin, viewModel::editTransferLogin, label = { Text(stringResource(R.string.repo_transfer_login)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                }
                if (target != null) item {
                    TransferAccountRow(target.account, false, Modifier.fillMaxWidth())
                    Text(stringResource(when {
                        target.hasNameConflict -> R.string.repo_transfer_name_conflict
                        !target.account.canCreateRepository -> R.string.repo_transfer_no_permission
                        else -> R.string.repo_transfer_verified
                    }), color = if (target.hasNameConflict || !target.account.canCreateRepository) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissManagement, enabled = enabled) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            val login = if (state.transferOtherSelected) state.transferOtherLogin.trim() else state.selectedTransferLogin.orEmpty()
            Button(
                onClick = viewModel::checkTransferTarget,
                enabled = enabled && !state.isCheckingTarget && isValidRepositoryName(state.editName) && login.isNotBlank() && '/' !in login,
            ) {
                if (state.isCheckingTarget) CircularProgressIndicator(Modifier.size(20.dp))
                else Text(stringResource(R.string.repo_transfer_continue))
            }
        },
    )
}

@Composable
private fun TransferAccountRow(account: RepositoryCreateOwner, selected: Boolean, modifier: Modifier) {
    Row(modifier.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(account.avatarUrl, null, Modifier.size(40.dp).clip(CircleShape))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(account.name ?: account.login, color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
            Text("@${account.login}", style = MaterialTheme.typography.bodySmall)
            if (!account.canCreateRepository) Text(stringResource(R.string.repo_transfer_no_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
