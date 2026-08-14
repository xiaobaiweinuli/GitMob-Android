package com.gitmob.app.data.repository

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistEdgeNode
import com.gitmob.app.data.model.GistFileNode
import com.gitmob.app.data.model.GistFilePreview
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistNode
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.ViewerGistsQueryData
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val GIST_FILE_PREVIEW_CHARACTERS = 4_000
private const val GIST_FILE_METADATA_LIMIT = 100
private const val GIST_SCAN_BATCH_LIMIT = 5
private const val GIST_CACHE_TTL_MS = 5 * 60_000L

@Singleton
class GistRepository @Inject constructor(
    private val api: GHApiClient,
) {
    private data class CacheKey(
        val viewerLogin: String,
        val targetLogin: String?,
        val category: GistCategory,
        val sort: GistSort,
    )

    private data class ScanResult(
        val viewerLogin: String,
        val page: GistPage,
    )

    private val firstPageCache = MemoryCache<CacheKey, GistPage>(GIST_CACHE_TTL_MS)

    @Volatile
    private var currentViewerLogin: String? = null

    fun invalidateAllCaches() {
        firstPageCache.invalidateAll()
        currentViewerLogin = null
    }

    suspend fun getGists(
        login: String? = null,
        category: GistCategory,
        sort: GistSort,
        after: String? = null,
    ): ApiResult<GistPage> {
        if (after == null) {
            currentViewerLogin?.let { viewerLogin ->
                firstPageCache.get(CacheKey(viewerLogin, login?.lowercase(), category, sort))?.let {
                    return ApiResult.Success(it)
                }
            }
        }

        return loadAndMap(login, category, sort, after, cacheFirstPage = after == null)
    }

    suspend fun getGistsFresh(
        login: String? = null,
        category: GistCategory,
        sort: GistSort,
    ): ApiResult<GistPage> = loadAndMap(
        login = login,
        category = category,
        sort = sort,
        after = null,
        cacheFirstPage = true,
    )

    private suspend fun loadAndMap(
        login: String?,
        category: GistCategory,
        sort: GistSort,
        after: String?,
        cacheFirstPage: Boolean,
    ): ApiResult<GistPage> = when (val result = scanGists(login, category, sort, after)) {
        is ApiResult.Success -> {
            currentViewerLogin = result.data.viewerLogin
            if (cacheFirstPage) {
                firstPageCache.set(
                    CacheKey(result.data.viewerLogin, login?.lowercase(), category, sort),
                    result.data.page,
                )
            }
            ApiResult.Success(result.data.page)
        }
        is ApiResult.Failure -> result
    }

    private suspend fun scanGists(
        targetLogin: String?,
        category: GistCategory,
        sort: GistSort,
        after: String?,
    ): ApiResult<ScanResult> = safeCall {
        val collected = mutableListOf<GistListItem>()
        var viewerLogin: String? = null
        var cursor = after
        var hasNextPage = true
        var scannedBatches = 0

        while (
            collected.size < PageSize.GISTS &&
            hasNextPage &&
            scannedBatches < GIST_SCAN_BATCH_LIMIT
        ) {
            val response = fetchBatch(targetLogin, sort, cursor)
            val login = response.viewer.login
            if (viewerLogin != null && !viewerLogin.equals(login, ignoreCase = true)) {
                throw IllegalStateException("Gist 分页期间登录账号发生变化")
            }
            viewerLogin = login

            val connection = if (targetLogin == null) {
                response.viewer.gists
                    ?: throw IllegalStateException("viewer Gist 查询根字段缺失")
            } else {
                response.user?.gists
                    ?: throw IllegalStateException("用户不存在或 Gist 查询根字段缺失")
            }
            val edges = connection.edges.orEmpty().filterNotNull()
            var stoppedInsideBatch = false

            for ((index, edge) in edges.withIndex()) {
                cursor = edge.cursor
                edge.node?.let { node ->
                    if (node.matches(category)) {
                        collected += node.toDomain(login)
                    }
                }

                if (collected.size >= PageSize.GISTS) {
                    val hasUnexaminedEdges = index < edges.lastIndex
                    hasNextPage = hasUnexaminedEdges || connection.pageInfo.hasNextPage
                    stoppedInsideBatch = true
                    break
                }
            }

            if (!stoppedInsideBatch) {
                hasNextPage = connection.pageInfo.hasNextPage
                if (hasNextPage) {
                    val nextBatchCursor = connection.pageInfo.endCursor
                        ?: throw IllegalStateException("Gist 分页缺少 endCursor")
                    if (nextBatchCursor == cursor && edges.isEmpty()) {
                        throw IllegalStateException("Gist 分页游标没有前进")
                    }
                    cursor = nextBatchCursor
                }
            }
            scannedBatches++
        }

        ScanResult(
            viewerLogin = viewerLogin ?: throw IllegalStateException("Gist 查询缺少 viewer.login"),
            page = GistPage(
                items = collected,
                hasNextPage = hasNextPage,
                nextCursor = cursor,
            ),
        )
    }

    private suspend fun fetchBatch(
        login: String?,
        sort: GistSort,
        after: String?,
    ): ViewerGistsQueryData {
        val gistFields = """
            gists(
                        first: ${PageSize.GISTS}
                        after: ${'$'}after
                        privacy: ${if (login == null) "ALL" else "PUBLIC"}
                        orderBy: { field: ${sort.graphQLField}, direction: ${sort.graphQLDirection} }
                    ) {
                        edges {
                            cursor
                            node {
                                id
                                name
                                description
                                owner { login }
                                isPublic
                                isFork
                                createdAt
                                updatedAt
                                stargazerCount
                                comments { totalCount }
                                url
                                previewFiles: files(limit: 1) {
                                    name
                                    text(truncate: $GIST_FILE_PREVIEW_CHARACTERS)
                                    size
                                    isTruncated
                                    isImage
                                    language { name color }
                                }
                                fileMetadata: files(limit: $GIST_FILE_METADATA_LIMIT) { name }
                            }
                        }
                        pageInfo { hasNextPage endCursor }
                    }
        """.trimIndent()
        val query = if (login == null) {
            """
            query ViewerGists(${'$'}after: String) {
                viewer {
                    login
                    $gistFields
                }
            }
            """.trimIndent()
        } else {
            """
            query UserGists(${'$'}login: String!, ${'$'}after: String) {
                viewer { login }
                user(login: ${'$'}login) {
                    login
                    $gistFields
                }
            }
            """.trimIndent()
        }

        val variables = buildMap {
            after?.let { put("after", JsonPrimitive(it)) }
            login?.let { put("login", JsonPrimitive(it)) }
        }
        return api.graphQL(query, variables)
    }

    private fun GistNode.matches(category: GistCategory): Boolean = when (category) {
        GistCategory.ORIGINAL -> !isFork
        GistCategory.FORKED -> isFork
    }

    private fun GistNode.toDomain(viewerLogin: String): GistListItem {
        val fileNodes = fileMetadata.orEmpty().filterNotNull()
        val ownerLogin = owner?.login
        return GistListItem(
            id = id,
            apiName = name,
            ownerLogin = ownerLogin,
            description = description?.takeIf { it.isNotBlank() },
            url = url,
            isPublic = isPublic,
            isFork = isFork,
            isOwnedByViewer = ownerLogin?.equals(viewerLogin, ignoreCase = true) == true,
            createdAt = createdAt,
            updatedAt = updatedAt,
            stargazerCount = stargazerCount,
            commentCount = comments.totalCount,
            previewFile = previewFiles.orEmpty().filterNotNull().firstOrNull()?.toDomain(),
            fileCount = fileNodes.size,
            isFileCountCapped = fileNodes.size >= GIST_FILE_METADATA_LIMIT,
        )
    }

    private fun GistFileNode.toDomain() = GistFilePreview(
        name = name,
        text = text,
        size = size,
        isTruncated = isTruncated,
        isImage = isImage,
        languageName = language?.name,
        languageColor = language?.color,
    )
}
