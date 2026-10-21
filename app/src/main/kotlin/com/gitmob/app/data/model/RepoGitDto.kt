package com.gitmob.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 本文件只剩 REST DTO（compare / commit files），GraphQL 部分已全部迁移到 Apollo 生成模型。

@Serializable
data class RestRepoCommitResponse(
    val sha: String,
    val files: List<RestRepoCommitFile> = emptyList(),
)

@Serializable
data class RestRepoCompareResponse(
    val status: String = "",
    @SerialName("ahead_by") val aheadBy: Int = 0,
    @SerialName("behind_by") val behindBy: Int = 0,
    @SerialName("total_commits") val totalCommits: Int = 0,
    val commits: List<RestRepoCompareCommit> = emptyList(),
    val files: List<RestRepoCommitFile> = emptyList(),
)

@Serializable
data class RestRepoCompareCommit(
    val sha: String,
    val commit: RestRepoCompareCommitData,
    val author: RestRepoCompareActor? = null,
    val committer: RestRepoCompareActor? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class RestRepoCompareCommitData(
    val message: String = "",
    @SerialName("author") val authorData: RestRepoCompareSignature? = null,
    @SerialName("committer") val committerData: RestRepoCompareSignature? = null,
)

@Serializable
data class RestRepoCompareSignature(
    val name: String? = null,
    val email: String? = null,
    val date: String? = null,
)

@Serializable
data class RestRepoCompareActor(
    val login: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class RestRepoCommitFile(
    val sha: String? = null,
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    @SerialName("previous_filename") val previousFilename: String? = null,
    val patch: String? = null,
    @SerialName("blob_url") val blobUrl: String? = null,
    @SerialName("raw_url") val rawUrl: String? = null,
    @SerialName("contents_url") val contentsUrl: String? = null,
)
