package com.gitmob.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SearchCategory { CODE, REPOSITORIES, ISSUES, PULL_REQUESTS, USERS, ORGANIZATIONS }

/** Continuation belongs to one query and one request capacity; preview and full pages never share it. */
data class SearchContinuation(val cursor: String? = null, val page: Int = 1, val received: Int = 0)

data class SearchPage(
    val items: List<SearchItem>,
    val totalCount: Int,
    val next: SearchContinuation?,
    val incomplete: Boolean = false,
    val limitReached: Boolean = false,
)

sealed interface SearchItem {
    val key: String
    data class Repository(val repo: RepoListItem) : SearchItem { override val key get() = repo.key }
    data class Work(val item: WorkIssueItem) : SearchItem { override val key get() = item.id }
    data class Account(
        val id: String, val login: String, val name: String?, val avatarUrl: String,
        val description: String?, val location: String?,
    ) : SearchItem { override val key get() = id }
    data class Code(
        val owner: String, val repository: String, val name: String, val path: String,
        val htmlUrl: String, val snippets: List<SearchSnippet>,
        val target: SearchFileTarget?,
    ) : SearchItem { override val key get() = "$owner/$repository:$path" }
}

data class SearchSnippet(val text: String, val matches: List<SearchMatch>)
data class SearchMatch(val start: Int, val end: Int)
data class SearchFileTarget(val ref: String, val isCommitSnapshot: Boolean)
