package com.gitmob.app.data.model

import com.gitmob.app.core.diff.UnifiedDiff
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class GistUser(
    val login: String,
    val avatarUrl: String?,
    val url: String?,
)

data class GistFile(
    val filename: String,
    val type: String?,
    val language: String?,
    val rawUrl: String,
    val size: Int,
    val isTruncated: Boolean,
    val content: String?,
) {
    val isText: Boolean
        get() = !isTruncated && content != null && '\u0000' !in content
}

data class GistDetail(
    val id: String,
    val nodeId: String,
    val url: String,
    val description: String?,
    val isPublic: Boolean,
    val owner: GistUser?,
    val files: List<GistFile>,
    val commentCount: Int,
    val commentsEnabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val forkOf: GistForkSource?,
    val latestVersion: String?,
    val viewerLogin: String,
    val isStarred: Boolean,
    val stargazerCount: Int,
    val forkCount: Int,
) {
    val isOwnedByViewer: Boolean
        get() = owner?.login?.equals(viewerLogin, ignoreCase = true) == true
}

data class GistForkSource(
    val id: String,
    val owner: GistUser?,
    val url: String,
)

data class GistRevision(
    val version: String,
    val user: GistUser?,
    val committedAt: String,
    val additions: Int,
    val deletions: Int,
    val totalChanges: Int,
)

data class GistRevisionPage(
    val items: List<GistRevision>,
    val nextPage: Int?,
)

data class GistFork(
    val id: String,
    val owner: GistUser?,
    val createdAt: String,
    val updatedAt: String,
)

data class GistForkPage(
    val items: List<GistFork>,
    val nextPage: Int?,
)

data class GistComment(
    val id: Long,
    val nodeId: String,
    val url: String,
    val body: String,
    val bodyHtml: String,
    val author: GistUser?,
    val createdAt: String,
    val updatedAt: String,
    val authorAssociation: CommentAuthorAssociation,
)

data class GistCommentPage(
    val items: List<GistComment>,
    val nextPage: Int?,
)

enum class GistDiffUnavailableReason {
    BINARY,
    TRUNCATED,
    MISSING_CONTENT,
}

data class GistFileDiff(
    val filename: String,
    val diff: UnifiedDiff?,
    val unavailableReason: GistDiffUnavailableReason? = null,
)

data class GistRevisionComparison(
    val revision: GistRevision,
    val previousVersion: String?,
    val files: List<GistFileDiff>,
)

@Serializable
internal data class GistDetailDto(
    val id: String = "",
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val description: String? = null,
    val public: Boolean = false,
    val owner: GistUserDto? = null,
    val user: GistUserDto? = null,
    val files: Map<String, GistFileDto> = emptyMap(),
    val comments: Int = 0,
    @SerialName("comments_enabled") val commentsEnabled: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("fork_of") val forkOf: GistForkSourceDetailDto? = null,
    val history: List<GistRevisionDto> = emptyList(),
)

@Serializable
internal data class GistFileDto(
    val filename: String = "",
    val type: String? = null,
    val language: String? = null,
    @SerialName("raw_url") val rawUrl: String = "",
    val size: Int = 0,
    val truncated: Boolean = false,
    val content: String? = null,
)

@Serializable
internal data class GistUserDto(
    val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
internal data class GistForkSourceDetailDto(
    val id: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val owner: GistUserDto? = null,
    val user: GistUserDto? = null,
)

@Serializable
internal data class GistChangeStatusDto(
    val total: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
internal data class GistRevisionDto(
    val version: String = "",
    val user: GistUserDto? = null,
    @SerialName("committed_at") val committedAt: String = "",
    @SerialName("change_status") val changeStatus: GistChangeStatusDto = GistChangeStatusDto(),
)

@Serializable
internal data class GistForkDto(
    val id: String = "",
    val owner: GistUserDto? = null,
    val user: GistUserDto? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
internal data class GistCommentDto(
    val id: Long = 0,
    @SerialName("node_id") val nodeId: String = "",
    val url: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String = "",
    @SerialName("body_html") val bodyHtml: String? = null,
    val user: GistUserDto? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("author_association") val authorAssociation: String = "NONE",
)

@Serializable
internal data class GistUpdateRequest(
    val description: String? = null,
    val files: Map<String, GistFileUpdateDto?> = emptyMap(),
)

@Serializable
internal data class GistFileUpdateDto(
    val filename: String? = null,
    val content: String? = null,
)

@Serializable
internal data class GistCreateRequest(
    val description: String? = null,
    @SerialName("public") val isPublic: Boolean,
    val files: Map<String, GistCreateFileRequest>,
)

@Serializable
internal data class GistCreateFileRequest(val content: String)

@Serializable
internal data class GistCreateResponseDto(val id: String = "")

@Serializable
internal data class GistCommentRequest(val body: String)
