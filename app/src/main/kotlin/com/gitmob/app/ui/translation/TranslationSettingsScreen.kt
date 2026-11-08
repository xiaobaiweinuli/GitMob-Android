package com.gitmob.app.ui.translation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.translation.TranslationProvider
import com.gitmob.app.ui.library.LibraryScaffold
import com.gitmob.app.ui.settings.SettingsNavigationRow
import com.gitmob.app.ui.settings.SettingsSection

@Composable
fun TranslationSettingsScreen(onBack: () -> Unit, viewModel: TranslationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configuration = state.configuration
    var providersOpen by remember { mutableStateOf(false) }
    var configure by remember { mutableStateOf<TranslationProvider?>(null) }
    LibraryScaffold(stringResource(R.string.translation_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (configuration == null) item(key = "loading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.translation_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
            else {
                val preferences = configuration.preferences
                item(key = "enabled") {
                    SettingsSection(stringResource(R.string.translation_title)) {
                        Row(Modifier.fillMaxWidth().toggleable(preferences.enabled, enabled = !configuration.storageFailed, role = Role.Switch, onValueChange = { viewModel.savePreferences(preferences.copy(enabled = it)) }).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.translation_enable), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.translation_enable_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(preferences.enabled, onCheckedChange = null, enabled = !configuration.storageFailed)
                        }
                    }
                }
                if (configuration.storageFailed) item(key = "storage_error") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.translation_error_storage), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retryConfiguration) { Text(stringResource(R.string.common_retry)) }
                    }
                }
                else if (preferences.enabled) {
                    item(key = "provider") {
                        TranslationProviderSection(preferences, { providersOpen = true }) { configure = it }
                    }
                    item(key = "options") {
                        TranslationOptionsSection(preferences, viewModel::savePreferences)
                    }
                    item(key = "test") {
                        SettingsSection(stringResource(R.string.translation_test)) {
                            SettingsNavigationRow(stringResource(R.string.translation_test), stringResource(R.string.translation_test_hint), onClick = { viewModel.test() }, icon = Icons.Default.Wifi, enabled = !state.testing)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                if (state.testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                                state.testProblem?.let { TranslationErrorRow(it, { viewModel.test() }, { providersOpen = true }) }
                                state.testResult?.let { Text(stringResource(R.string.translation_test_success, it), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                    item(key = "anonymous_hint") { Text(stringResource(R.string.translation_anonymous_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                state.notice?.let { problem -> item(key = "notice") { Text(translationProblemText(problem), color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (providersOpen && configuration != null) TranslationProviderSheet(configuration.preferences.provider, {
        viewModel.savePreferences(configuration.preferences.copy(provider = it))
        if (it in setOf(TranslationProvider.BAIDU, TranslationProvider.YOUDAO_OPEN, TranslationProvider.CUSTOM)) configure = it
    }, { providersOpen = false })
    configure?.let { provider ->
        if (configuration != null) TranslationCredentialsSheet(provider, configuration.credentialsFor(provider), state, configuration.preferences, viewModel) { configure = null }
    }
}

@Composable
fun AdvancedTranslationEntry(onClick: () -> Unit, viewModel: TranslationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences = state.configuration?.preferences
    SettingsSection(stringResource(R.string.translation_reading)) {
        SettingsNavigationRow(stringResource(R.string.translation_title), summary = when {
            state.configuration == null -> stringResource(R.string.translation_loading)
            state.configuration?.storageFailed == true -> stringResource(R.string.translation_error_storage)
            preferences?.enabled == true -> translationProviderName(preferences.provider) + " · " + (translationLanguages[preferences.target] ?: preferences.target)
            else -> stringResource(R.string.translation_off)
        }, onClick = onClick, icon = Icons.Default.Translate)
    }
}
