package com.gitmob.app.data.model

import java.time.Instant
import java.util.Locale

data class RepoListItem(
    val name: String,
    val ownerLogin: String,
    val description: String?,
    val homepageUrl: String?,
    val visibility: RepoVisibility,
    val isArchived: Boolean,
    val isFork: Boolean,
    val hasIssuesEnabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pushedAt: Instant?,
    val forkedFromOwner: String?,
    val forkedFromName: String?,
    val languageName: String?,
    val languageColor: String?,
    val stargazerCount: Int,
    val forkCount: Int,
    val openIssueCount: Int,
    val topics: List<String>,
    val defaultBranchName: String?,
) {
    val key: String get() = "$ownerLogin/$name".lowercase(Locale.ROOT)
}

data class RepoList(
    val totalCount: Int,
    val items: List<RepoListItem>,
    val hasNextPage: Boolean,
    val endCursor: String?,
    val ownerContext: RepositoryListOwnerContext? = null,
    val requiresManualContinue: Boolean = false,
)
