package com.gitmob.app.data.model

const val DEFAULT_FAVORITE_GROUP = "default"

data class LibrarySettings(
    val favoritesEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val historyLimit: Int = 100,
)

data class FavoriteGroup(val id: String, val name: String, val count: Int, val sortOrder: Int) {
    val isDefault: Boolean get() = id == DEFAULT_FAVORITE_GROUP
}

/** A locally saved item. Favorite payloads are independent of browsing history. */
sealed interface FavoriteItem {
    val id: String
    val kind: String
    val groupId: String?
    val key: String get() = "$kind:$id"
    val title: String

    data class Repository(override val id: String, val card: RepoCardData, override val groupId: String? = null) : FavoriteItem {
        override val kind get() = "repository"
        override val title get() = "${card.ownerLogin}/${card.name}"
    }
    data class Gist(val gist: GistListItem, override val groupId: String? = null) : FavoriteItem {
        override val id get() = gist.apiName
        override val kind get() = "gist"
        override val title get() = listOfNotNull(gist.ownerLogin, gist.previewFile?.name ?: gist.apiName).joinToString("/")
    }
}

data class LibraryState(
    val loaded: Boolean = false,
    val favorites: List<FavoriteItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
)

sealed interface HistoryItem {
    val id: String
    val visitedAt: Long
    val key: String
    val title: String

    data class Repository(override val id: String, val card: RepoCardData, override val visitedAt: Long) : HistoryItem {
        override val key get() = "repository:$id"
        override val title get() = "${card.ownerLogin}/${card.name}"
    }
    data class Gist(override val id: String, val gist: GistListItem, override val visitedAt: Long) : HistoryItem {
        override val key get() = "gist:$id"
        override val title get() = listOfNotNull(gist.ownerLogin, gist.previewFile?.name ?: gist.apiName).joinToString("/")
    }
}

sealed interface LibraryContent {
    data object History : LibraryContent
    data class Group(val id: String) : LibraryContent
    data class Repository(val id: String) : LibraryContent
    data class Gist(val id: String) : LibraryContent
}

sealed interface HistoryLimitResult {
    data object Saved : HistoryLimitResult
    data class NeedsConfirmation(val count: Int) : HistoryLimitResult
}
