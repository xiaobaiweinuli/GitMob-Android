package com.gitmob.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CodeSearchResponse(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("incomplete_results") val incomplete: Boolean,
    val items: List<CodeSearchDto>,
)
@Serializable
internal data class CodeSearchDto(
    val name: String, val path: String,
    @SerialName("html_url") val htmlUrl: String,
    val repository: CodeSearchRepository,
    @SerialName("text_matches") val textMatches: List<CodeSearchTextMatch> = emptyList(),
)
@Serializable
internal data class CodeSearchRepository(val name: String, val owner: CodeSearchOwner)
@Serializable
internal data class CodeSearchOwner(val login: String)
@Serializable
internal data class CodeSearchTextMatch(
    val property: String? = null, val fragment: String = "",
    val matches: List<CodeSearchIndices> = emptyList(),
)
@Serializable
internal data class CodeSearchIndices(
    val indices: List<Int> = emptyList(),
    val text: String? = null,
)
