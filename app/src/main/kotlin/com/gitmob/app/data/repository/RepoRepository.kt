package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.*
import com.gitmob.app.graphql.OwnerReposQuery
import com.gitmob.app.graphql.RepositoryCreateOwnersQuery
import com.gitmob.app.graphql.ViewerReposQuery
import com.gitmob.app.graphql.fragment.RepoListFields
import com.gitmob.app.graphql.type.OrderDirection
import com.gitmob.app.graphql.type.RepositoryOrder
import com.gitmob.app.graphql.type.RepositoryOrderField
import com.gitmob.app.graphql.type.RepositoryVisibility
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoRepository @Inject constructor(
    private val api: GHApiClient,
    private val apolloClient: ApolloClient,
) {

    /** 只缓存 viewer 默认条件第一页，TTL 5 min。 */
    private val viewerReposCache = MemoryCache<Unit, RepoList>(ttlMs = 5 * 60_000L)
    private var cacheGeneration = 0L

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存。
     */
    fun invalidateAllCaches() {
        synchronized(viewerReposCache) {
            cacheGeneration++
            viewerReposCache.invalidateAll()
        }
    }

    /**
     * 查询当前主体拥有的仓库；login=null 表示 viewer，其他值表示用户或组织。
     * 搜索逐页匹配名称／简介，扫描预算由 PageSize 决定，返回最后完整处理页的游标。
     */
    suspend fun getRepos(
        login: String? = null,
        after: String? = null,
        filters: RepoFilters = RepoFilters(),
        forceRefresh: Boolean = false,
    ): ApiResult<RepoList> {
        val query = filters.normalized()
        val cacheable = login == null && after == null && query == RepoFilters()
        val generation = synchronized(viewerReposCache) {
            if (cacheable) {
                if (forceRefresh) {
                    cacheGeneration++
                    viewerReposCache.invalidateAll()
                } else {
                    viewerReposCache.get(Unit)?.let { return ApiResult.Success(it) }
                }
            }
            cacheGeneration
        }
        val result = scanRepos(login, after, query)
        if (cacheable && result is ApiResult.Success) {
            synchronized(viewerReposCache) {
                if (generation == cacheGeneration) viewerReposCache.set(Unit, result.data)
            }
        }
        return result
    }

    private suspend fun scanRepos(login: String?, after: String?, filters: RepoFilters): ApiResult<RepoList> {
        val collected = linkedMapOf<String, RepoListItem>()
        val cursors = mutableSetOf(after)
        var cursor = after
        var scannedPages = 0
        var ownerId: String? = null
        while (true) {
            val page = when (val result = getReposPage(login, cursor, filters)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> result.data
            }
            val batch = safeCall {
                check(ownerId == null || ownerId == page.ownerContext?.owner?.id) { "Repository owner changed while scanning" }
                ownerId = page.ownerContext?.owner?.id
                if (page.hasNextPage) {
                    check(!page.endCursor.isNullOrBlank() && cursors.add(page.endCursor)) { "Repository cursor did not advance" }
                }
                scannedPages++
                page.items.filter(filters::matchesSearch).forEach { collected[it.key] = it }
                page.copy(
                    items = collected.values.toList(),
                    requiresManualContinue = page.hasNextPage &&
                        (collected.isEmpty() || (filters.query.isNotEmpty() && scannedPages == PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT)),
                )
            }
            if (batch is ApiResult.Failure) return batch
            if (!page.hasNextPage || filters.query.isEmpty() || collected.size >= PageSize.REPOS || scannedPages == PageSize.REPO_SEARCH_SCAN_PAGE_LIMIT) {
                return batch
            }
            cursor = page.endCursor
        }
    }

    private suspend fun getReposPage(login: String?, after: String?, filters: RepoFilters): ApiResult<RepoList> {
        val visibility = Optional.presentIfNotNull(filters.visibility?.let { RepositoryVisibility.valueOf(it.name) })
        val archived = Optional.presentIfNotNull(filters.isArchived)
        val fork = Optional.presentIfNotNull(filters.isFork)
        val issues = Optional.presentIfNotNull(filters.hasIssuesEnabled)
        val order = filters.sort.toOrder()
        return if (login == null) {
            when (val result = apolloClient.query(ViewerReposQuery(
                after = Optional.presentIfNotNull(after),
                first = PageSize.REPOS,
                topicsFirst = PageSize.TOPICS_PER_REPO,
                visibility = visibility,
                isArchived = archived,
                isFork = fork,
                hasIssuesEnabled = issues,
                orderBy = order,
            )).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall {
                    val viewer = result.data.viewer
                    val conn = viewer.repositories
                    RepoList(
                        totalCount = conn.totalCount,
                        items = conn.nodes.orEmpty().filterNotNull().map { it.repoListFields.toDomain() },
                        hasNextPage = conn.pageInfo.hasNextPage,
                        endCursor = conn.pageInfo.endCursor,
                        ownerContext = RepositoryListOwnerContext(
                            RepositoryCreateOwner(
                                id = viewer.id,
                                login = viewer.login,
                                name = viewer.name,
                                avatarUrl = viewer.avatarUrl,
                                type = RepositoryCreateOwnerType.USER,
                                canCreateRepository = true,
                            ),
                        ),
                    )
                }
            }
        } else {
            when (val result = apolloClient.query(OwnerReposQuery(
                login = login,
                after = Optional.presentIfNotNull(after),
                first = PageSize.REPOS,
                topicsFirst = PageSize.TOPICS_PER_REPO,
                visibility = visibility,
                isArchived = archived,
                isFork = fork,
                hasIssuesEnabled = issues,
                orderBy = order,
            )).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> safeCall {
                    val repositoryOwner = result.data.repositoryOwner
                        ?: throw IllegalStateException("仓库查询根字段缺失（repositoryOwner 为 null）")
                    val conn = repositoryOwner.repositories
                    val ownerType = if (repositoryOwner.onOrganization != null) {
                        RepositoryCreateOwnerType.ORGANIZATION
                    } else {
                        RepositoryCreateOwnerType.USER
                    }
                    RepoList(
                        totalCount = conn.totalCount,
                        items = conn.nodes.orEmpty().filterNotNull().map { it.repoListFields.toDomain() },
                        hasNextPage = conn.pageInfo.hasNextPage,
                        endCursor = conn.pageInfo.endCursor,
                        ownerContext = RepositoryListOwnerContext(
                            RepositoryCreateOwner(
                                id = repositoryOwner.id,
                                login = repositoryOwner.login,
                                name = repositoryOwner.onUser?.name ?: repositoryOwner.onOrganization?.name,
                                avatarUrl = repositoryOwner.avatarUrl,
                                type = ownerType,
                                canCreateRepository = if (ownerType == RepositoryCreateOwnerType.ORGANIZATION) {
                                    repositoryOwner.onOrganization?.viewerCanCreateRepositories == true
                                } else {
                                    repositoryOwner.onUser?.isViewer == true
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** Loads the viewer and one page of organizations for the create-repository owner picker. */
    suspend fun getRepositoryCreateOwners(after: String? = null): ApiResult<RepositoryCreateOwnerPage> =
        when (val result = apolloClient.query(RepositoryCreateOwnersQuery(
            after = Optional.presentIfNotNull(after),
            first = PageSize.ORGS,
        )).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val viewer = result.data.viewer
                RepositoryCreateOwnerPage(
                    viewer = RepositoryCreateOwner(
                        id = viewer.id,
                        login = viewer.login,
                        name = viewer.name,
                        avatarUrl = viewer.avatarUrl,
                        type = RepositoryCreateOwnerType.USER,
                        canCreateRepository = true,
                    ),
                    organizations = viewer.organizations.nodes.orEmpty().filterNotNull().map { organization ->
                        RepositoryCreateOwner(
                            id = organization.id,
                            login = organization.login,
                            name = organization.name,
                            avatarUrl = organization.avatarUrl,
                            type = RepositoryCreateOwnerType.ORGANIZATION,
                            canCreateRepository = organization.viewerCanCreateRepositories,
                        )
                    },
                    hasNextPage = viewer.organizations.pageInfo.hasNextPage,
                    endCursor = viewer.organizations.pageInfo.endCursor,
                )
            }
        }

    suspend fun getLicenseTemplates(): ApiResult<List<RepositoryLicense>> = safeCall {
        api.get<List<RestLicenseTemplate>>("/licenses").map { RepositoryLicense(it.key, it.name) }
    }

    suspend fun getGitignoreTemplates(): ApiResult<List<String>> = safeCall {
        api.get<List<String>>("/gitignore/templates")
    }

    suspend fun createRepository(input: RepositoryCreateInput): ApiResult<CreatedRepository> = safeCall {
        val request = RepositoryCreateRequest(
            name = input.name,
            description = input.description,
            isPrivate = input.isPrivate,
            autoInit = input.addReadme,
            licenseTemplate = input.licenseTemplate,
            gitignoreTemplate = input.gitignoreTemplate,
        )
        val path = if (input.owner.type == RepositoryCreateOwnerType.ORGANIZATION) {
            "/orgs/${input.owner.login}/repos"
        } else {
            "/user/repos"
        }
        val response = api.post<RestCreatedRepository, RepositoryCreateRequest>(path, request)
        if (input.owner.type == RepositoryCreateOwnerType.USER) {
            invalidateAllCaches()
        }
        CreatedRepository(owner = response.owner.login, name = response.name)
    }

    @Serializable
    private data class RestLicenseTemplate(
        val key: String = "",
        val name: String = "",
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class RepositoryCreateRequest(
        val name: String,
        val description: String? = null,
        @SerialName("private")
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val isPrivate: Boolean = false,
        @SerialName("auto_init")
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val autoInit: Boolean = false,
        @SerialName("license_template")
        val licenseTemplate: String? = null,
        @SerialName("gitignore_template")
        val gitignoreTemplate: String? = null,
    )

    @Serializable
    private data class RestCreatedRepository(
        val name: String = "",
        val owner: RestRepositoryOwner = RestRepositoryOwner(),
    )

    @Serializable
    private data class RestRepositoryOwner(
        val login: String = "",
    )
}

/**
 * RepoListFields → RepoListItem 公共映射。
 * viewer.repositories 与 repositoryOwner(login:).repositories 的节点结构完全一致，
 * 统一在这里映射，避免两处漂移。
 */
internal fun RepoListFields.toDomain() = RepoListItem(
    name = name,
    ownerLogin = owner.login,
    description = description,
    homepageUrl = homepageUrl,
    visibility = RepoVisibility.valueOf(visibility.rawValue),
    isArchived = isArchived,
    isFork = isFork,
    hasIssuesEnabled = hasIssuesEnabled,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
    pushedAt = pushedAt?.let(Instant::parse),
    forkedFromOwner = parent?.owner?.login,
    forkedFromName = parent?.name,
    languageName = primaryLanguage?.name,
    languageColor = primaryLanguage?.color,
    stargazerCount = stargazerCount,
    forkCount = forkCount,
    openIssueCount = issues.totalCount,
    topics = repositoryTopics.nodes.orEmpty().filterNotNull().map { it.topic.name },
    defaultBranchName = defaultBranchRef?.name,
)

private fun RepoSort.toOrder() = RepositoryOrder(
    field = when (this) {
        RepoSort.UPDATED_ASC, RepoSort.UPDATED_DESC -> RepositoryOrderField.UPDATED_AT
        RepoSort.CREATED_ASC, RepoSort.CREATED_DESC -> RepositoryOrderField.CREATED_AT
        RepoSort.PUSHED_ASC, RepoSort.PUSHED_DESC -> RepositoryOrderField.PUSHED_AT
        RepoSort.STARS_ASC, RepoSort.STARS_DESC -> RepositoryOrderField.STARGAZERS
        RepoSort.NAME_ASC, RepoSort.NAME_DESC -> RepositoryOrderField.NAME
    },
    direction = when (this) {
        RepoSort.UPDATED_ASC, RepoSort.CREATED_ASC, RepoSort.PUSHED_ASC, RepoSort.STARS_ASC, RepoSort.NAME_ASC -> OrderDirection.ASC
        RepoSort.UPDATED_DESC, RepoSort.CREATED_DESC, RepoSort.PUSHED_DESC, RepoSort.STARS_DESC, RepoSort.NAME_DESC -> OrderDirection.DESC
    },
)
