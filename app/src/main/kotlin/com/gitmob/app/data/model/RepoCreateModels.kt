package com.gitmob.app.data.model

/** The accounts that can own a newly created repository. */
enum class RepositoryCreateOwnerType {
    USER,
    ORGANIZATION,
}

data class RepositoryCreateOwner(
    val id: String,
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val type: RepositoryCreateOwnerType,
    val canCreateRepository: Boolean,
)

data class RepositoryCreateInput(
    val owner: RepositoryCreateOwner,
    val name: String,
    val description: String?,
    val isPrivate: Boolean,
    val addReadme: Boolean,
    val licenseTemplate: String?,
    val gitignoreTemplate: String?,
)

data class CreatedRepository(
    val owner: String,
    val name: String,
)

data class RepositoryCreateOwnerPage(
    val viewer: RepositoryCreateOwner,
    val organizations: List<RepositoryCreateOwner>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

data class RepositoryLicense(
    val key: String,
    val name: String,
)

/** Owner context returned with a repository list so UI never guesses write capability. */
data class RepositoryListOwnerContext(
    val owner: RepositoryCreateOwner,
)
