package com.gitmob.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepoGitQueryData(val repository: RepoGitRepositoryNode? = null)

@Serializable
data class RepoGitRepositoryNode(
    val id: String,
    val viewerPermission: String? = null,
    val isArchived: Boolean = false,
    val ref: RepoGitRefNode? = null,
    @SerialName("object") val objectNode: RepoGitObjectNode? = null,
)

@Serializable
data class RepoGitRefNode(
    val name: String? = null,
    val target: RepoGitObjectNode? = null,
)

@Serializable
data class RepoGitObjectNode(
    @SerialName("__typename") val typeName: String? = null,
    val oid: String = "",
    val abbreviatedOid: String = "",
    val messageHeadline: String = "",
    val messageBody: String = "",
    val authoredDate: String? = null,
    val committedDate: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changedFilesIfAvailable: Int? = null,
    val url: String? = null,
    val author: RepoGitActorNode? = null,
    val committer: RepoGitActorNode? = null,
    val parents: RepoGitParentConnectionNode? = null,
    val history: RepoGitCommitHistoryConnectionNode? = null,
    val entries: List<RepoGitTreeEntryNode> = emptyList(),
    val byteSize: Int = 0,
    val isBinary: Boolean? = null,
    val isTruncated: Boolean = false,
    val text: String? = null,
)

@Serializable
data class RepoGitActorNode(
    val name: String? = null,
    val email: String? = null,
    val date: String? = null,
    val avatarUrl: String? = null,
    val user: RepoGitUserNode? = null,
)

@Serializable
data class RepoGitUserNode(
    val login: String,
    val avatarUrl: String? = null,
)

@Serializable
data class RepoGitParentConnectionNode(val nodes: List<RepoGitObjectNode> = emptyList())

@Serializable
data class RepoGitCommitHistoryConnectionNode(
    val totalCount: Int = 0,
    val nodes: List<RepoGitObjectNode> = emptyList(),
    val pageInfo: PageInfoNode,
)

@Serializable
data class RepoGitTreeEntryNode(
    val name: String,
    val path: String? = null,
    val type: String,
    val oid: String,
    val size: Int = 0,
    val extension: String? = null,
    val language: LanguageNode? = null,
    val submodule: RepoGitSubmoduleNode? = null,
)

@Serializable
data class RepoGitSubmoduleNode(val name: String? = null)

@Serializable
data class RepoGitCommitMutationData(
    val createCommitOnBranch: RepoGitCommitMutationPayload? = null,
)

@Serializable
data class RepoGitCommitMutationPayload(
    val commit: RepoGitObjectNode? = null,
    val ref: RepoGitRefNode? = null,
)

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

@Serializable
data class RestGitBlob(
    val sha: String,
    val content: String,
    val encoding: String,
    val size: Int = 0,
)
