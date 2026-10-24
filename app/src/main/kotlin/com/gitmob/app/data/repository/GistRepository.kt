package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.GistFilePreview
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistVisibility
import com.gitmob.app.graphql.UserGistsQuery
import com.gitmob.app.graphql.ViewerGistsQuery
import com.gitmob.app.graphql.fragment.GistConnectionFields
import com.gitmob.app.graphql.type.GistOrder
import com.gitmob.app.graphql.type.GistOrderField
import com.gitmob.app.graphql.type.OrderDirection
import javax.inject.Inject
import javax.inject.Singleton

private const val GIST_CACHE_TTL_MS = 5 * 60_000L

@Singleton
class GistRepository @Inject constructor(
    private val apolloClient: ApolloClient,
) {
    private data class CacheKey(
        val viewerLogin: String,
        val targetLogin: String?,
        val category: GistCategory,
        val sort: GistSort,
        val visibility: GistVisibility,
    )

    private data class ScanResult(
        val viewerLogin: String,
        val page: GistPage,
    )

    /** 一次扫描批次的统一内部形状：ViewerGists / UserGists 两个查询共用片段后收敛到这里 */
    private data class GistBatch(
        val viewerLogin: String,
        val connection: GistConnectionFields?,
        val userMissing: Boolean,
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
        visibility: GistVisibility = GistVisibility.ALL,
    ): ApiResult<GistPage> {
        if (after == null) {
            currentViewerLogin?.let { viewerLogin ->
                firstPageCache.get(CacheKey(viewerLogin, login?.lowercase(), category, sort, visibility))?.let {
                    return ApiResult.Success(it)
                }
            }
        }

        return loadAndMap(login, category, sort, after, visibility, cacheFirstPage = after == null)
    }

    suspend fun getGistsFresh(
        login: String? = null,
        category: GistCategory,
        sort: GistSort,
        visibility: GistVisibility = GistVisibility.ALL,
    ): ApiResult<GistPage> = loadAndMap(
        login = login,
        category = category,
        sort = sort,
        after = null,
        visibility = visibility,
        cacheFirstPage = true,
    )

    private suspend fun loadAndMap(
        login: String?,
        category: GistCategory,
        sort: GistSort,
        after: String?,
        visibility: GistVisibility,
        cacheFirstPage: Boolean,
    ): ApiResult<GistPage> = when (val result = scanGists(login, category, sort, after, visibility)) {
        is ApiResult.Success -> {
            currentViewerLogin = result.data.viewerLogin
            if (cacheFirstPage) {
                firstPageCache.set(
                    CacheKey(result.data.viewerLogin, login?.lowercase(), category, sort, visibility),
                    result.data.page,
                )
            }
            ApiResult.Success(result.data.page)
        }
        is ApiResult.Failure -> result
    }

    private data class LoopStep(
        val viewerLogin: String,
        val cursor: String?,
        val hasNextPage: Boolean,
    )

    /**
     * fetch 失败直接返回 Failure（不走 safeCall，避免被 catch-all 吞成 Unknown）；
     * 映射层的 UserVisible/IllegalState 仍由 safeCall 按统一语义归类。
     */
    private suspend fun scanGists(
        targetLogin: String?,
        category: GistCategory,
        sort: GistSort,
        after: String?,
        visibility: GistVisibility,
    ): ApiResult<ScanResult> {
        val collected = mutableListOf<GistListItem>()
        var viewerLogin: String? = null
        var cursor = after
        var hasNextPage = true
        var scannedBatches = 0

        while (
            collected.size < PageSize.GISTS &&
            hasNextPage &&
            scannedBatches < PageSize.GIST_SCAN_BATCH_LIMIT
        ) {
            val batch = when (val result = fetchBatch(targetLogin, sort, cursor)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> result.data
            }
            val step = safeCall {
                processBatch(batch, targetLogin, viewerLogin, category, visibility, collected, cursor)
            }
            when (step) {
                is ApiResult.Failure -> return step
                is ApiResult.Success -> {
                    viewerLogin = step.data.viewerLogin
                    cursor = step.data.cursor
                    hasNextPage = step.data.hasNextPage
                }
            }
            scannedBatches++
        }

        return safeCall {
            ScanResult(
                viewerLogin = viewerLogin ?: throw IllegalStateException("Gist 查询缺少 viewer.login"),
                page = GistPage(
                    items = collected,
                    hasNextPage = hasNextPage,
                    nextCursor = cursor,
                ),
            )
        }
    }

    /** 处理一个扫描批次：校验归属、过滤分类与可见性、推进游标。 */
    private fun processBatch(
        batch: GistBatch,
        targetLogin: String?,
        previousViewerLogin: String?,
        category: GistCategory,
        visibility: GistVisibility,
        collected: MutableList<GistListItem>,
        cursorIn: String?,
    ): LoopStep {
        var cursor = cursorIn
        var hasNextPage = true
        val login = batch.viewerLogin
        if (previousViewerLogin != null && !previousViewerLogin.equals(login, ignoreCase = true)) {
            throw IllegalStateException("Gist 分页期间登录账号发生变化")
        }

        val connection = when {
            targetLogin == null -> batch.connection
                ?: throw IllegalStateException("viewer Gist 查询根字段缺失")
            batch.userMissing -> throw UserVisibleException(R.string.error_user_not_found)
            else -> batch.connection
                ?: throw UserVisibleException(R.string.error_user_not_found)
        }
        val edges = connection.edges.orEmpty().filterNotNull()
        var stoppedInsideBatch = false

        for ((index, edge) in edges.withIndex()) {
            cursor = edge.cursor
            edge.node?.let { node ->
                if (node.matches(category, visibility)) {
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

        return LoopStep(
            viewerLogin = login,
            cursor = cursor,
            hasNextPage = hasNextPage,
        )
    }

    private suspend fun fetchBatch(
        login: String?,
        sort: GistSort,
        after: String?,
    ): ApiResult<GistBatch> {
        val orderBy = Optional.presentIfNotNull(sort.toGistOrder())
        val cursor = Optional.presentIfNotNull(after)
        return if (login == null) {
            when (val result = apolloClient.query(ViewerGistsQuery(
                after = cursor,
                orderBy = orderBy,
                first = PageSize.GISTS,
                fileMetadataLimit = PageSize.GIST_FILE_METADATA_LIMIT,
            )).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> ApiResult.Success(
                    GistBatch(
                        viewerLogin = result.data.viewer.login,
                        connection = result.data.viewer.gists.gistConnectionFields,
                        userMissing = false,
                    ),
                )
            }
        } else {
            when (val result = apolloClient.query(UserGistsQuery(
                login = login,
                after = cursor,
                orderBy = orderBy,
                first = PageSize.GISTS,
                fileMetadataLimit = PageSize.GIST_FILE_METADATA_LIMIT,
            )).executeAsApiResult()) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> ApiResult.Success(
                    GistBatch(
                        viewerLogin = result.data.viewer.login,
                        connection = result.data.user?.gists?.gistConnectionFields,
                        userMissing = result.data.user == null,
                    ),
                )
            }
        }
    }

    /** GistSort → schema 生成的 GistOrder 输入类型（原字符串插值已收敛为类型安全枚举） */
    private fun GistSort.toGistOrder(): GistOrder = when (this) {
        GistSort.RECENTLY_CREATED -> GistOrder(
            field = GistOrderField.CREATED_AT,
            direction = OrderDirection.DESC,
        )
        GistSort.RECENTLY_UPDATED -> GistOrder(
            field = GistOrderField.UPDATED_AT,
            direction = OrderDirection.DESC,
        )
        GistSort.OLDEST_CREATED -> GistOrder(
            field = GistOrderField.CREATED_AT,
            direction = OrderDirection.ASC,
        )
        GistSort.OLDEST_UPDATED -> GistOrder(
            field = GistOrderField.UPDATED_AT,
            direction = OrderDirection.ASC,
        )
    }

    private fun GistConnectionFields.Node.matches(category: GistCategory, visibility: GistVisibility): Boolean =
        (isFork == (category == GistCategory.FORKED)) && when (visibility) {
            GistVisibility.ALL -> true
            GistVisibility.PUBLIC -> isPublic
            GistVisibility.PRIVATE -> !isPublic
        }

    private fun GistConnectionFields.Node.toDomain(viewerLogin: String): GistListItem {
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
            isFileCountCapped = fileNodes.size >= PageSize.GIST_FILE_METADATA_LIMIT,
        )
    }

    private fun GistConnectionFields.PreviewFile.toDomain() = GistFilePreview(
        name = name,
        text = text,
        size = size,
        isTruncated = isTruncated,
        isImage = isImage,
        languageName = language?.name,
        languageColor = language?.color,
    )
}
