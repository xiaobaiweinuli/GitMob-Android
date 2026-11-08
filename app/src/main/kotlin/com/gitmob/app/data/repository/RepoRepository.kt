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
import com.gitmob.app.graphql.SearchRepositoriesQuery
import com.gitmob.app.graphql.fragment.RepoListFields
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
    private val ownerCache = MemoryCache<String, RepositoryListOwnerContext>(ttlMs = 5 * 60_000L)
    private val cacheGeneration = MemoryCache<Unit, Long>(ttlMs = Long.MAX_VALUE)

    /**
     * 只失效当前会话；世代用于防止同一账号刷新前的迟到响应重新回填。
     */
    fun invalidateAllCaches() {
        synchronized(viewerReposCache) {
            cacheGeneration.set(Unit, (cacheGeneration.get(Unit) ?: 0) + 1)
            viewerReposCache.invalidateAll()
            ownerCache.invalidateAll()
        }
    }

    /** Lists owned repositories, with all matching and ordering performed by GitHub search. */
    suspend fun getRepos(
        login: String? = null,
        after: String? = null,
        filters: RepoFilters = RepoFilters(),
        forceRefresh: Boolean = false,
        received: Int = 0,
    ): ApiResult<RepoList> {
        val normalized = filters.normalized()
        val cacheable = login == null && after == null && normalized == RepoFilters()
        val generation = synchronized(viewerReposCache) {
            if (forceRefresh) invalidateAllCaches()
            if (cacheable) viewerReposCache.get(Unit)?.let { return ApiResult.Success(it) }
            cacheGeneration.get(Unit) ?: 0
        }
        val owner = when (val result = getListOwner(login, generation)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        val remaining = PageSize.SEARCH_ACCESSIBLE_LIMIT - received
        if (remaining <= 0) return ApiResult.Success(RepoList(
            totalCount = received, items = emptyList(), hasNextPage = false, endCursor = null,
            ownerContext = owner, receivedCount = received, limitReached = true,
        ))
        val result = when (val response = apolloClient.query(SearchRepositoriesQuery(
            query = repoSearchQuery(owner.owner, normalized),
            after = Optional.presentIfNotNull(after),
            first = minOf(PageSize.REPOS, remaining),
            topicsFirst = PageSize.TOPICS_PER_REPO,
        )).executeAsApiResult()) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> safeCall {
                val connection = response.data.search
                val nodes = connection.nodes.orEmpty()
                val items = nodes.mapNotNull { it?.onRepository?.repoListFields }.map { it.toDomain() }
                val count = received + nodes.size
                val limited = count >= PageSize.SEARCH_ACCESSIBLE_LIMIT &&
                    (connection.pageInfo.hasNextPage || connection.repositoryCount > count)
                if (connection.pageInfo.hasNextPage && count < PageSize.SEARCH_ACCESSIBLE_LIMIT) {
                    check(items.isNotEmpty()) { "Repository search returned an empty continuation page" }
                    check(!connection.pageInfo.endCursor.isNullOrBlank() && connection.pageInfo.endCursor != after) {
                        "Repository search cursor did not advance"
                    }
                }
                val hasNext = connection.pageInfo.hasNextPage && count < PageSize.SEARCH_ACCESSIBLE_LIMIT
                RepoList(
                    totalCount = connection.repositoryCount,
                    items = items,
                    hasNextPage = hasNext,
                    endCursor = connection.pageInfo.endCursor.takeIf { hasNext },
                    ownerContext = owner,
                    receivedCount = count,
                    limitReached = limited,
                )
            }
        }
        if (cacheable && result is ApiResult.Success) {
            synchronized(viewerReposCache) {
                if (generation == (cacheGeneration.get(Unit) ?: 0)) viewerReposCache.set(Unit, result.data)
            }
        }
        return result
    }

    private suspend fun getListOwner(login: String?, generation: Long): ApiResult<RepositoryListOwnerContext> {
        val key = login?.lowercase(java.util.Locale.ROOT).orEmpty()
        synchronized(viewerReposCache) { ownerCache.get(key)?.let { return ApiResult.Success(it) } }
        val result = if (login == null) {
            when (val response = apolloClient.query(ViewerReposQuery()).executeAsApiResult()) {
                is ApiResult.Failure -> response
                is ApiResult.Success -> safeCall {
                    val viewer = response.data.viewer
                    RepositoryListOwnerContext(RepositoryCreateOwner(
                        id = viewer.id, login = viewer.login, name = viewer.name, avatarUrl = viewer.avatarUrl,
                        type = RepositoryCreateOwnerType.USER, canCreateRepository = true,
                    ))
                }
            }
        } else {
            when (val response = apolloClient.query(OwnerReposQuery(login)).executeAsApiResult()) {
                is ApiResult.Failure -> response
                is ApiResult.Success -> safeCall {
                    val owner = checkNotNull(response.data.repositoryOwner) { "Repository owner is missing" }
                    val organization = owner.onOrganization
                    RepositoryListOwnerContext(RepositoryCreateOwner(
                        id = owner.id, login = owner.login,
                        name = owner.onUser?.name ?: organization?.name, avatarUrl = owner.avatarUrl,
                        type = if (organization != null) RepositoryCreateOwnerType.ORGANIZATION else RepositoryCreateOwnerType.USER,
                        canCreateRepository = organization?.viewerCanCreateRepositories ?: (owner.onUser?.isViewer == true),
                    ))
                }
            }
        }
        if (result is ApiResult.Success) synchronized(viewerReposCache) {
            if (generation == (cacheGeneration.get(Unit) ?: 0)) ownerCache.set(key, result.data)
        }
        return result
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
 * 仓库列表与全局搜索统一映射，保留 API 返回的可见性与卡片数据。
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
