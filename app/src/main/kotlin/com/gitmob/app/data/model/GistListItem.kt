
package com.gitmob.app.data.model

enum class GistCategory {
    ORIGINAL,
    FORKED,
}

enum class GistSort(
    internal val graphQLField: String,
    internal val graphQLDirection: String,
) {
    RECENTLY_CREATED("CREATED_AT", "DESC"),
    RECENTLY_UPDATED("UPDATED_AT", "DESC"),
    OLDEST_CREATED("CREATED_AT", "ASC"),
    OLDEST_UPDATED("UPDATED_AT", "ASC"),
}

data class GistFilePreview(
    val name: String?,
    val text: String?,
    val size: Int?,
    val isTruncated: Boolean,
    val isImage: Boolean,
    val languageName: String?,
    val languageColor: String?,
)

data class GistListItem(
    val id: String,
    val apiName: String,
    val ownerLogin: String?,
    val description: String?,
    val url: String,
    val isPublic: Boolean,
    val isFork: Boolean,
    val isOwnedByViewer: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val stargazerCount: Int,
    val commentCount: Int,
    val previewFile: GistFilePreview?,
    val fileCount: Int,
    val isFileCountCapped: Boolean,
)

data class GistPage(
    val items: List<GistListItem>,
    val hasNextPage: Boolean,
    val nextCursor: String?,
)
