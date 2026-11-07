package com.gitmob.app.ui.translation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gitmob.app.R
import com.gitmob.app.data.translation.*
import com.gitmob.app.ui.settings.SettingsDropdownRow
import com.gitmob.app.ui.settings.SettingsNavigationRow
import com.gitmob.app.ui.settings.SettingsSection
import com.gitmob.app.ui.settings.SelectionRow

@Composable
fun translationProviderName(provider: TranslationProvider): String = when (provider) {
    TranslationProvider.BAIDU -> stringResource(R.string.translation_baidu)
    TranslationProvider.YOUDAO_OPEN -> stringResource(R.string.translation_youdao_open)
    TranslationProvider.CUSTOM -> stringResource(R.string.translation_custom)
    TranslationProvider.EDGE -> "Microsoft Edge"
    TranslationProvider.TRANSMART -> "Tencent Transmart"
}

@Composable
fun translationProblemText(problem: TranslationProblem): String = stringResource(when (problem) {
    TranslationProblem.NETWORK -> R.string.translation_error_network
    TranslationProblem.AUTH -> R.string.translation_error_auth
    TranslationProblem.LIMIT -> R.string.translation_error_limit
    TranslationProblem.QUOTA -> R.string.translation_error_quota
    TranslationProblem.MAPPING -> R.string.translation_error_mapping
    TranslationProblem.DISPLAY -> R.string.translation_error_display
    TranslationProblem.LANGUAGE -> R.string.translation_error_language
    TranslationProblem.CONFIGURATION -> R.string.translation_error_config
    TranslationProblem.RESPONSE -> R.string.translation_error_response
    TranslationProblem.TOO_LONG -> R.string.translation_error_long
    TranslationProblem.EMPTY -> R.string.translation_error_empty
    TranslationProblem.STORAGE -> R.string.translation_error_storage
})

internal val translationLanguages = linkedMapOf("zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語", "ko" to "한국어", "fr" to "Français", "de" to "Deutsch", "es" to "Español", "ru" to "Русский", "pt" to "Português", "it" to "Italiano")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationPreferencesEditor(value: TranslationPreferences, onChange: (TranslationPreferences) -> Unit, onConfigure: (TranslationProvider) -> Unit) {
    var providersOpen by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.translation_provider)) {
        SettingsNavigationRow(translationProviderName(value.provider), icon = Icons.Default.Translate, onClick = { providersOpen = true })
        if (value.provider in setOf(TranslationProvider.BAIDU, TranslationProvider.YOUDAO_OPEN, TranslationProvider.CUSTOM)) {
            HorizontalDivider(Modifier.padding(start = 72.dp))
            SettingsNavigationRow(stringResource(R.string.translation_configure), icon = Icons.Default.Settings, onClick = { onConfigure(value.provider) })
        }
    }
    SettingsSection(stringResource(R.string.translation_options)) {
        SettingsDropdownRow(stringResource(R.string.translation_target), translationLanguages[value.target] ?: value.target, Icons.Default.Language) { dismiss ->
            translationLanguages.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { dismiss(); onChange(value.copy(target = code)) }) }
        }
        if (value.provider == TranslationProvider.CUSTOM) {
            SettingsDropdownRow(stringResource(R.string.translation_source), if (value.source == "auto") stringResource(R.string.translation_auto) else translationLanguages[value.source] ?: value.source, Icons.Default.Language) { dismiss ->
                DropdownMenuItem(text = { Text(stringResource(R.string.translation_auto)) }, onClick = { dismiss(); onChange(value.copy(source = "auto")) })
                translationLanguages.forEach { (code, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { dismiss(); onChange(value.copy(source = code)) }) }
            }
        }
        SettingsDropdownRow(stringResource(R.string.translation_display), stringResource(if (value.bilingual) R.string.translation_bilingual else R.string.translation_only), Icons.Default.ViewAgenda) { dismiss ->
            listOf(true, false).forEach { bilingual -> DropdownMenuItem(text = { Text(stringResource(if (bilingual) R.string.translation_bilingual else R.string.translation_only)) }, onClick = { dismiss(); onChange(value.copy(bilingual = bilingual)) }) }
        }
    }
    if (providersOpen) TranslationProviderSheet(value.provider, {
        onChange(value.copy(provider = it))
        if (it in setOf(TranslationProvider.BAIDU, TranslationProvider.YOUDAO_OPEN, TranslationProvider.CUSTOM)) onConfigure(it)
    }, { providersOpen = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationProviderSheet(selected: TranslationProvider, onSelect: (TranslationProvider) -> Unit, onDismiss: () -> Unit) {
    val providers = TranslationProvider.entries
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.translation_provider), style = MaterialTheme.typography.titleLarge)
            providers.forEach { provider ->
                SelectionRow(translationProviderName(provider), stringResource(when (provider) {
                    TranslationProvider.BAIDU, TranslationProvider.YOUDAO_OPEN -> R.string.translation_provider_key
                    TranslationProvider.CUSTOM -> R.string.translation_provider_custom
                    else -> R.string.translation_provider_anonymous
                }), selected == provider, { onSelect(provider); onDismiss() })
            }
            Text(stringResource(R.string.translation_anonymous_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun TranslationErrorRow(problem: TranslationProblem, retry: () -> Unit, switch: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(translationProblemText(problem), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onClick = retry, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.common_retry), maxLines = 1) }
        TextButton(onClick = switch, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.translation_switch), maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationCredentialsSheet(provider: TranslationProvider, initial: TranslationCredentials, state: TranslationUiState, preferences: TranslationPreferences, viewModel: TranslationViewModel, onDismiss: () -> Unit) {
    var value by remember(provider, initial) { mutableStateOf(initial) }
    LaunchedEffect(value, preferences) { viewModel.clearTest(); viewModel.clearNotice() }
    DisposableEffect(provider) { onDispose { viewModel.clearTest() } }
    var visible by remember { mutableStateOf(false) }
    var methodOpen by remember { mutableStateOf(false) }
    var authOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { if (!state.saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(translationProviderName(provider), style = MaterialTheme.typography.titleLarge)
            if (provider in setOf(TranslationProvider.BAIDU, TranslationProvider.YOUDAO_OPEN)) OutlinedTextField(value.appId, { value = value.copy(appId = it) }, label = { Text(stringResource(R.string.translation_app_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (provider == TranslationProvider.CUSTOM) {
                OutlinedTextField(value.endpoint, { value = value.copy(endpoint = it) }, label = { Text(stringResource(R.string.translation_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { methodOpen = true }) { Text(stringResource(R.string.translation_method) + ": " + value.method) }
                    DropdownMenu(methodOpen, { methodOpen = false }) { listOf("GET", "POST").forEach { method -> DropdownMenuItem(text = { Text(method) }, onClick = { value = value.copy(method = method); methodOpen = false }) } }
                }
                Box {
                    OutlinedButton(onClick = { authOpen = true }) { Text(stringResource(R.string.translation_auth) + ": " + when (value.auth) { TranslationAuth.NONE -> stringResource(R.string.translation_no_auth); TranslationAuth.BEARER -> "Bearer"; TranslationAuth.BODY_KEY -> "api_key"; TranslationAuth.HEADER -> stringResource(R.string.translation_header) }) }
                    DropdownMenu(authOpen, { authOpen = false }) { TranslationAuth.entries.forEach { auth -> DropdownMenuItem(text = { Text(when (auth) { TranslationAuth.NONE -> stringResource(R.string.translation_no_auth); TranslationAuth.BEARER -> "Bearer"; TranslationAuth.BODY_KEY -> "api_key"; TranslationAuth.HEADER -> stringResource(R.string.translation_header) }) }, onClick = { value = value.copy(auth = auth); authOpen = false }) } }
                }
                if (value.auth == TranslationAuth.HEADER) OutlinedTextField(value.header, { value = value.copy(header = it) }, label = { Text(stringResource(R.string.translation_header)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (provider in setOf(TranslationProvider.BAIDU, TranslationProvider.YOUDAO_OPEN) || provider == TranslationProvider.CUSTOM && value.auth != TranslationAuth.NONE) {
                OutlinedTextField(value.secret, { value = value.copy(secret = it) }, label = { Text(stringResource(R.string.translation_secret)) }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.translation_show_secret)) } }, modifier = Modifier.fillMaxWidth())
            }
            if (provider == TranslationProvider.CUSTOM) {
                TextButton(onClick = { value = value.copy(method = "POST", template = TranslationCredentials().template, responsePath = "translatedText") }) { Text(stringResource(R.string.translation_libre)) }
                OutlinedTextField(value.template, { value = value.copy(template = it) }, label = { Text(stringResource(R.string.translation_template)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.translation_template_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value.responsePath, { value = value.copy(responsePath = it) }, label = { Text(stringResource(R.string.translation_response_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            state.notice?.let { Text(translationProblemText(it), color = MaterialTheme.colorScheme.error) }
            if (state.testing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.testProblem?.let { Text(translationProblemText(it), color = MaterialTheme.colorScheme.error) }
            state.testResult?.let { Text(stringResource(R.string.translation_test_success, it), style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.test(preferences.copy(provider = provider), value) }, enabled = !state.testing && !state.saving) { Text(stringResource(R.string.translation_test)) }
                Button(onClick = { viewModel.saveCredentials(provider, value, onDismiss) }, enabled = !state.saving) { Text(stringResource(R.string.common_save)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
