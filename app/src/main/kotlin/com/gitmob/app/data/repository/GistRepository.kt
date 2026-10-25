package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.auth.TokenStorage
import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.diff.LineDiffGenerator
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.data.model.GistCategory
import com.gitmob.app.data.model.CommentAuthorAssociation
import com.gitmob.app.data.model.GistComment
import com.gitmob.app.data.model.GistCommentDto
import com.gitmob.app.data.model.GistCommentPage
import com.gitmob.app.data.model.GistCommentRequest
import com.gitmob.app.data.model.GistCreateFileRequest
import com.gitmob.app.data.model.GistCreateRequest
import com.gitmob.app.data.model.GistCreateResponseDto
import com.gitmob.app.data.model.GistDetail
import com.gitmob.app.data.model.GistDetailDto
import com.gitmob.app.data.model.GistDetailsDto
import com.gitmob.app.data.model.GistDiffUnavailableReason
import com.gitmob.app.data.model.GistFile
import com.gitmob.app.data.model.GistFileDiff
import com.gitmob.app.data.model.GistFileDto
import com.gitmob.app.data.model.GistFileUpdateDto
import com.gitmob.app.data.model.GistFilePreview
import com.gitmob.app.data.model.GistFork
import com.gitmob.app.data.model.GistForkDto
import com.gitmob.app.data.model.GistForkPage
import com.gitmob.app.data.model.GistForkSource
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.GistPage
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.model.GistRevision
import com.gitmob.app.data.model.GistRevisionComparison
import com.gitmob.app.data.model.GistRevisionDto
import com.gitmob.app.data.model.GistRevisionPage
import com.gitmob.app.data.model.GistSort
import com.gitmob.app.data.model.GistUpdateRequest
import com.gitmob.app.data.model.GistUser
import com.gitmob.app.data.model.GistUserDto
import com.gitmob.app.data.model.GistVisibility
import com.gitmob.app.graphql.UserGistsQuery
import com.gitmob.app.graphql.ViewerGistsQuery
import com.gitmob.app.graphql.GistSocialQuery
import com.gitmob.app.graphql.GistStargazersQuery
import com.gitmob.app.graphql.fragment.GistConnectionFields
import com.gitmob.app.graphql.type.GistOrder
import com.gitmob.app.graphql.type.GistOrderField
import com.gitmob.app.graphql.type.OrderDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val GIST_CACHE_TTL_MS = 5 * 60_000L
private const val GIST_COMMENT_ACCEPT = "application/vnd.github.full+json"

@Singleton
class GistRepository @Inject constructor(
    private val api: GHApiClient,
    private val apolloClient: ApolloClient,
    private val tokenStorage: TokenStorage,
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

    suspend fun getGist(id: String): ApiResult<GistDetail> {
        if (id.isBlank()) return safeCall { error("Gist id must not be blank") }
        val dto = when (val result = safeCall { api.get<GistDetailDto>("/gists/$id") }) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data
        }
        val social = when (val result = apolloClient.query(GistSocialQuery(dto.nodeId)).executeAsApiResult()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data.node?.onGist
                ?: return safeCall { error("Gist social data is unavailable") }
        }
        return safeCall {
            dto.toDomain(
                viewerLogin = activeLogin(),
                isStarred = social.viewerHasStarred,
                stargazerCount = social.stargazerCount,
                forkCount = social.forks.totalCount,
            )
        }
    }

    suspend fun getGistFile(id: String, filename: String): ApiResult<GistFile> = safeCall {
        require(filename.isNotBlank()) { "Filename must not be blank" }
        val files = api.get<GistDetailDto>(id.path()).files
        (files[filename] ?: files.values.firstOrNull { it.filename == filename }
            ?: error("Gist file is unavailable")).toDomain()
    }

    suspend fun getStargazers(nodeId: String, after: String? = null): ApiResult<PagedUsers> =
        when (val result = apolloClient.query(
            GistStargazersQuery(nodeId, Optional.presentIfNotNull(after), PageSize.USER_LIST),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val connection = result.data.node?.onGist?.stargazers
                    ?: error("Gist stargazers are unavailable")
                PagedUsers(
                    totalCount = connection.totalCount,
                    users = connection.nodes.orEmpty().filterNotNull().map {
                        SimpleUser(it.login, it.name, it.avatarUrl, it.bio)
                    },
                    hasNextPage = connection.pageInfo.hasNextPage,
                    endCursor = connection.pageInfo.endCursor,
                )
            }
        }

    suspend fun getOldestVersion(id: String): ApiResult<String?> = safeCall {
        require(id.isNotBlank()) { "Gist id must not be blank" }
        var page = 1
        var oldest: GistRevisionDto? = null
        do {
            val revisions = api.get<List<GistRevisionDto>>(
                "/gists/$id/commits?per_page=${PageSize.GIST_REVISION_SCAN}&page=$page",
            )
            revisions.forEach { revision ->
                val currentOldest = oldest
                if (revision.version.isNotBlank() &&
                    (currentOldest == null || revision.committedAt < currentOldest.committedAt)
                ) oldest = revision
            }
            page++
        } while (revisions.size == PageSize.GIST_REVISION_SCAN)
        oldest?.version
    }

    suspend fun getRevisions(id: String, page: Int = 1): ApiResult<GistRevisionPage> = safeCall {
        require(id.isNotBlank()) { "Gist id must not be blank" }
        require(page > 0) { "Page must be positive" }
        val items = api.get<List<GistRevisionDto>>(
            "/gists/$id/commits?per_page=${PageSize.GIST_REVISIONS}&page=$page",
        ).map { it.toDomain() }
        GistRevisionPage(items, (page + 1).takeIf { items.size == PageSize.GIST_REVISIONS })
    }

    suspend fun getForks(id: String, page: Int = 1): ApiResult<GistForkPage> = safeCall {
        require(id.isNotBlank()) { "Gist id must not be blank" }
        require(page > 0) { "Page must be positive" }
        val items = api.get<List<GistForkDto>>(
            "/gists/$id/forks?per_page=${PageSize.GIST_FORKS}&page=$page",
        ).map { it.toDomain() }
        GistForkPage(items, (page + 1).takeIf { items.size == PageSize.GIST_FORKS })
    }

    suspend fun getComments(id: String, page: Int = 1): ApiResult<GistCommentPage> = safeCall {
        require(id.isNotBlank()) { "Gist id must not be blank" }
        require(page > 0) { "Page must be positive" }
        val items = api.get<List<GistCommentDto>>(
            "/gists/$id/comments?per_page=${PageSize.GIST_COMMENTS}&page=$page",
            accept = GIST_COMMENT_ACCEPT,
        ).map { it.toDomain() }
        GistCommentPage(items, (page + 1).takeIf { items.size == PageSize.GIST_COMMENTS })
    }

    suspend fun setStarred(id: String, starred: Boolean): ApiResult<Boolean> = safeCall {
        require(id.isNotBlank()) { "Gist id must not be blank" }
        if (starred) api.putNoBody<Unit>("/gists/$id/star") else api.delete<Unit>("/gists/$id/star")
        starred
    }

    suspend fun fork(id: String): ApiResult<GistDetail> = safeCall {
        require(id.isNotBlank()) { "Gist id must not be blank" }
        val viewerLogin = activeLogin()
        api.post<GistDetailDto, Map<String, String>>("/gists/$id/forks", emptyMap())
            .toDomain(viewerLogin, isStarred = false, stargazerCount = 0, forkCount = 0)
    }

    suspend fun createGist(
        description: String?,
        isPublic: Boolean,
        files: Map<String, String>,
    ): ApiResult<String> = safeCall {
        require(files.isNotEmpty()) { "At least one file is required" }
        val normalized = files.map { (filename, content) -> filename.trim() to content }
        require(normalized.all { (filename, _) -> filename.isNotEmpty() }) { "Filename must not be blank" }
        require(normalized.map { it.first }.distinct().size == normalized.size) { "Filenames must be unique" }
        val created = api.post<GistCreateResponseDto, GistCreateRequest>(
            "/gists",
            GistCreateRequest(
                description = description?.takeIf(String::isNotBlank),
                isPublic = isPublic,
                files = normalized.associate { (filename, content) ->
                    filename to GistCreateFileRequest(content)
                },
            ),
        )
        require(created.id.isNotBlank()) { "Created Gist id is missing" }
        invalidateAllCaches()
        created.id
    }

    suspend fun updateDescription(id: String, description: String): ApiResult<Unit> = safeCall {
        api.patch<GistDetailDto, GistUpdateRequest>(id.path(), GistUpdateRequest(description = description))
    }

    suspend fun updateFile(
        id: String,
        originalFilename: String,
        filename: String,
        content: String,
    ): ApiResult<Unit> = safeCall {
        require(originalFilename.isNotBlank()) { "Original filename must not be blank" }
        require(filename.isNotBlank()) { "Filename must not be blank" }
        api.patch<GistDetailDto, GistUpdateRequest>(
            id.path(),
            GistUpdateRequest(
                files = mapOf(originalFilename to GistFileUpdateDto(filename = filename, content = content)),
            ),
        )
    }

    suspend fun addFile(id: String, filename: String, content: String): ApiResult<Unit> = safeCall {
        val normalized = filename.trim()
        require(normalized.isNotBlank()) { "Filename must not be blank" }
        val current = api.get<GistDetailDto>(id.path())
        if (normalized in current.files) throw UserVisibleException(R.string.gist_file_exists)
        api.patch<GistDetailDto, GistUpdateRequest>(
            id.path(),
            GistUpdateRequest(
                files = mapOf(normalized to GistFileUpdateDto(filename = normalized, content = content)),
            ),
        )
    }

    suspend fun deleteFile(id: String, filename: String): ApiResult<Unit> = safeCall {
        require(filename.isNotBlank()) { "Filename must not be blank" }
        api.patch<GistDetailDto, GistUpdateRequest>(
            id.path(),
            GistUpdateRequest(files = mapOf(filename to null)),
        )
    }

    suspend fun deleteGist(id: String): ApiResult<Unit> = safeCall {
        api.delete<Unit>(id.path())
        invalidateAllCaches()
    }

    suspend fun createComment(id: String, body: String): ApiResult<GistComment> = safeCall {
        require(body.isNotBlank()) { "Comment body must not be blank" }
        api.post<GistCommentDto, GistCommentRequest>(
            "${id.path()}/comments",
            GistCommentRequest(body),
            accept = GIST_COMMENT_ACCEPT,
        ).toDomain()
    }

    suspend fun updateComment(id: String, commentId: Long, body: String): ApiResult<GistComment> = safeCall {
        require(body.isNotBlank()) { "Comment body must not be blank" }
        api.patch<GistCommentDto, GistCommentRequest>(
            "${id.path()}/comments/$commentId",
            GistCommentRequest(body),
            accept = GIST_COMMENT_ACCEPT,
        ).toDomain()
    }

    suspend fun deleteComment(id: String, commentId: Long): ApiResult<Unit> = safeCall {
        api.delete<Unit>("${id.path()}/comments/$commentId")
    }

    suspend fun compareRevisions(
        id: String,
        revision: GistRevision,
        previousVersion: String?,
    ): ApiResult<GistRevisionComparison> = safeCall {
        require(revision.version.isNotBlank()) { "Revision version must not be blank" }
        val current = api.get<GistDetailDto>("${id.path()}/${revision.version}")
        if (previousVersion == null) {
            return@safeCall GistRevisionComparison(revision, null, emptyList())
        }
        val previous = api.get<GistDetailDto>("${id.path()}/$previousVersion")
        GistRevisionComparison(
            revision = revision,
            previousVersion = previousVersion,
            files = withContext(Dispatchers.Default) { compareFiles(previous.files, current.files) },
        )
    }

    private suspend fun activeLogin(): String = requireNotNull(tokenStorage.activeLogin.first()) {
        "Active login is required"
    }

    private fun String.path(): String {
        require(isNotBlank()) { "Gist id must not be blank" }
        return "/gists/$this"
    }

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
            val page = result.data.page.withForkSources()
            if (cacheFirstPage) {
                firstPageCache.set(
                    CacheKey(result.data.viewerLogin, login?.lowercase(), category, sort, visibility),
                    page,
                )
            }
            ApiResult.Success(page)
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
        (category == GistCategory.ALL || isFork == (category == GistCategory.FORKED)) && when (visibility) {
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

    private suspend fun GistPage.withForkSources(): GistPage = copy(
        items = items.map { item ->
            if (!item.isFork) return@map item
            try {
                val source = api.get<GistDetailsDto>("/gists/${item.apiName}").forkOf ?: return@map item
                item.copy(
                    sourceId = source.id.takeIf(String::isNotBlank),
                    sourceOwnerLogin = source.owner?.login?.takeIf(String::isNotBlank),
                    sourceUrl = source.htmlUrl.takeIf(String::isNotBlank),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                item
            }
        },
    )

    private fun GistDetailDto.toDomain(
        viewerLogin: String,
        isStarred: Boolean,
        stargazerCount: Int,
        forkCount: Int,
    ): GistDetail = GistDetail(
        id = id,
        nodeId = nodeId,
        url = htmlUrl,
        description = description?.takeIf(String::isNotBlank),
        isPublic = public,
        owner = (owner ?: user)?.toDomain(),
        files = files.values.map { it.toDomain() }.sortedBy(GistFile::filename),
        commentCount = comments,
        commentsEnabled = commentsEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        forkOf = forkOf?.let {
            GistForkSource(
                id = it.id,
                owner = (it.owner ?: it.user)?.toDomain(),
                url = it.htmlUrl,
            )
        },
        latestVersion = history.firstNotNullOfOrNull { it.version.takeIf(String::isNotBlank) }
            ?: files.values.firstNotNullOfOrNull { it.rawUrl.gistVersion() },
        viewerLogin = viewerLogin,
        isStarred = isStarred,
        stargazerCount = stargazerCount,
        forkCount = forkCount,
    )

    private fun GistFileDto.toDomain() = GistFile(
        filename = filename,
        type = type,
        language = language,
        rawUrl = rawUrl,
        size = size,
        isTruncated = truncated,
        content = content,
    )

    private fun String.gistVersion(): String? = substringAfter("/raw/", "")
        .substringBefore('/')
        .takeIf { version ->
            (version.length == 40 || version.length == 64) && version.all { it.digitToIntOrNull(16) != null }
        }

    private fun GistUserDto.toDomain() = GistUser(
        login = login,
        avatarUrl = avatarUrl,
        url = htmlUrl,
    )

    private fun GistRevisionDto.toDomain() = GistRevision(
        version = version,
        user = user?.toDomain(),
        committedAt = committedAt,
        additions = changeStatus.additions,
        deletions = changeStatus.deletions,
        totalChanges = changeStatus.total,
    )

    private fun GistForkDto.toDomain() = GistFork(
        id = id,
        owner = (owner ?: user)?.toDomain(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun GistCommentDto.toDomain() = GistComment(
        id = id,
        nodeId = nodeId,
        url = htmlUrl.ifBlank { url },
        body = body,
        bodyHtml = bodyHtml.orEmpty(),
        author = user?.toDomain(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        authorAssociation = runCatching { CommentAuthorAssociation.valueOf(authorAssociation) }
            .getOrDefault(CommentAuthorAssociation.NONE),
    )

    private fun compareFiles(
        previous: Map<String, GistFileDto>,
        current: Map<String, GistFileDto>,
    ): List<GistFileDiff> = (previous.keys + current.keys).sorted().mapNotNull { filename ->
        val oldFile = previous[filename]
        val newFile = current[filename]
        val reason = when {
            oldFile?.truncated == true || newFile?.truncated == true -> GistDiffUnavailableReason.TRUNCATED
            oldFile != null && oldFile.content == null -> GistDiffUnavailableReason.MISSING_CONTENT
            newFile != null && newFile.content == null -> GistDiffUnavailableReason.MISSING_CONTENT
            oldFile?.content?.contains('\u0000') == true || newFile?.content?.contains('\u0000') == true ->
                GistDiffUnavailableReason.BINARY
            else -> null
        }
        if (reason != null) return@mapNotNull GistFileDiff(filename, null, reason)
        val diff = LineDiffGenerator.generate(oldFile?.content.orEmpty(), newFile?.content.orEmpty())
            ?: return@mapNotNull null
        GistFileDiff(filename, diff)
    }
}
