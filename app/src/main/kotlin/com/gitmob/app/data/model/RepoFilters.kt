package com.gitmob.app.data.model

import java.time.Instant
import java.util.Locale

enum class RepoVisibility { PUBLIC, PRIVATE, INTERNAL }

enum class RepoSort {
    UPDATED_DESC, CREATED_ASC, CREATED_DESC, UPDATED_ASC, PUSHED_ASC, PUSHED_DESC,
    STARS_ASC, STARS_DESC, NAME_DESC, NAME_ASC;

    fun sorted(repos: List<RepoListItem>): List<RepoListItem> {
        val primary: Comparator<RepoListItem> = when (this) {
            UPDATED_DESC -> compareByDescending { it.updatedAt }
            CREATED_ASC -> compareBy { it.createdAt }
            CREATED_DESC -> compareByDescending { it.createdAt }
            UPDATED_ASC -> compareBy { it.updatedAt }
            PUSHED_ASC -> compareBy(nullsLast<Instant>()) { it.pushedAt }
            PUSHED_DESC -> compareBy(nullsLast(reverseOrder<Instant>())) { it.pushedAt }
            STARS_ASC -> compareBy { it.stargazerCount }
            STARS_DESC -> compareByDescending { it.stargazerCount }
            NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
            NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        return repos.sortedWith(primary.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}

data class RepoFilters(
    val query: String = "",
    val visibility: RepoVisibility? = null,
    val isArchived: Boolean? = null,
    val isFork: Boolean? = null,
    val hasIssuesEnabled: Boolean? = null,
    val sort: RepoSort = RepoSort.UPDATED_DESC,
) {
    fun normalized(): RepoFilters = copy(query = query.trim().lowercase(Locale.ROOT))

    fun matchesSearch(repo: RepoListItem): Boolean {
        val text = query.trim()
        return repo.name.contains(text, ignoreCase = true) ||
            repo.description?.contains(text, ignoreCase = true) == true
    }
}
