package com.gitmob.app.ui.translation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoDocumentContext
import com.gitmob.app.data.translation.*
import com.gitmob.app.ui.common.MarkdownWebView
import com.gitmob.app.ui.common.RepoDocumentResources

class TranslationController(val state: TranslationUiState, val viewModel: TranslationViewModel, val hint: (Int) -> Unit) {
    val enabled: Boolean get() = state.configuration?.let { it.preferences.enabled && !it.storageFailed } == true
    fun open(target: TranslationTarget) = viewModel.open(target)
}
val LocalTranslationController = staticCompositionLocalOf<TranslationController?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationHost(viewModel: TranslationViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var hint by remember { mutableStateOf<Int?>(null) }
    var configure by remember { mutableStateOf<TranslationProvider?>(null) }
    CompositionLocalProvider(LocalTranslationController provides TranslationController(state, viewModel) { hint = it }) { content() }
    if (state.optionsOpen && configure == null) ModalBottomSheet(onDismissRequest = viewModel::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.translation_options), style = MaterialTheme.typography.titleLarge)
            TranslationPreferencesEditor(state.options, viewModel::options) { configure = it }
            state.notice?.let { Text(translationProblemText(it), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                state.selected?.takeIf { it.identity in state.entries }?.let { target ->
                    TextButton(onClick = { viewModel.restore(target) }) { Text(stringResource(R.string.translation_restore)) }
                }
                Button(onClick = { viewModel.request() }) { Text(stringResource(R.string.translation_start)) }
            }
        }
    }
    configure?.let { provider ->
        TranslationCredentialsSheet(provider, state.configuration?.credentialsFor(provider) ?: TranslationCredentials(), state, state.options, viewModel) { configure = null }
    }
    hint?.let { message -> AlertDialog(onDismissRequest = { hint = null }, text = { Text(stringResource(message)) }, confirmButton = { TextButton(onClick = { hint = null }) { Text(stringResource(R.string.common_confirm)) } }) }
}

@Composable
fun TranslationMenuItem(target: TranslationTarget, onDismiss: () -> Unit, label: Int = R.string.translation_start, enabled: Boolean = true) {
    val controller = LocalTranslationController.current ?: return
    if (!controller.enabled) return
    DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(Icons.Default.Translate, null) }, enabled = enabled && target.content.isNotBlank(), onClick = { onDismiss(); controller.open(target) })
}

@Composable
fun TranslationFileMenuItem(target: TranslationTarget?, onDismiss: () -> Unit, onOpen: () -> Unit = {}) {
    if (target == null || !target.html || target.content.isBlank()) return
    val controller = LocalTranslationController.current ?: return
    if (!controller.enabled) return
    DropdownMenuItem(text = { Text(stringResource(R.string.translation_start)) }, leadingIcon = { Icon(Icons.Default.Translate, null) }, onClick = {
        onDismiss(); onOpen(); controller.open(target)
    })
}

@Composable
fun translationProgressText(entry: TranslationEntry): String {
    var now by remember(entry.waitingUntil) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry.phase, entry.waitingUntil) {
        while (entry.phase == TranslationPhase.WAITING && now < entry.waitingUntil) { delay(500); now = System.currentTimeMillis() }
    }
    return when (entry.phase) {
        TranslationPhase.PREPARING -> stringResource(R.string.translation_preparing)
        TranslationPhase.WAITING -> stringResource(R.string.translation_waiting, ((entry.waitingUntil - now).coerceAtLeast(0) + 999) / 1000)
        else -> stringResource(R.string.translation_working, entry.completed, entry.total)
    }
}

@Composable
fun TranslationStatus(target: TranslationTarget, modifier: Modifier = Modifier, completedOnly: Boolean = false) {
    val controller = LocalTranslationController.current ?: return
    val entry = controller.state.entries[target.identity] ?: return
    if (entry.showOriginal) return
    val done = entry.phase == TranslationPhase.COMPLETE && entry.problem == null && !entry.displayProblem && entry.completed == entry.total
    if (done != completedOnly) return
    Column(modifier.fillMaxWidth()) {
        when {
            entry.displayProblem -> TranslationErrorRow(TranslationProblem.DISPLAY, { controller.viewModel.retry(target) }, { controller.open(target) })
            entry.running -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(translationProgressText(entry), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.viewModel.cancel(target) }) { Text(stringResource(R.string.common_cancel)) }
            }
            entry.problem != null -> TranslationErrorRow(entry.problem, { controller.viewModel.retry(target) }, { controller.open(target) })
            entry.phase == TranslationPhase.CANCELLED || entry.completed < entry.total -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.translation_cancelled), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.viewModel.retry(target) }) { Text(stringResource(R.string.common_retry)) }
                TextButton(onClick = { controller.open(target) }) { Text(stringResource(R.string.translation_switch)) }
            }
            else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.translation_done, translationProviderName(entry.preferences.provider)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { controller.open(target) }) { Text(stringResource(R.string.translation_options)) }
            }
        }
    }
}

@Composable
fun TranslationHtml(
    translationKey: String,
    bodyHtml: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    documentResources: RepoDocumentResources? = null,
    onDocumentLink: (RepoDocumentContext) -> Unit = {},
    onExternalLink: ((String) -> Unit)? = null,
) {
    val target = remember(translationKey, bodyHtml) { TranslationTarget(translationKey, bodyHtml, true) }
    val controller = LocalTranslationController.current
    val entry = controller?.state?.entries?.get(target.identity)
    val presentation = entry?.document?.let { document ->
        TranslationPresentation(document, entry.translations, entry.version,
            if (entry.showOriginal) "original" else if (entry.preferences.bilingual) "bilingual" else "translation", entry.generation)
    }
    Column(modifier) {
        TranslationStatus(target, Modifier.padding(horizontal = 8.dp))
        MarkdownWebView(bodyHtml, backgroundColor = backgroundColor, documentResources = documentResources,
            onDocumentLink = onDocumentLink, onExternalLink = onExternalLink, translation = presentation,
            displayAttempt = entry?.displayAttempt ?: 0,
            onTranslationDisplayError = { controller?.viewModel?.displayFailed(target, entry?.generation ?: -1) })
        TranslationStatus(target, Modifier.padding(horizontal = 8.dp), completedOnly = true)
    }
}

@Composable
fun TranslationText(target: TranslationTarget, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, fontWeight: FontWeight? = null, showStatus: Boolean = false) {
    val entry = LocalTranslationController.current?.state?.entries?.get(target.identity)
    val document = entry?.document
    val translations = if (entry?.showOriginal == true) emptyMap() else entry?.translations.orEmpty()
    val bilingual = entry?.preferences?.bilingual == true
    val translated = remember(document, translations, bilingual) {
        if (translations.isEmpty()) null else document?.renderText(translations, translatedOnly = bilingual)
    }
    val quoteColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        if (showStatus) TranslationStatus(target)
        if (translated == null || bilingual) Text(target.content, style = style, fontWeight = fontWeight,
            color = if (translated != null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        if (!translated.isNullOrBlank()) Text(translated, style = style, fontWeight = fontWeight,
            modifier = if (bilingual) Modifier.padding(top = 8.dp).drawBehind { drawLine(quoteColor, Offset.Zero, Offset(0f, size.height), 3.dp.toPx()) }.padding(start = 12.dp) else Modifier)
        if (showStatus) TranslationStatus(target, completedOnly = true)
    }
}
