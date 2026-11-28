package com.gitmob.app.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gitmob.app.data.model.RepoEntryType

@Composable
fun FileIcon(
    path: String,
    type: RepoEntryType,
    modifier: Modifier = Modifier,
    languageName: String? = null,
    size: Dp = 24.dp,
) {
    val lightTheme = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val icon = remember(path, type, languageName, lightTheme) {
        FileIconCatalog.resolve(path, type, languageName, lightTheme)
    }
    if (icon != null) {
        Image(imageVector = icon, contentDescription = null, modifier = modifier.size(size))
    } else {
        val directory = type == RepoEntryType.DIRECTORY || type == RepoEntryType.SUBMODULE
        Icon(
            imageVector = if (directory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = if (directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
