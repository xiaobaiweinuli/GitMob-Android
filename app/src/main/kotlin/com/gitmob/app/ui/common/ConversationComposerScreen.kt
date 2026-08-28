package com.gitmob.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.navigation.ConversationComposerRoute
import com.gitmob.app.navigation.ConversationComposerTarget

data class ConversationComposeRequest(
    val target: ConversationComposerTarget,
    val subjectId: String,
    val initialText: String = "",
    val commentId: String? = null,
    val replyToId: String? = null,
    val reviewEvent: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationComposerScreen(
    route: ConversationComposerRoute,
    onBack: () -> Unit,
    viewModel: ConversationComposerViewModel = hiltViewModel(),
) {
    LaunchedEffect(route) {
        viewModel.init(route.owner, route.name, route.number, route.target, route.subjectId, route.initialText, route.commentId, route.replyToId, route.reviewEvent)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(route.initialText, TextRange(route.initialText.length))) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = state.text != route.initialText

    LaunchedEffect(state.text) {
        if (editorValue.text != state.text) editorValue = editorValue.copy(text = state.text, selection = TextRange(state.text.length))
    }
    LaunchedEffect(state.submitted) { if (state.submitted) onBack() }

    fun requestBack() {
        if (dirty && !state.isSubmitting) confirmDiscard = true else if (!state.isSubmitting) onBack()
    }
    BackHandler(onBack = ::requestBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(composerTitle(route)) },
                navigationIcon = { IconButton(onClick = ::requestBack, enabled = !state.isSubmitting) { Icon(Icons.Default.Close, stringResource(R.string.common_cancel)) } },
                actions = {
                    IconButton(onClick = viewModel::submit, enabled = (state.text.isNotBlank() || state.allowsEmptySubmission) && !state.isSubmitting) {
                        if (state.isSubmitting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.conversation_send))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .fitInside(WindowInsetsRulers.Ime.current),
        ) {
            MarkdownBodyEditor(
                value = editorValue,
                state = state.bodyEditor,
                onValueChange = {
                    editorValue = it
                    viewModel.updateText(it.text)
                },
                onTabSelected = viewModel::selectTab,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.conversation_discard_title)) },
        text = { Text(stringResource(R.string.conversation_discard_message)) },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = { TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.conversation_discard)) } },
    )
}

@Composable
private fun composerTitle(route: ConversationComposerRoute): String = stringResource(
    when {
        route.commentId != null -> R.string.conversation_edit_comment
        route.target == ConversationComposerTarget.PULL_REQUEST_REVIEW -> R.string.pr_review
        route.replyToId != null || route.target == ConversationComposerTarget.PULL_REQUEST_THREAD -> R.string.conversation_reply
        else -> R.string.conversation_write_comment
    },
)
