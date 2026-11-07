package com.gitmob.app.data.model

import kotlinx.serialization.Serializable

/** Only fields actually rendered by RepoCard; local snapshots do not invent repository dates. */
@Serializable
data class RepoCardData(
    val name: String,
    val ownerLogin: String,
    val description: String?,
    val homepageUrl: String?,
    val visibility: String,
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

fun RepoListItem.cardData() = RepoCardData(name, ownerLogin, description, homepageUrl,
    visibility.name, isArchived, isFork, forkedFromOwner, forkedFromName, languageName,
    languageColor, stargazerCount, forkCount, openIssueCount, topics, defaultBranchName)

fun RepoDetail.cardData() = RepoCardData(name, ownerLogin, description, homepageUrl,
    visibility, isArchived, isFork, forkedFromOwner, forkedFromName, languageName,
    languageColor, stargazerCount, forkCount, openIssueCount, topics, defaultBranchName)
