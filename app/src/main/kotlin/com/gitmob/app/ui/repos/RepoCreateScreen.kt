package com.gitmob.app.ui.repos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.RepositoryCreateOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoCreateScreen(
    defaultOwner: RepositoryCreateOwner,
    onBack: () -> Unit,
    onCreated: (owner: String, name: String) -> Unit,
    viewModel: RepoCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(defaultOwner.id) {
        viewModel.initialize(defaultOwner)
    }
    LaunchedEffect(viewModel) {
        viewModel.createdEvents.collect { created ->
            onCreated(created.owner, created.name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.repo_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::create,
                        enabled = state.name.trim().isNotEmpty() && !state.isCreating,
                    ) {
                        if (state.isCreating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Text(stringResource(R.string.repo_create_action))
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .fitInside(WindowInsetsRulers.Ime.current)

        val owner = state.owner
        if (owner == null) {
            Box(contentModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            CreateForm(
                state = state,
                owner = owner,
                viewModel = viewModel,
                modifier = contentModifier,
            )
        }
    }

    when (state.activePicker) {
        RepoCreatePicker.OWNER -> OwnerPickerSheet(state, viewModel)
        RepoCreatePicker.LICENSE -> LicensePickerSheet(state, viewModel)
        RepoCreatePicker.GITIGNORE -> GitignorePickerSheet(state, viewModel)
        null -> Unit
    }
}

@Composable
private fun CreateForm(
    state: RepoCreateUiState,
    owner: RepositoryCreateOwner,
    viewModel: RepoCreateViewModel,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.repo_create_owner),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        item {
            OwnerRow(owner = owner, onClick = viewModel::openOwnerPicker)
        }
        item {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text(stringResource(R.string.repo_create_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::updateDescription,
                label = { Text(stringResource(R.string.repo_create_description)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.repo_create_private),
                checked = state.isPrivate,
                onCheckedChange = viewModel::updatePrivate,
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.repo_create_readme),
                checked = state.addReadme,
                onCheckedChange = viewModel::updateReadme,
            )
        }
        item {
            SelectionRow(
                label = stringResource(R.string.repo_create_gitignore),
                value = state.gitignore ?: stringResource(R.string.repo_create_none),
                onClick = viewModel::openGitignorePicker,
            )
        }
        item {
            SelectionRow(
                label = stringResource(R.string.repo_create_license),
                value = state.license?.name ?: stringResource(R.string.repo_create_none),
                onClick = viewModel::openLicensePicker,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OwnerPickerSheet(
    state: RepoCreateUiState,
    viewModel: RepoCreateViewModel,
) {
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelPicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        PickerScaffold(
            title = stringResource(R.string.repo_create_owner_picker),
            onCancel = viewModel::cancelPicker,
            onConfirm = viewModel::confirmOwner,
        ) { padding ->
            OwnerPicker(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .navigationBarsPadding(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensePickerSheet(
    state: RepoCreateUiState,
    viewModel: RepoCreateViewModel,
) {
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelPicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        PickerScaffold(
            title = stringResource(R.string.repo_create_license_picker),
            onCancel = viewModel::cancelPicker,
            onConfirm = viewModel::confirmLicense,
        ) { padding ->
            LicensePicker(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .navigationBarsPadding(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitignorePickerSheet(
    state: RepoCreateUiState,
    viewModel: RepoCreateViewModel,
) {
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelPicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        PickerScaffold(
            title = stringResource(R.string.repo_create_gitignore_picker),
            onCancel = viewModel::cancelPicker,
            onConfirm = viewModel::confirmGitignore,
        ) { padding ->
            GitignorePicker(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .navigationBarsPadding(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScaffold(
    title: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.common_done))
                    }
                },
                windowInsets = WindowInsets(0),
            )
        },
        contentWindowInsets = WindowInsets(0),
        content = content,
    )
}

@Composable
private fun OwnerPicker(
    state: RepoCreateUiState,
    viewModel: RepoCreateViewModel,
    modifier: Modifier,
) {
    val owners = state.owners
    val listState = rememberLazyListState()
    val paginationItemVisible by remember(
        listState,
        owners.size,
        state.ownersHasNextPage,
        state.isLoadingOwners,
        state.ownersLoadFailed,
    ) {
        derivedStateOf {
            state.ownersHasNextPage &&
                !state.isLoadingOwners &&
                !state.ownersLoadFailed &&
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == owners.size
        }
    }
    LaunchedEffect(paginationItemVisible) {
        if (paginationItemVisible) viewModel.loadMoreOwners()
    }

    when {
        state.isLoadingOwners && owners.isEmpty() -> {
            Box(
                modifier = modifier.padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        state.ownersLoadFailed && owners.isEmpty() -> RetryRow(
            modifier = modifier,
            onRetry = viewModel::retryOwners,
        )

        else -> LazyColumn(
            modifier = modifier,
            state = listState,
        ) {
            items(
                items = owners,
                key = { owner -> owner.id },
            ) { owner ->
                OwnerChoiceRow(
                    owner = owner,
                    selected = state.draftOwner?.id == owner.id,
                    onClick = viewModel::selectOwner,
                )
            }
            if (state.ownersHasNextPage || state.ownersLoadFailed) {
                item(key = "repository-create-owner-pagination") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.ownersLoadFailed -> RetryRow(onRetry = viewModel::retryOwners)
                            state.isLoadingOwners -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicensePicker(
    state: RepoCreateUiState,
    viewModel: RepoCreateViewModel,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.licenseQuery,
            onValueChange = viewModel::updateLicenseQuery,
            label = { Text(stringResource(R.string.repo_create_license_picker)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when {
            state.isLoadingLicenses && state.licenses.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.licensesLoadFailed -> RetryRow(onRetry = viewModel::retryLicenses)

            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item(key = "repository-create-license-none") {
                    ChoiceRow(
                        label = stringResource(R.string.repo_create_none),
                        selected = state.draftLicense == null,
                        onClick = { viewModel.selectLicense(null) },
                    )
                }
                items(
                    items = state.licenses.filter {
                        it.name.contains(state.licenseQuery, true) ||
                            it.key.contains(state.licenseQuery, true)
                    },
                    key = { license -> license.key },
                ) { license ->
                    ChoiceRow(
                        label = license.name,
                        selected = state.draftLicense?.key == license.key,
                        onClick = { viewModel.selectLicense(license) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GitignorePicker(
    state: RepoCreateUiState,
    viewModel: RepoCreateViewModel,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.gitignoreQuery,
            onValueChange = viewModel::updateGitignoreQuery,
            label = { Text(stringResource(R.string.repo_create_gitignore_picker)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when {
            state.isLoadingGitignore && state.gitignoreTemplates.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.gitignoreLoadFailed -> RetryRow(onRetry = viewModel::retryGitignore)

            else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item(key = "repository-create-gitignore-none") {
                    ChoiceRow(
                        label = stringResource(R.string.repo_create_none),
                        selected = state.draftGitignore == null,
                        onClick = { viewModel.selectGitignore(null) },
                    )
                }
                items(
                    items = state.gitignoreTemplates.filter {
                        it.contains(state.gitignoreQuery, true)
                    },
                    key = { template -> template },
                ) { template ->
                    ChoiceRow(
                        label = template,
                        selected = state.draftGitignore == template,
                        onClick = { viewModel.selectGitignore(template) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnerRow(
    owner: RepositoryCreateOwner,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = owner.avatarUrl,
            contentDescription = owner.login,
            modifier = Modifier.size(40.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(owner.name ?: owner.login, style = MaterialTheme.typography.titleMedium)
            Text("@${owner.login}", style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun OwnerChoiceRow(
    owner: RepositoryCreateOwner,
    selected: Boolean,
    onClick: (RepositoryCreateOwner) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = owner.canCreateRepository) { onClick(owner) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = owner.avatarUrl,
            contentDescription = owner.login,
            modifier = Modifier.size(40.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(owner.name ?: owner.login, style = MaterialTheme.typography.titleMedium)
            Text(
                if (owner.canCreateRepository) {
                    "@${owner.login}"
                } else {
                    "@${owner.login} · ${stringResource(R.string.repo_create_no_permission)}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RadioButton(
            selected = selected,
            onClick = if (owner.canCreateRepository) ({ onClick(owner) }) else null,
        )
    }
}

@Composable
private fun SelectionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RetryRow(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRetry) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.common_retry),
            )
        }
        Text(stringResource(R.string.common_retry))
    }
}
