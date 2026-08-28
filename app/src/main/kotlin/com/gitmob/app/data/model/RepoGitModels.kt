package com.gitmob.app.data.model

import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission

data class RepoGitActor(
    val login: String?,
    val displayName: String?,
    val email: String?,
    val avatarUrl: String?,
    val date: String?,
)

data class RepoCommitSummary(
    val oid: String,
    val abbreviatedOid: String,
    val headline: String,
    val body: String,
    val authoredDate: String?,
    val committedDate: String?,
    val author: RepoGitActor?,
    val committer: RepoGitActor?,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int?,
    val parentOids: List<String> = emptyList(),
    val url: String? = null,
) {
    val isMergeCommit: Boolean get() = parentOids.size > 1
}

enum class RepoEntryType { FILE, DIRECTORY, SUBMODULE, UNKNOWN }

data class RepoTreeEntry(
    val name: String,
    val path: String,
    val type: RepoEntryType,
    val oid: String,
    val size: Long?,
    val extension: String?,
    val languageName: String?,
)

data class RepoCodeTree(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val isArchived: Boolean,
    val ref: String,
    val headOid: String,
    val path: String,
    val entries: List<RepoTreeEntry>,
)

data class RepoFileContent(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val isArchived: Boolean,
    val ref: String,
    val headOid: String,
    val path: String,
    val oid: String,
    val byteSize: Long,
    val isBinary: Boolean,
    val isTruncated: Boolean,
    val text: String?,
)

enum class RepoChangedFileStatus { ADDED, MODIFIED, DELETED, RENAMED, COPIED, UNKNOWN }

data class RepoChangedFile(
    val filename: String,
    val previousFilename: String?,
    val status: RepoChangedFileStatus,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val patch: String?,
    val blobUrl: String?,
    val rawUrl: String?,
    val contentsUrl: String?,
    val oid: String?,
)

data class RepoCommitDetail(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val isArchived: Boolean,
    val commit: RepoCommitSummary,
    val changedFiles: List<RepoChangedFile>,
    val changedFilesTruncated: Boolean,
)

data class RepoComparisonRefs(
    val baseOwner: String,
    val baseRepository: String,
    val baseRef: String,
    val headOwner: String,
    val headRepository: String,
    val headRef: String,
)

data class RepoComparison(
    val refs: RepoComparisonRefs,
    val status: String,
    val aheadBy: Int,
    val behindBy: Int,
    val totalCommits: Int,
    val commits: List<RepoCommitSummary>,
    val files: List<RepoChangedFile>,
    val additions: Int,
    val deletions: Int,
    val filesTruncated: Boolean,
    val commitsPage: Int,
    val commitsHasNextPage: Boolean,
)

sealed interface RepoComparisonResult {
    data class Available(val comparison: RepoComparison) : RepoComparisonResult
    data object NoCommonAncestor : RepoComparisonResult
}

data class PagedRepoCommits(
    val repositoryId: String,
    val permission: RepoPermission,
    val capabilities: RepoCapabilities,
    val isArchived: Boolean,
    val ref: String,
    val headOid: String,
    val totalCount: Int,
    val items: List<RepoCommitSummary>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

sealed interface RepoPendingFileChange {
    val path: String

    data class Addition(
        override val path: String,
        val contentBase64: String,
    ) : RepoPendingFileChange

    data class Deletion(
        override val path: String,
    ) : RepoPendingFileChange
}

