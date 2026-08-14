package com.gitmob.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// REST /notifications 响应体，字段是 snake_case（GitHub REST API 的一贯风格，
// 和 GraphQL 的 camelCase 不同，这里用 @SerialName 显式映射，不依赖全局命名策略）

@Serializable
data class NotificationThreadDto(
    val id: String,
    val repository: NotificationRepoDto,
    val subject: NotificationSubjectDto,
    val reason: String,
    val unread: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class NotificationRepoDto(
    val name: String,
    val owner: NotificationRepoOwnerDto,
)

@Serializable
data class NotificationRepoOwnerDto(val login: String)

@Serializable
data class NotificationSubjectDto(
    val title: String,
    val url: String? = null,
    val type: String,
)
