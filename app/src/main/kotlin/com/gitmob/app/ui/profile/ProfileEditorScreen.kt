package com.gitmob.app.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.ProfileField
import com.gitmob.app.ui.login.AccountIdentity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(onBack: () -> Unit, viewModel: ProfileEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val focus = remember { ProfileField.entries.associateWith { FocusRequester() } }
    val relocation = remember { ProfileField.entries.associateWith { BringIntoViewRequester() } }

    fun requestBack() {
        if (!viewModel.accountEpoch.isCurrent || state.isSaving) return
        if (state.hasChanges) confirmDiscard = true else onBack()
    }
    BackHandler(onBack = ::requestBack)
    LaunchedEffect(state.focusRequest) {
        state.focusField?.let { field ->
            focus.getValue(field).requestFocus()
            relocation.getValue(field).bringIntoView()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = ::requestBack, enabled = !state.isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    FilledTonalButton(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.padding(end = 12.dp).heightIn(min = 48.dp)) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (state.isSaving) R.string.profile_saving else R.string.common_save))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        // safeDrawing 包含底部系统栏和 IME；消费一次，不另叠加 imePadding。
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (state.original == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loadFailed) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.common_load_failed))
                    Button(onClick = viewModel::load) { Text(stringResource(R.string.common_retry)) }
                } else CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .wrapContentWidth().widthIn(max = 680.dp)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val identity = state.original!!.user
                AccountIdentity(identity.name, identity.login, identity.avatarUrl, Modifier.fillMaxWidth())
                Text(stringResource(R.string.profile_readonly_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProfileField.entries.forEach { field ->
                    ProfileEditorField(field, state, focus.getValue(field), relocation.getValue(field), viewModel)
                }
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = state.draft.hireable, enabled = !state.isSaving, role = Role.Switch, onValueChange = viewModel::setHireable)
                            .heightIn(min = 76.dp).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.profile_hireable), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.profile_hireable_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.draft.hireable, onCheckedChange = null, enabled = !state.isSaving)
                    }
                }
                if (state.saveErrorRes != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(state.saveErrorRes!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = viewModel::save, enabled = state.canSave) { Text(stringResource(R.string.common_retry)) }
                    }
                }
                Text(
                    stringResource(state.saveStatusRes ?: when {
                        state.isSaving -> R.string.profile_saving
                        state.hasChanges -> R.string.profile_unsaved_status
                        else -> R.string.profile_not_modified
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.profile_api_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmDiscard) {
        val safeFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.profile_discard_title)) },
            text = { Text(stringResource(R.string.profile_discard_message)) },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.focusRequester(safeFocus)) { Text(stringResource(R.string.profile_keep_editing)) }
                LaunchedEffect(safeFocus) { safeFocus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.profile_discard)) }
            },
        )
    }
}

@Composable
private fun ProfileEditorField(
    field: ProfileField,
    state: ProfileEditorUiState,
    focus: FocusRequester,
    relocation: BringIntoViewRequester,
    viewModel: ProfileEditorViewModel,
) {
    val label = stringResource(when (field) {
        ProfileField.NAME -> R.string.profile_name
        ProfileField.BIO -> R.string.profile_bio
        ProfileField.COMPANY -> R.string.profile_company
        ProfileField.LOCATION -> R.string.profile_location
        ProfileField.BLOG -> R.string.profile_blog
        ProfileField.EMAIL -> R.string.profile_email
        ProfileField.TWITTER_USERNAME -> R.string.profile_twitter
    })
    val help = when (field) {
        ProfileField.NAME -> R.string.profile_name_help
        ProfileField.BIO -> R.string.profile_bio_help
        ProfileField.COMPANY -> R.string.profile_company_help
        ProfileField.LOCATION -> R.string.profile_location_help
        ProfileField.BLOG -> R.string.profile_blog_help
        ProfileField.EMAIL -> R.string.profile_email_help
        ProfileField.TWITTER_USERNAME -> R.string.profile_twitter_help
    }
    val error = state.errors[field]
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(relocation), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.draft.value(field),
            onValueChange = { viewModel.update(field, it) },
            enabled = !state.isSaving,
            singleLine = field != ProfileField.BIO,
            minLines = if (field == ProfileField.BIO) 3 else 1,
            shape = RoundedCornerShape(20.dp),
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = when (field) {
                ProfileField.EMAIL -> KeyboardType.Email
                ProfileField.BLOG -> KeyboardType.Uri
                else -> KeyboardType.Text
            }),
            supportingText = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(error ?: help), Modifier.weight(1f))
                    if (field == ProfileField.BIO) Text(stringResource(R.string.profile_bio_count, state.draft.bioLength))
                }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label }
                .onFocusChanged {
                    if (focused && !it.isFocused) viewModel.validate(field)
                    focused = it.isFocused
                    if (it.isFocused) scope.launch { relocation.bringIntoView() }
                },
        )
    }
}
