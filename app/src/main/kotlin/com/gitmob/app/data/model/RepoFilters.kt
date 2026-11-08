package com.gitmob.app.data.model

import java.util.Locale

/** Includes API values even when a value is not offered as a list filter. */
enum class RepoVisibility { PUBLIC, PRIVATE, INTERNAL }

enum class RepoSort {
    UPDATED_DESC, UPDATED_ASC, STARS_ASC, STARS_DESC, NAME_DESC, NAME_ASC,
}

data class RepoFilters(
    val query: String = "",
    val visibility: RepoVisibility? = null,
    val isArchived: Boolean? = null,
    val isFork: Boolean? = null,
    val sort: RepoSort = RepoSort.UPDATED_DESC,
) {
    fun normalized(): RepoFilters = copy(
        query = query.trim().lowercase(Locale.ROOT),
        visibility = visibility?.takeUnless { it == RepoVisibility.INTERNAL },
    )
}
