package com.gitmob.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.gitmob.app.R

/** Shared title-slot input; the screen owns expansion and the ViewModel owns the submitted query. */
@Composable
fun RepositorySearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    hasSubmittedQuery: Boolean,
    onSubmit: (String) -> Unit,
    focusOnExpand: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (!focusOnExpand) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboard?.show()
    }
    fun submit() {
        onSubmit(value.text)
        keyboard?.hide()
        focusManager.clearFocus()
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        trailingIcon = if (value.text.isNotEmpty() || hasSubmittedQuery) {{
            IconButton(onClick = {
                onValueChange(TextFieldValue())
                onSubmit("")
                focusRequester.requestFocus()
                keyboard?.show()
            }) { Icon(Icons.Default.Close, stringResource(R.string.repos_clear_search)) }
        }} else null,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent {
            if (value.composition == null && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                if (it.type == KeyEventType.KeyUp) submit()
                true
            } else false
        },
    )
}

@Composable
fun RepositorySearchButton(active: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Icon(Icons.Default.Search, stringResource(R.string.repo_list_search)) }
}
