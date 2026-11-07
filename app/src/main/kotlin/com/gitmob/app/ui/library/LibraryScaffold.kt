package com.gitmob.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScaffold(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
        }, actions = actions, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        content = content,
    )
}

@Composable
internal fun LibraryLoading(loaded: Boolean, failed: Boolean, retry: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    when {
        loaded -> Column(modifier.fillMaxWidth()) {
            if (failed) {
                TextButton(onClick = retry, modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            // Keep content at the same composition location while the retry action changes.
            Box { content() }
        }
        failed -> Box(modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) { TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) } }
        else -> Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(3) {
                Box(Modifier.fillMaxWidth().height(76.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)))
            }
        }
    }
}

@Composable
internal fun LibraryEmpty(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
