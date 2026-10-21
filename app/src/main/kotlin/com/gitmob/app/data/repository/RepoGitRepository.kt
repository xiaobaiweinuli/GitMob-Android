package com.gitmob.app.data.repository

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.core.network.graphql.executeAsApiResult
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.*
import com.gitmob.app.graphql.CommitParentQuery
import com.gitmob.app.graphql.CreateCommitOnBranchMutation
import com.gitmob.app.graphql.RepoCodeTreeQuery
import com.gitmob.app.graphql.RepoCommitDetailQuery
import com.gitmob.app.graphql.RepoCommitHistoryQuery
import com.gitmob.app.graphql.RepoFileContentQuery
import com.gitmob.app.graphql.fragment.CommitFields
import com.gitmob.app.graphql.fragment.CommitWithParentsFields
import com.gitmob.app.graphql.type.CommitMessage
import com.gitmob.app.graphql.type.CommittableBranch
import com.gitmob.app.graphql.type.CreateCommitOnBranchInput
import com.gitmob.app.graphql.type.FileAddition
import com.gitmob.app.graphql.type.FileChanges
import com.gitmob.app.graphql.type.FileDeletion
import com.gitmob.app.core.error.HttpStatusException
import com.gitmob.app.core.error.UserVisibleException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git 数据的唯一仓库入口。
 *
 * GraphQL 负责树、Blob、ref 和提交历史；REST 只补充 GitHub GraphQL
 * 当前没有公开提供的逐文件 patch/raw 信息。这里不缓存内容，所有读取都
 * 以调用时传入的 ref/HEAD 为准。
 */
@Singleton
class RepoGitRepository @Inject constructor(
    private val api: GHApiClient,
    private val apolloClient: ApolloClient,
) {

    /** REST commit comparison. Pagination is deliberately hidden in this repository API. */
    suspend fun compare(
        baseOwner: String,
        baseRepository: String,
        baseRef: String,
        headOwner: String,
        headRepository: String,
        headRef: String,
        page: Int = 1,
    ): ApiResult<RepoComparisonResult> = safeCall {
        require(baseRef.isNotBlank() && headRef.isNotBlank()) { "Compare refs must not be blank" }
        val sameRepository = baseOwner == headOwner && baseRepository == headRepository
        fun refPath(value: String) = value.replace("%", "%25").replace("/", "%2F").replace(" ", "%20")
        val basehead = if (sameRepository) {
            "${refPath(baseRef)}...${refPath(headRef)}"
        } else {
            "$baseOwner:${refPath(baseRef)}...$headOwner:${refPath(headRef)}"
        }
        val response = try {
            api.get<RestRepoCompareResponse>(
                "/repos/$baseOwner/$baseRepository/compare/$basehead?page=$page&per_page=${PageSize.COMPARE_COMMITS}",
            )
        } catch (error: HttpStatusException) {
            if (error.code == 404 && error.message.orEmpty().contains("No common ancestor", ignoreCase = true)) {
                return@safeCall RepoComparisonResult.NoCommonAncestor
            }
            throw error
        }
        val files = response.files.take(300).map(::toChangedFile)
        val commits = response.commits.map(::toCompareCommit)
        RepoComparisonResult.Available(RepoComparison(
            refs = RepoComparisonRefs(baseOwner, baseRepository, baseRef, headOwner, headRepository, headRef),
            status = response.status,
            aheadBy = response.aheadBy,
            behindBy = response.behindBy,
            totalCommits = response.totalCommits,
            commits = commits,
            files = files,
            additions = files.sumOf { it.additions },
            deletions = files.sumOf { it.deletions },
            filesTruncated = response.files.size >= 300,
            commitsPage = page,
            commitsHasNextPage = page * PageSize.COMPARE_COMMITS < response.totalCommits,
        ))
    }

    suspend fun getCodeTree(owner: String, name: String, ref: String, path: String = ""): ApiResult<RepoCodeTree> {
        val expression = if (path.isBlank()) "$ref:" else "$ref:$path"
        val qualifiedRef = if (ref.startsWith("refs/")) ref else "refs/heads/$ref"
        return when (val result = apolloClient.query(
            RepoCodeTreeQuery(owner, name, qualifiedRef, expression),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository ?: error("Repository not found")
                val tree = repository.`object`?.onTree ?: error("Tree not found")
                val repositoryRef = repository.ref ?: error("Branch not found")
                val head = repositoryRef.target?.oid ?: error("Branch head not found")
                val permission = permission(repository.viewerPermission?.rawValue)
                RepoCodeTree(
                    repositoryId = repository.id,
                    permission = permission,
                    capabilities = permission.toCapabilities(),
                    isArchived = repository.isArchived,
                    ref = repositoryRef.name,
                    headOid = head,
                    path = path,
                    entries = tree.entries.orEmpty().filterNotNull().map(::toTreeEntry),
                )
            }
        }
    }

    suspend fun getFileContent(owner: String, name: String, ref: String, path: String): ApiResult<RepoFileContent> {
        require(path.isNotBlank()) { "File path is empty" }
        val expression = "$ref:$path"
        val qualifiedRef = if (ref.startsWith("refs/")) ref else "refs/heads/$ref"
        return when (val result = apolloClient.query(
            RepoFileContentQuery(owner, name, qualifiedRef, expression),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository ?: error("Repository not found")
                val blob = repository.`object`?.onBlob ?: error("File not found")
                val repositoryRef = repository.ref ?: error("Branch not found")
                val permission = permission(repository.viewerPermission?.rawValue)
                RepoFileContent(
                    repositoryId = repository.id,
                    permission = permission,
                    capabilities = permission.toCapabilities(),
                    isArchived = repository.isArchived,
                    ref = repositoryRef.name,
                    headOid = repositoryRef.target?.oid ?: error("Branch head not found"),
                    path = path,
                    oid = blob.oid,
                    byteSize = blob.byteSize.toLong(),
                    isBinary = blob.isBinary == true,
                    isTruncated = blob.isTruncated,
                    text = blob.text,
                )
            }
        }
    }

    suspend fun getCommitHistory(owner: String, name: String, ref: String, path: String? = null, after: String? = null): ApiResult<PagedRepoCommits> {
        val qualifiedRef = if (ref.startsWith("refs/")) ref else "refs/heads/$ref"
        return when (val result = apolloClient.query(
            RepoCommitHistoryQuery(
                owner = owner,
                name = name,
                qualifiedRef = qualifiedRef,
                after = Optional.presentIfNotNull(after),
                path = Optional.presentIfNotNull(path),
                first = PageSize.REPO_ISSUES,
                parentsFirst = PageSize.COMMIT_PARENTS,
            ),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository ?: error("Repository not found")
                val permission = permission(repository.viewerPermission?.rawValue)
                val repositoryRef = repository.ref ?: error("Branch not found")
                val target = repositoryRef.target?.onCommit ?: error("Branch not found")
                val history = target.history
                PagedRepoCommits(
                    repositoryId = repository.id,
                    permission = permission,
                    capabilities = permission.toCapabilities(),
                    isArchived = repository.isArchived,
                    ref = repositoryRef.name,
                    headOid = target.oid,
                    totalCount = history.totalCount,
                    items = history.nodes.orEmpty().filterNotNull().map { toCommitSummary(it.commitWithParentsFields) },
                    hasNextPage = history.pageInfo.hasNextPage,
                    endCursor = history.pageInfo.endCursor,
                )
            }
        }
    }

    suspend fun getFileHistory(owner: String, name: String, ref: String, path: String, after: String? = null): ApiResult<PagedRepoCommits> =
        getCommitHistory(owner, name, ref, path, after)

    suspend fun getCommitDetail(owner: String, name: String, ref: String, sha: String): ApiResult<RepoCommitDetail> {
        val qualifiedRef = if (ref.startsWith("refs/")) ref else "refs/heads/$ref"
        return when (val result = apolloClient.query(
            RepoCommitDetailQuery(owner, name, qualifiedRef, sha, PageSize.COMMIT_PARENTS),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val repository = result.data.repository ?: error("Repository not found")
                val commitNode = repository.`object`?.onCommit ?: error("Commit not found")
                val permission = permission(repository.viewerPermission?.rawValue)
                val commit = toCommitSummary(commitNode.commitWithParentsFields)
                val rest = api.get<RestRepoCommitResponse>("/repos/$owner/$name/commits/$sha")
                val files = rest.files.map(::toChangedFile)
                RepoCommitDetail(repository.id, permission, permission.toCapabilities(), repository.isArchived, commit, files, false)
            }
        }
    }

    suspend fun getChangedFiles(owner: String, name: String, sha: String): ApiResult<List<RepoChangedFile>> = safeCall {
        api.get<RestRepoCommitResponse>("/repos/$owner/$name/commits/$sha").files.map(::toChangedFile)
    }

    suspend fun resolveFileDownloadUrl(owner: String, name: String, ref: String, path: String): ApiResult<String?> = safeCall {
        api.resolveRestDownloadUrl("/repos/$owner/$name/contents/$path?ref=$ref", "application/octet-stream")
    }

    suspend fun resolveArchiveDownloadUrl(owner: String, name: String, ref: String, format: String = "zipball"): ApiResult<String?> = safeCall {
        require(format == "zipball" || format == "tarball") { "Unsupported archive format" }
        api.resolveRestDownloadUrl("/repos/$owner/$name/$format/$ref")
    }

    suspend fun revertFile(
        owner: String,
        name: String,
        branch: String,
        targetSha: String,
        path: String,
        message: String,
        previousPath: String? = null,
    ): ApiResult<RepoCommitSummary> {
        // 父提交对探测：只需区分零、一个或多个父提交，不是分页列表。
        // safeCall 是 crossinline，失败必须直接返回 Failure，不能包进 safeCall。
        val parentOids = when (val result = apolloClient.query(
            CommitParentQuery(owner, name, targetSha),
        ).executeAsApiResult()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.data.repository?.`object`?.onCommit?.parents?.nodes.orEmpty().mapNotNull { it?.oid }
        }
        return safeCall {
        if (parentOids.size != 1) throw UserVisibleException(R.string.git_revert_merge_unsupported)
        val parentSha = parentOids.single()
        val currentHead = getCodeTree(owner, name, branch, path.substringBeforeLast('/', "")).let { result ->
            (result as? ApiResult.Success)?.data?.headOid ?: error("Current branch head unavailable")
        }
        val restorePath = previousPath ?: path
        val parentBytes = try {
            api.getRawBytes("/repos/$owner/$name/contents/$restorePath?ref=$parentSha", "application/vnd.github.raw")
        } catch (error: HttpStatusException) {
            if (error.code == 404) null else throw error
        }
        val additions = parentBytes?.let { listOf(RepoPendingFileChange.Addition(restorePath, java.util.Base64.getEncoder().encodeToString(it))) }.orEmpty()
        val deletions = if (parentBytes == null || restorePath != path) listOf(RepoPendingFileChange.Deletion(path)) else emptyList()
        createCommit(owner, name, branch, message, additions, deletions, currentHead)
            .let { result -> (result as? ApiResult.Success)?.data ?: error("Revert commit failed") }
        }
    }

    suspend fun revertCommit(
        owner: String,
        name: String,
        branch: String,
        targetSha: String,
        message: String,
    ): ApiResult<RepoCommitSummary> = safeCall {
        val detail = when (val result = getCommitDetail(owner, name, branch, targetSha)) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> error("Unable to load commit for revert")
        }
        val parentSha = detail.commit.parentOids.singleOrNull()
            ?: throw UserVisibleException(R.string.git_revert_merge_unsupported)
        val currentHead = getCodeTree(owner, name, branch).let { result ->
            (result as? ApiResult.Success)?.data?.headOid ?: error("Current branch head unavailable")
        }
        val additions = mutableListOf<RepoPendingFileChange.Addition>()
        val deletions = mutableListOf<RepoPendingFileChange.Deletion>()
        detail.changedFiles.forEach { file ->
            val restorePath = file.previousFilename ?: file.filename
            val parentBytes = try {
                api.getRawBytes("/repos/$owner/$name/contents/$restorePath?ref=$parentSha", "application/vnd.github.raw")
            } catch (error: HttpStatusException) {
                if (error.code == 404) null else throw error
            }
            if (parentBytes == null) {
                deletions += RepoPendingFileChange.Deletion(file.filename)
            } else {
                additions += RepoPendingFileChange.Addition(
                    restorePath,
                    java.util.Base64.getEncoder().encodeToString(parentBytes),
                )
                if (restorePath != file.filename) deletions += RepoPendingFileChange.Deletion(file.filename)
            }
        }
        if (additions.isEmpty() && deletions.isEmpty()) error("Commit has no reversible file changes")
        createCommit(owner, name, branch, message, additions, deletions, currentHead)
            .let { result -> (result as? ApiResult.Success)?.data ?: error("Revert commit failed") }
    }

    suspend fun createFolderZip(owner: String, name: String, ref: String, rootPath: String, onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }): ApiResult<ByteArray> = safeCall {
        val entries = mutableListOf<RepoTreeEntry>()
        suspend fun collect(path: String) {
            coroutineContext.ensureActive()
            val tree = getCodeTree(owner, name, ref, path).let { result -> (result as? ApiResult.Success)?.data ?: error("Unable to read tree") }
            tree.entries.forEach { entry -> if (entry.type == RepoEntryType.DIRECTORY) collect(entry.path) else entries += entry }
        }
        collect(rootPath)
        val output = ByteArrayOutputStream()
        var completed = 0
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            ZipOutputStream(output).use { zip ->
                entries.forEach { entry ->
                    coroutineContext.ensureActive()
                    val bytes = api.getRawBytes("/repos/$owner/$name/contents/${entry.path}?ref=$ref", "application/vnd.github.raw")
                    zip.putNextEntry(ZipEntry(entry.path.removePrefix(rootPath.trimEnd('/') + "/")))
                    zip.write(bytes)
                    zip.closeEntry()
                    completed++
                    onProgress(completed, entries.size)
                }
            }
        }
        output.toByteArray()
    }

    suspend fun createCommit(
        owner: String,
        name: String,
        branch: String,
        message: String,
        additions: List<RepoPendingFileChange.Addition>,
        deletions: List<RepoPendingFileChange.Deletion>,
        expectedHeadOid: String,
    ): ApiResult<RepoCommitSummary> {
        val input = CreateCommitOnBranchInput(
            branch = CommittableBranch(
                repositoryNameWithOwner = Optional.present("$owner/$name"),
                branchName = Optional.present(branch),
            ),
            message = CommitMessage(headline = message),
            fileChanges = Optional.present(FileChanges(
                additions = Optional.present(additions.map { FileAddition(path = it.path, contents = it.contentBase64) }),
                deletions = Optional.present(deletions.map { FileDeletion(path = it.path) }),
            )),
            expectedHeadOid = expectedHeadOid,
            clientMutationId = Optional.present("gitmob"),
        )
        return when (val result = apolloClient.mutation(CreateCommitOnBranchMutation(input)).executeAsApiResult()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> safeCall {
                val commit = result.data.createCommitOnBranch?.commit
                    ?: error("Commit was not created")
                toCommitSummary(commit.commitFields)
            }
        }
    }

    private fun permission(value: String?): RepoPermission = value?.let { runCatching { RepoPermission.valueOf(it) }.getOrNull() } ?: RepoPermission.NONE

    private fun toTreeEntry(node: RepoCodeTreeQuery.Entry) = RepoTreeEntry(
        name = node.name,
        path = node.path ?: node.name,
        type = when (node.type.uppercase()) {
            "BLOB" -> RepoEntryType.FILE
            "TREE" -> RepoEntryType.DIRECTORY
            "COMMIT" -> RepoEntryType.SUBMODULE
            else -> RepoEntryType.UNKNOWN
        },
        oid = node.oid,
        size = node.size.toLong(),
        extension = node.extension,
        languageName = node.language?.name,
    )

    private fun toActor(node: CommitFields.Author?): RepoGitActor? = node?.let {
        RepoGitActor(it.user?.login, it.name, it.email, it.user?.avatarUrl ?: it.avatarUrl, it.date)
    }

    private fun toActor(node: CommitFields.Committer?): RepoGitActor? = node?.let {
        RepoGitActor(it.user?.login, it.name, it.email, it.user?.avatarUrl ?: it.avatarUrl, it.date)
    }

    private fun toCommitSummary(fields: CommitWithParentsFields) = toCommitSummary(
        fields.commitFields,
        parentOids = fields.parents.nodes.orEmpty().mapNotNull { it?.oid },
    )

    private fun toCommitSummary(fields: CommitFields, parentOids: List<String> = emptyList()) = RepoCommitSummary(
        oid = fields.oid,
        abbreviatedOid = fields.abbreviatedOid.ifBlank { fields.oid.take(7) },
        headline = fields.messageHeadline,
        body = fields.messageBody,
        authoredDate = fields.authoredDate,
        committedDate = fields.committedDate,
        author = toActor(fields.author),
        committer = toActor(fields.committer),
        additions = fields.additions,
        deletions = fields.deletions,
        changedFiles = fields.changedFilesIfAvailable,
        parentOids = parentOids,
        url = fields.url,
    )

    private fun toChangedFile(file: RestRepoCommitFile) = RepoChangedFile(
        filename = file.filename,
        previousFilename = file.previousFilename,
        status = runCatching { RepoChangedFileStatus.valueOf(file.status.uppercase()) }.getOrDefault(RepoChangedFileStatus.UNKNOWN),
        additions = file.additions,
        deletions = file.deletions,
        changes = file.changes,
        patch = file.patch,
        blobUrl = file.blobUrl,
        rawUrl = file.rawUrl,
        contentsUrl = file.contentsUrl,
        oid = file.sha,
    )

    private fun toCompareCommit(commit: RestRepoCompareCommit) = RepoCommitSummary(
        oid = commit.sha,
        abbreviatedOid = commit.sha.take(7),
        headline = commit.commit.message.lineSequence().firstOrNull().orEmpty(),
        body = commit.commit.message.lineSequence().drop(1).joinToString("\n").trim(),
        authoredDate = commit.commit.authorData?.date,
        committedDate = commit.commit.committerData?.date,
        author = commit.author?.let { RepoGitActor(it.login, it.login, null, it.avatarUrl, commit.commit.authorData?.date) },
        committer = commit.committer?.let { RepoGitActor(it.login, it.login, null, it.avatarUrl, commit.commit.committerData?.date) },
        additions = 0,
        deletions = 0,
        changedFiles = null,
        url = commit.htmlUrl,
    )
}
