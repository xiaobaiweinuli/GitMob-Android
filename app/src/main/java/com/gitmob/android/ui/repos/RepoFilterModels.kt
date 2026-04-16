package com.gitmob.android.ui.repos

import com.gitmob.android.api.GHRepo
import com.gitmob.android.util.LanguageEntry
import java.time.OffsetDateTime

enum class RepoTypeFilter(val displayName: String) {
    ALL("所有"), ARCHIVED("已存档"), FORK("分支"), MIRROR("镜像"),
    PRIVATE("私人"), PUBLIC("公共"), SOURCE("源"), TEMPLATE("模板");
    companion object { fun fromDisplayName(n: String) = values().find { it.displayName == n } }
}

enum class RepoSortBy(val displayName: String) {
    PUSHED_DESC("最近推送"), PUSHED_ASC("最近推送最少"),
    CREATED_DESC("最新"), CREATED_ASC("最早"),
    NAME_DESC("名称降序"), NAME_ASC("名称升序"),
    STARS_DESC("最多星标"), STARS_ASC("最少星标");
    companion object { fun fromDisplayName(n: String) = values().find { it.displayName == n } }
}

enum class StarSortBy(val displayName: String) {
    STARRED_DESC("最近标星"), ACTIVE_DESC("近期活跃"), STARS_DESC("星标数量");
    companion object { fun fromDisplayName(n: String) = values().find { it.displayName == n } }
}

/**
 * 远程仓库筛选状态
 * languageFilter：存储 LanguageEntry（name 用于显示，id 用于 Search API）
 * 当 languageFilter 不为 null 时，ViewModel 切换到 Search API 模式
 */
data class RepoFilterState(
    val typeFilter: RepoTypeFilter = RepoTypeFilter.ALL,
    val sortBy: RepoSortBy = RepoSortBy.PUSHED_DESC,
    val languageFilter: LanguageEntry? = null,
)

/**
 * 标星仓库筛选状态
 * 注意：GitHub API 无"按语言过滤已星标仓库"端点，languageFilter 在星标模式下走本地过滤（it.language == name）
 */
data class StarFilterState(
    val typeFilter: RepoTypeFilter = RepoTypeFilter.ALL,
    val sortBy: StarSortBy = StarSortBy.STARRED_DESC,
    val languageFilter: LanguageEntry? = null,
)

/**
 * 仓库列表本地排序（仅在无语言筛选时使用；有语言筛选时已通过 Search API 获取结果）
 * 注意：有 languageFilter 时调用方不再调用此函数，直接展示 Search API 结果
 */
fun sortRepos(repos: List<GHRepo>, sortBy: RepoSortBy): List<GHRepo> = when (sortBy) {
    RepoSortBy.PUSHED_DESC  -> repos.sortedWith(compareByDescending<GHRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
    RepoSortBy.PUSHED_ASC   -> repos.sortedWith(compareBy<GHRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
    RepoSortBy.CREATED_DESC -> repos.sortedWith(compareByDescending<GHRepo> { parseDateTime(it.createdAt) }.thenBy { it.name })
    RepoSortBy.CREATED_ASC  -> repos.sortedWith(compareBy<GHRepo> { parseDateTime(it.createdAt) }.thenBy { it.name })
    RepoSortBy.NAME_DESC    -> repos.sortedWith(compareByDescending { it.name.lowercase() })
    RepoSortBy.NAME_ASC     -> repos.sortedWith(compareBy { it.name.lowercase() })
    RepoSortBy.STARS_DESC   -> repos.sortedWith(compareByDescending<GHRepo> { it.stars }.thenByDescending { parseDateTime(it.pushedAt) })
    RepoSortBy.STARS_ASC    -> repos.sortedWith(compareBy<GHRepo> { it.stars }.thenByDescending { parseDateTime(it.pushedAt) })
}

fun filterAndSortRepos(
    repos: List<GHRepo>,
    searchQuery: String,
    filterState: RepoFilterState,
): List<GHRepo> {
    // 有语言筛选时 repos 已经是 Search API 结果（VM 层保证），无需本地语言过滤
    var result = repos

    result = when (filterState.typeFilter) {
        RepoTypeFilter.ALL      -> result
        RepoTypeFilter.ARCHIVED -> result.filter { it.archived }
        RepoTypeFilter.FORK     -> result.filter { it.fork }
        RepoTypeFilter.MIRROR   -> result.filter { it.mirrorUrl != null }
        RepoTypeFilter.PRIVATE  -> result.filter { it.private }
        RepoTypeFilter.PUBLIC   -> result.filter { !it.private }
        RepoTypeFilter.SOURCE   -> result.filter { !it.fork }
        RepoTypeFilter.TEMPLATE -> result.filter { it.isTemplate }
    }

    if (searchQuery.isNotEmpty()) {
        result = result.filter { r ->
            r.name.contains(searchQuery, ignoreCase = true) ||
            (r.description?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    return sortRepos(result, filterState.sortBy)
}

fun filterAndSortStarredRepos(
    repos: List<StarredRepo>,
    searchQuery: String,
    filterState: StarFilterState,
): List<StarredRepo> {
    var result = repos

    result = when (filterState.typeFilter) {
        RepoTypeFilter.ALL      -> result
        RepoTypeFilter.ARCHIVED -> result.filter { it.archived }
        RepoTypeFilter.FORK     -> result.filter { it.fork }
        RepoTypeFilter.MIRROR   -> result.filter { it.mirrorUrl != null }
        RepoTypeFilter.PRIVATE  -> result.filter { it.isPrivate }
        RepoTypeFilter.PUBLIC   -> result.filter { !it.isPrivate }
        RepoTypeFilter.SOURCE   -> result.filter { !it.fork }
        RepoTypeFilter.TEMPLATE -> result.filter { it.isTemplate }
    }

    // 星标模式：GitHub 无"starred+language"API，退化为本地按 name 过滤
    filterState.languageFilter?.let { entry ->
        result = result.filter { it.language.equals(entry.name, ignoreCase = true) }
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
        StarSortBy.ACTIVE_DESC  -> result.sortedWith(compareByDescending<StarredRepo> { parseDateTime(it.pushedAt) }.thenBy { it.name })
        StarSortBy.STARS_DESC   -> result.sortedWith(compareByDescending<StarredRepo> { it.stars }.thenByDescending { parseDateTime(it.pushedAt) })
    }

    return result
}

fun hasAnyRepoFiltersApplied(filterState: RepoFilterState): Boolean =
    filterState.typeFilter != RepoTypeFilter.ALL ||
    filterState.sortBy != RepoSortBy.PUSHED_DESC ||
    filterState.languageFilter != null

fun hasAnyStarFiltersApplied(filterState: StarFilterState): Boolean =
    filterState.typeFilter != RepoTypeFilter.ALL ||
    filterState.sortBy != StarSortBy.STARRED_DESC ||
    filterState.languageFilter != null

fun extractLanguagesFromRepos(repos: List<GHRepo>): Set<String> =
    repos.mapNotNull { it.language }.toSet()

fun extractLanguagesFromStarredRepos(repos: List<StarredRepo>): Set<String> =
    repos.mapNotNull { it.language }.toSet()

private fun parseDateTime(s: String?): OffsetDateTime =
    try { s?.let { OffsetDateTime.parse(it) } ?: OffsetDateTime.MIN } catch (_: Exception) { OffsetDateTime.MIN }
