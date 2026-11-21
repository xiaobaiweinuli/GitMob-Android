package com.gitmob.app.data.model

import kotlinx.serialization.Serializable

enum class RepoLinkKind { FILE, DIRECTORY, HISTORY }

@Serializable
sealed interface RepoContentSource {
    @Serializable
    data class Link(val refAndPath: List<String>) : RepoContentSource
}

@Serializable
data class RepoLinkTarget(val ref: String, val path: String) : RepoContentSource
