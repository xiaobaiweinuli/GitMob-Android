package com.gitmob.app.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.gitmob.app.data.model.RepoEntryType

internal object FileIconCatalog {
    fun resolve(path: String, type: RepoEntryType, languageName: String?, lightTheme: Boolean): ImageVector? {
        val icon = when (type) {
            RepoEntryType.SUBMODULE -> "folder-git"
            RepoEntryType.SYMLINK -> "folder-symlink"
            RepoEntryType.DIRECTORY -> namedIcon(GeneratedFileIcons.folderNames, path)
            RepoEntryType.FILE -> namedIcon(GeneratedFileIcons.fileNames, path)
                ?: extensionIcon(path.substringAfterLast('/'))
                ?: languageName?.let { lookup(GeneratedFileIcons.languageIds, it) }
            RepoEntryType.UNKNOWN -> null
        } ?: return null
        // A matched generic icon stops the lookup and uses the theme-aware fallback.
        if (icon == "file" || icon == "folder") return null
        val name = if (lightTheme) GeneratedFileIcons.lightVariants[icon] ?: icon else icon
        return GeneratedFileIcons.icons.getValue(name).invoke()
    }

    private fun namedIcon(associations: Map<String, String>, path: String): String? {
        var candidate = path.trim('/')
        while (candidate.isNotEmpty()) {
            lookup(associations, candidate)?.let { return it }
            candidate = candidate.substringAfter('/', "")
        }
        return null
    }

    private fun extensionIcon(name: String): String? {
        var suffix = name
        while ('.' in suffix) {
            suffix = suffix.substringAfter('.')
            lookup(GeneratedFileIcons.fileExtensions, suffix)?.let { return it }
            lookup(GeneratedFileIcons.languageIds, suffix)?.let { return it }
        }
        return null
    }

    private fun lookup(associations: Map<String, String>, key: String): String? =
        associations[key] ?: associations[key.lowercase()]
}
