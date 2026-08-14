package com.gitmob.app.data.model

/** "我的列表"横排 chip 用的摘要信息，不需要完整 items */
data class UserListSummary(
    val id: String,
    val name: String,
    val slug: String,
    val description: String?,
    val isPrivate: Boolean,
    val itemCount: Int,
)

/**
 * 星标仓库卡片模型——和 RepoListItem（"仓库"Tab，自己拥有的仓库）字段结构类似，
 * 但多了 owner 信息（星标的通常是别人的仓库），并保留复刻来源供卡片直接跳转。
 */
data class StarredRepo(
    val id: String,
    val name: String,
    val url: String,
    val description: String?,
    val homepageUrl: String?,
    val ownerLogin: String,
    val ownerAvatarUrl: String?,
    val isPrivate: Boolean,
    val isArchived: Boolean,
    val languageName: String?,
    val languageColor: String?,
    val stargazerCount: Int,
    val forkCount: Int,
    val openIssueCount: Int,
    val topics: List<String>,
    val defaultBranchName: String?,
    val isFork: Boolean = false,
    val forkedFromOwner: String? = null,
    val forkedFromName: String? = null,
)

data class PagedStarredRepos(
    val totalCount: Int,
    val items: List<StarredRepo>,
    val hasNextPage: Boolean,
    val endCursor: String?,
)

/** "全部星标" 是一个特殊的筛选态，不对应真实的 UserList，用 sealed class 和真实列表区分 */
sealed class StarFilter {
    data object All : StarFilter()
    data class ByList(val list: UserListSummary) : StarFilter()
}

/**
 * StarredRepoNode → StarredRepo 公共映射，StarRepository 和 UserRepository.getUserStars 都需要用到。
 * 从 StarRepository 的 private 扩展提升到 internal，避免两个仓库各自写一份同样的字段映射代码。
 */
internal fun StarredRepoNode.toDomain() = StarredRepo(
    id = id, name = name, url = url, description = description, homepageUrl = homepageUrl,
    ownerLogin = owner.login, ownerAvatarUrl = owner.avatarUrl,
    isPrivate = isPrivate, isArchived = isArchived,
    isFork = isFork, forkedFromOwner = parent?.owner?.login, forkedFromName = parent?.name,
    languageName = primaryLanguage?.name, languageColor = primaryLanguage?.color,
    stargazerCount = stargazerCount, forkCount = forkCount, openIssueCount = issues.totalCount,
    topics = repositoryTopics.nodes.map { it.topic.name },
    defaultBranchName = defaultBranchRef?.name,
)

/**
 * RepoListItemNode → RepoListItem 公共映射，RepoRepository 和 UserRepository.getUserRepos 都需要用到。
 * 与 StarredRepoNode.toDomain() 对称，提取到领域模型层，避免重复字段映射代码。
 */
internal fun RepoListItemNode.toDomain() = RepoListItem(
    name = name,
    ownerLogin = owner.login,
    description = description,
    homepageUrl = homepageUrl,
    isPrivate = isPrivate,
    isArchived = isArchived,
    isFork = isFork,
    forkedFromOwner = parent?.owner?.login,
    forkedFromName = parent?.name,
    languageName = primaryLanguage?.name,
    languageColor = primaryLanguage?.color,
    stargazerCount = stargazerCount,
    forkCount = forkCount,
    openIssueCount = issues.totalCount,
    topics = repositoryTopics.nodes.map { it.topic.name },
    defaultBranchName = defaultBranchRef?.name,
)
