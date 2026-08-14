package com.gitmob.app.data.model

data class RepoListItem(
    val name: String,
    val ownerLogin: String,
    val description: String?,
    val homepageUrl: String?,
    val isPrivate: Boolean,
    val isArchived: Boolean,
    val isFork: Boolean,
    val forkedFromOwner: String?,
    val forkedFromName: String?,
    val languageName: String?,
    val languageColor: String?,
    val stargazerCount: Int,
    val forkCount: Int,
    val openIssueCount: Int,
    val topics: List<String>,
    val defaultBranchName: String?,
)

data class RepoList(
    val totalCount: Int,
    val items: List<RepoListItem>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)
