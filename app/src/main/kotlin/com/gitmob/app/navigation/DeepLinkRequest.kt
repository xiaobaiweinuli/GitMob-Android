package com.gitmob.app.navigation

import com.gitmob.app.data.model.RepoLinkKind
import java.io.Serializable
import java.util.UUID

/** Repository links retain their ref/path segments for resolution in the destination page. */
sealed interface DeepLinkRequest : Serializable {
    data class Direct(val destination: DeepLinkDestination) : DeepLinkRequest
    data class RepositoryContent(
        val owner: String,
        val repo: String,
        val kind: RepoLinkKind,
        val refAndPath: List<String>,
    ) : DeepLinkRequest
}

data class PendingDeepLink(
    val request: DeepLinkRequest,
    val id: String = UUID.randomUUID().toString(),
) : Serializable
