package com.gitmob.app.data.model

data class RepoReleaseAsset(
    val id: Long,
    val name: String,
    val label: String?,
    val contentType: String,
    val state: String,
    val size: Long,
    val downloadCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

data class RepoRelease(
    val id: Long,
    val nodeId: String,
    val tagName: String,
    val targetCommitish: String,
    val name: String?,
    val body: String,
    val bodyHtml: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val createdAt: String,
    val publishedAt: String?,
    val author: SimpleUser?,
    val uploadUrl: String,
    val assets: List<RepoReleaseAsset>,
    val tarballUrl: String?,
    val zipballUrl: String?,
)

data class RepoReleasePage(val items: List<RepoRelease>, val page: Int, val hasNextPage: Boolean)
data class ReleaseNotes(val name: String?, val body: String)

data class SaveRepoReleaseInput(
    val tagName: String,
    val targetCommitish: String,
    val name: String?,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
)
