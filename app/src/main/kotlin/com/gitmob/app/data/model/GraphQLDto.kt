package com.gitmob.app.data.model

import kotlinx.serialization.Serializable

/** GraphQL 原始响应结构（贴合 query 形状），只在 Repository 内部使用，不传到 UI 层 */
//
// 本文件只剩 WorkRepository（involves:@me 搜索）仍在使用的手写 GraphQL DTO，
// 其余仓库均已迁移到 Apollo Kotlin 生成模型，B6 将随 WorkRepository 一起清空本文件。

@Serializable
data class PageInfoNode(val hasNextPage: Boolean = false, val endCursor: String? = null)

@Serializable
data class OwnerNode(val login: String, val avatarUrl: String? = null)

// ---- 主页事务入口行：议题/PR/讨论 involves:@me 计数 ----

@Serializable
data class SearchIssueCountNode(val issueCount: Int? = null)

@Serializable
data class SearchDiscussionCountNode(val discussionCount: Int? = null)

// ---- 议题/PR/讨论 involves:@me 分页列表 ----

@Serializable
data class WorkSearchQueryData(val search: WorkSearchConnection)

@Serializable
data class WorkSearchConnection(
    val issueCount: Int = 0,
    val discussionCount: Int = 0,
    val nodes: List<WorkSearchNode>,
    val pageInfo: PageInfoNode,
)

@Serializable
data class WorkSearchNode(
    val __typename: String,
    val id: String,
    val number: Int,
    val title: String,
    val state: String? = null,
    val stateReason: String? = null,
    val isDraft: Boolean = false,
    val isAnswered: Boolean = false,
    val locked: Boolean = false,
    val updatedAt: String,
    val repository: WorkRepoRefNode,
)

@Serializable
data class WorkRepoRefNode(val name: String, val owner: OwnerNode)
