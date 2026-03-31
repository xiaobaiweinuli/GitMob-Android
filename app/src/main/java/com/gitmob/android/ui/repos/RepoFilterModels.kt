package com.gitmob.android.ui.repos

import com.gitmob.android.api.GHRepo
import java.time.OffsetDateTime

/**
 * 仓库类型筛选枚举
 */
enum class RepoTypeFilter(val displayName: String) {
    ALL("所有"),
    ARCHIVED("已存档"),
    FORK("分支"),
    MIRROR("镜像"),
    PRIVATE("私人"),
    PUBLIC("公共"),
    SOURCE("源"),
    TEMPLATE("模板");

    companion object {
        fun fromDisplayName(name: String): RepoTypeFilter? {
            return values().find { it.displayName == name }
        }
    }
}

/**
 * 仓库排序方式枚举（远程仓库页面）
 */
enum class RepoSortBy(val displayName: String) {
    PUSHED_DESC("最近推送"),
    PUSHED_ASC("最近推送最少"),
    CREATED_DESC("最新"),
    CREATED_ASC("最早"),
    NAME_DESC("名称降序"),
    NAME_ASC("名称升序"),
    STARS_DESC("最多星标"),
    STARS_ASC("最少星标");

    companion object {
        fun fromDisplayName(name: String): RepoSortBy? {
            return values().find { it.displayName == name }
        }
    }
}

/**
 * 标星仓库排序方式枚举
 */
enum class StarSortBy(val displayName: String) {
    STARRED_DESC("最近标星"),
    ACTIVE_DESC("近期活跃"),
    STARS_DESC("星标数量");

    companion object {
        fun fromDisplayName(name: String): StarSortBy? {
            return values().find { it.displayName == name }
        }
    }
}

/**
 * 远程仓库筛选状态
 */
data class RepoFilterState(
    val typeFilter: RepoTypeFilter = RepoTypeFilter.ALL,
    val sortBy: RepoSortBy = RepoSortBy.PUSHED_DESC,
    val languageFilter: String? = null,
)

/**
 * 标星仓库筛选状态
 */
data class StarFilterState(
    val typeFilter: RepoTypeFilter = RepoTypeFilter.ALL,
    val sortBy: StarSortBy = StarSortBy.STARRED_DESC,
    val languageFilter: String? = null,
)

/**
 * 对远程仓库列表进行筛选和排序
 */
fun filterAndSortRepos(
    repos: List<GHRepo>,
    searchQuery: String,
    filterState: RepoFilterState,
): List<GHRepo> {
    var result = repos

    result = when (filterState.typeFilter) {
        RepoTypeFilter.ALL -> result
        RepoTypeFilter.ARCHIVED -> result.filter { it.archived == true }
        RepoTypeFilter.FORK -> result.filter { it.fork }
        RepoTypeFilter.MIRROR -> result.filter { it.isTemplate == false && it.fork && it.mirrorUrl != null }
        RepoTypeFilter.PRIVATE -> result.filter { it.private }
        RepoTypeFilter.PUBLIC -> result.filter { !it.private }
        RepoTypeFilter.SOURCE -> result.filter { !it.fork }
        RepoTypeFilter.TEMPLATE -> result.filter { it.isTemplate == true }
    }

    if (filterState.languageFilter != null) {
        result = result.filter { it.language == filterState.languageFilter }
    }

    if (searchQuery.isNotEmpty()) {
        result = result.filter { r ->
            r.name.contains(searchQuery, ignoreCase = true) ||
            (r.description?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    result = when (filterState.sortBy) {
        RepoSortBy.PUSHED_DESC -> result.sortedWith(compareByDescending<GHRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
        RepoSortBy.PUSHED_ASC -> result.sortedWith(compareBy<GHRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
        RepoSortBy.CREATED_DESC -> result.sortedWith(compareByDescending<GHRepo> { parseDateTime(it.createdAt) }.thenBy { it.name })
        RepoSortBy.CREATED_ASC -> result.sortedWith(compareBy<GHRepo> { parseDateTime(it.createdAt) }.thenBy { it.name })
        RepoSortBy.NAME_DESC -> result.sortedWith(compareByDescending { it.name.lowercase() })
        RepoSortBy.NAME_ASC -> result.sortedWith(compareBy { it.name.lowercase() })
        RepoSortBy.STARS_DESC -> result.sortedWith(compareByDescending<GHRepo> { it.stars }.thenByDescending { parseDateTime(it.pushedAt) })
        RepoSortBy.STARS_ASC -> result.sortedWith(compareBy<GHRepo> { it.stars }.thenByDescending { parseDateTime(it.pushedAt) })
    }

    return result
}

/**
 * 对标星仓库列表进行筛选和排序
 */
fun filterAndSortStarredRepos(
    repos: List<StarredRepo>,
    searchQuery: String,
    filterState: StarFilterState,
): List<StarredRepo> {
    var result = repos

    result = when (filterState.typeFilter) {
        RepoTypeFilter.ALL -> result
        RepoTypeFilter.ARCHIVED -> result.filter { it.archived == true }
        RepoTypeFilter.FORK -> result.filter { it.fork }
        RepoTypeFilter.MIRROR -> result.filter { it.isTemplate == false && it.fork && it.mirrorUrl != null }
        RepoTypeFilter.PRIVATE -> result.filter { it.isPrivate }
        RepoTypeFilter.PUBLIC -> result.filter { !it.isPrivate }
        RepoTypeFilter.SOURCE -> result.filter { !it.fork }
        RepoTypeFilter.TEMPLATE -> result.filter { it.isTemplate == true }
    }

    if (filterState.languageFilter != null) {
        result = result.filter { it.language == filterState.languageFilter }
    }

    if (searchQuery.isNotEmpty()) {
        result = result.filter { r ->
            r.name.contains(searchQuery, ignoreCase = true) ||
            r.nameWithOwner.contains(searchQuery, ignoreCase = true) ||
            (r.description?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    result = when (filterState.sortBy) {
        StarSortBy.STARRED_DESC -> result.sortedWith(compareByDescending<StarredRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
        StarSortBy.ACTIVE_DESC -> result.sortedWith(compareByDescending<StarredRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
        StarSortBy.STARS_DESC -> result.sortedWith(compareByDescending<StarredRepo> { it.stars }.thenByDescending { parseDateTime(it.pushedAt) })
    }

    return result
}

/**
 * 判断远程仓库是否有任何筛选条件被应用
 */
fun hasAnyRepoFiltersApplied(filterState: RepoFilterState): Boolean {
    return filterState.typeFilter != RepoTypeFilter.ALL ||
           filterState.sortBy != RepoSortBy.PUSHED_DESC ||
           filterState.languageFilter != null
}

/**
 * 判断标星仓库是否有任何筛选条件被应用
 */
fun hasAnyStarFiltersApplied(filterState: StarFilterState): Boolean {
    return filterState.typeFilter != RepoTypeFilter.ALL ||
           filterState.sortBy != StarSortBy.STARRED_DESC ||
           filterState.languageFilter != null
}

/**
 * 从仓库列表中提取所有语言
 */
fun extractLanguagesFromRepos(repos: List<GHRepo>): List<String> {
    return repos.mapNotNull { it.language }.distinct().sorted()
}

/**
 * 从标星仓库列表中提取所有语言
 */
fun extractLanguagesFromStarredRepos(repos: List<StarredRepo>): List<String> {
    return repos.mapNotNull { it.language }.distinct().sorted()
}

/**
 * 解析日期时间字符串
 */
private fun parseDateTime(dateTimeStr: String?): OffsetDateTime {
    return try {
        dateTimeStr?.let { OffsetDateTime.parse(it) } ?: OffsetDateTime.MIN
    } catch (e: Exception) {
        OffsetDateTime.MIN
    }
}
