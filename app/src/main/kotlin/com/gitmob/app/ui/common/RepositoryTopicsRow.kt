package com.gitmob.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RepositoryTopicsRow(
    topics: List<String>,
    modifier: Modifier = Modifier,
) {
    if (topics.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        topics.forEach { topic ->
            StatusChip(
                text = topic,
                bg = MaterialTheme.colorScheme.primaryContainer,
                fg = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
