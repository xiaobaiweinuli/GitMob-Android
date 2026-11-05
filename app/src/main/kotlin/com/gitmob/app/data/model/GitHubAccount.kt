package com.gitmob.app.data.model

enum class AccountAuthMethod { PAT, DEVICE_FLOW, LEGACY }

/** 旧凭据保留独立 key；只有验证 /user 后才获得稳定的 GitHub ID。 */
data class GitHubAccount(
    val key: String,
    val id: Long?,
    val nodeId: String?,
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val authMethod: AccountAuthMethod = AccountAuthMethod.PAT,
    val needsReauthentication: Boolean = false,
)

data class AccountState(
    val isLoaded: Boolean = false,
    val accounts: List<GitHubAccount> = emptyList(),
    val activeAccount: GitHubAccount? = null,
    val sessionId: String = "",
)
