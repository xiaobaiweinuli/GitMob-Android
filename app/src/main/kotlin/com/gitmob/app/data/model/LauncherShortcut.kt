package com.gitmob.app.data.model

import android.net.Uri

enum class ShortcutKind { REPOSITORY, GIST }

data class LauncherShortcut(
    val id: String,
    val kind: ShortcutKind,
    val title: String,
    val subtitle: String,
    val url: String,
    val rank: Int = 0,
    val dynamic: Boolean = false,
    val pinned: Boolean = false,
) {
    companion object {
        fun repository(detail: RepoDetail) = LauncherShortcut(
            id = "repo:" + detail.id,
            kind = ShortcutKind.REPOSITORY,
            title = detail.name,
            subtitle = detail.ownerLogin + "/" + detail.name,
            url = Uri.Builder().scheme("https").authority("github.com")
                .appendPath(detail.ownerLogin).appendPath(detail.name).build().toString(),
        )

        fun gist(detail: GistDetail, fallbackTitle: String) = LauncherShortcut(
            id = "gist:" + detail.id,
            kind = ShortcutKind.GIST,
            title = detail.description?.takeIf { it.isNotBlank() }
                ?: detail.files.firstOrNull()?.filename ?: fallbackTitle,
            subtitle = listOfNotNull(detail.owner?.login, detail.id).joinToString("/"),
            url = Uri.Builder().scheme("https").authority("gist.github.com")
                .appendPath(detail.id).build().toString(),
        )
    }
}

data class LauncherShortcutState(
    val shortcuts: List<LauncherShortcut>,
    val capacity: Int,
    val pinSupported: Boolean,
) {
    val dynamic: List<LauncherShortcut> get() = shortcuts.filter { it.dynamic }.sortedBy { it.rank }
    val pinned: List<LauncherShortcut> get() = shortcuts.filter { it.pinned }
}

sealed interface ShortcutPublishResult {
    data object Saved : ShortcutPublishResult
    data object Full : ShortcutPublishResult
    data object PinRequested : ShortcutPublishResult
    data object PinUnavailable : ShortcutPublishResult
}
