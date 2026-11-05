package com.gitmob.app.data.model

/** 真实凭据所属身份，与主页聚合数据及网络 DTO 分开。 */
data class AuthenticatedUser(
    val id: Long,
    val nodeId: String,
    val login: String,
    val name: String?,
    val avatarUrl: String?,
)
