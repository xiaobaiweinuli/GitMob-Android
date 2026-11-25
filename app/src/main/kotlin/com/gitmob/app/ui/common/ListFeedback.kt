package com.gitmob.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gitmob.app.R

enum class ListFeedbackState { CONTENT, LOADING, EMPTY, ERROR }

/** Overlay transitions leave the underlying list and pagination effects in place. */
@Composable
fun ListFeedback(
    state: ListFeedbackState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = stringResource(R.string.common_empty),
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        modifier = modifier.fillMaxSize(),
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        label = "List feedback",
    ) { status ->
        if (status != ListFeedbackState.CONTENT) {
            Column(Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                when (status) {
                    ListFeedbackState.LOADING -> CircularProgressIndicator()
                    ListFeedbackState.EMPTY -> Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ListFeedbackState.ERROR -> {
                        Text(stringResource(R.string.common_load_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRetry, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.common_retry)) }
                    }
                    ListFeedbackState.CONTENT -> Unit
                }
            }
        }
    }
}
