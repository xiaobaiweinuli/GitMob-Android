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
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoRepository @Inject constructor(
    private val api: GHApiClient,
    private val apolloClient: ApolloClient,
) {

    // ── 缓存实例 ──────────────────────────────────────────────
    /** viewer 自己的仓库第一页（login=null, after=null），TTL 5 min */
    private val viewerReposCache = MemoryCache<Unit, RepoList>(ttlMs = 5 * 60_000L)

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存。
     */
    fun invalidateAllCaches() {
        viewerReposCache.invalidateAll()
    }

    /**
     * 统一的仓库列表查询入口：当前登录用户 + 任意指定用户都走这一个方法。
     *
     * @param login 要查询的 owner login（用户或组织）。
     *              `null` 时走 `viewer.repositories`（当前登录用户，「仓库」Tab）；
     *              非空时走 `repositoryOwner(login:).repositories`（个人/组织主页点「仓库」。
 
     * @param after 分页游标，`null` 表示第一页。
     * @return 仓库列表，两种模式下返回的 `RepoListConnection` 字段结构完全一致（GraphQL Schema 已核实）。
     */
    /**
     * 仓库列表。
     * 仅对「viewer 自己 + 第一页（login=null, after=null）」启用缓存（TTL 5 min）；
     * 他人仓库/第二页及以后不走缓存。
     */
    suspend fun getRepos(login: String? = null, after: String? = null): ApiResult<RepoList> {
        if (login == null && after == null) {
            viewerReposCache.get(Unit)?.let { return ApiResult.Success(it) }
            val result = getReposInternal(login, after)
            if (result is ApiResult.Success) viewerReposCache.set(Unit, result.data)
            return result
        }
        return getReposInternal(login, after)
    }

    /**
     * 下拉刷新专用：强制重新拉取 viewer 自己的仓库第一页，不走缓存。
     */
    suspend fun getViewerReposFresh(): ApiResult<RepoList> {
        val result = getReposInternal(login = null, after = null)
        if (result is ApiResult.Success) viewerReposCache.set(Unit, result.data)
        return result
    }

    private suspend fun getReposInternal(login: String?, after: String?): ApiResult<RepoList> {
        return if (login == null) {
            when (val result = apolloClient.query(ViewerReposQuery(
                after = Optional.presentIfNotNull(after),
                first = PageSize.REPOS,
                topicsFirst = PageSize.TOPICS_PER_REPO,
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
            viewerReposCache.invalidateAll()
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
    topics = repositoryTopics.nodes.orEmpty().filterNotNull().map { it.topic.name },
    defaultBranchName = defaultBranchRef?.name,
)
