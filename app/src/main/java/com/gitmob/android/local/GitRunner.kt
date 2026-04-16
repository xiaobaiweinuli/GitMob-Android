package com.gitmob.android.local

import com.gitmob.android.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 本地 Git 操作封装 —— 基于 JGit（纯 Java 实现，无需外部 git 可执行文件）
 *
 * ▸ 所有耗时操作均在 Dispatchers.IO 执行
 * ▸ Git 对象使用 use {} 确保资源正确释放
 * ▸ 统一返回 GitResult，调用方通过 .success / .error 判断结果
 */
object GitRunner {

    private const val TAG = "GitRunner"

    /** 所有操作都基于 JGit，始终可用 */
    val isGitAvailable: Boolean get() = true

    data class GitResult(
        val success: Boolean,
        val output: List<String> = emptyList(),
        val error: String = "",
    )

    /**
     * 变更文件信息
     */
    data class ChangedFile(
        val path: String,           // 文件路径
        val status: FileStatus,     // 文件状态
    )

    /**
     * 文件状态枚举
     */
    enum class FileStatus(val code: String) {
        MODIFIED("M"),          // 修改
        ADDED("A"),             // 新增
        REMOVED("D"),           // 删除
        CHANGED("C"),           // 变更（与modified类似）
        UNTRACKED("?"),         // 未跟踪
        MISSING("!"),           // 缺失
        CONFLICTING("U"),       // 冲突
        STAGED("S"),            // 已暂存
    }
    
    /**
     * 冲突检测结果
     */
    data class ConflictCheckResult(
        val hasLocalChanges: Boolean,           // 本地有未提交的变更
        val hasRemoteChanges: Boolean,          // 远程有新的变更
        val localCommitsAhead: Int,             // 本地领先远程的提交数
        val remoteCommitsAhead: Int,            // 远程领先本地的提交数
        val isConflicting: Boolean,              // 是否有冲突（双方都有变更）
    )

    /**
     * 提交历史信息
     */
    data class CommitInfo(
        val sha: String,        // 完整SHA
        val shortSha: String,   // 短SHA（7位）
        val message: String,    // 提交信息
        val author: String,     // 作者
        val authorEmail: String, // 作者邮箱
        val time: Date,         // 提交时间
    )

    /**
     * 仓库状态统计信息
     */
    data class RepoStatusStats(
        val modifiedCount: Int,  // 修改的文件数
        val addedCount: Int,     // 新增的文件数
        val removedCount: Int,   // 删除的文件数
        val stagedCount: Int,    // 已暂存的文件数
    )

    /**
     * 分支信息
     */
    data class BranchInfo(
        val name: String,       // 分支名称
        val isCurrent: Boolean, // 是否是当前分支
        val isRemote: Boolean,  // 是否是远程分支
    )

    // ─── 仓库探测 ──────────────────────────────────────────────────────────

    suspend fun isGitRepo(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path, ".git").exists().also {
                if (it) LogManager.d(TAG, "isGitRepo($path) = true")
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "isGitRepo 异常", e); false
        }
    }

    // ─── git init ──────────────────────────────────────────────────────────

    suspend fun init(path: String): GitResult = withContext(Dispatchers.IO) {
        try {
            val dir = File(path).also { it.mkdirs() }
            Git.init().setDirectory(dir).setInitialBranch("main").call().use {
                LogManager.i(TAG, "init 成功: $path")
                GitResult(true, listOf("Initialized empty Git repository in $path/.git/"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "init 失败", e)
            GitResult(false, error = e.message ?: "init 失败")
        }
    }

    // ─── git add ────────────────────────────────────────────────────────

    /**
     * 暂存所有变更
     */
    suspend fun addAll(path: String): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val status = git.status().call()
                
                status.added.forEach { git.add().addFilepattern(it).call() }
                status.changed.forEach { git.add().addFilepattern(it).call() }
                status.modified.forEach { git.add().addFilepattern(it).call() }
                status.uncommittedChanges.forEach { git.add().addFilepattern(it).call() }
                status.untracked.forEach { git.add().addFilepattern(it).call() }
                status.missing.forEach { git.rm().addFilepattern(it).call() }
                
                LogManager.d(TAG, "addAll 成功: $path")
                GitResult(true, listOf("已暂存所有变更"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "addAll 失败", e)
            GitResult(false, error = e.message ?: "addAll 失败")
        }
    }

    /**
     * 暂存单个文件
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide#AddCommand_.28git-add.29
     */
    suspend fun addFile(path: String, filePath: String): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.add().addFilepattern(filePath).call()
                LogManager.d(TAG, "add 成功: $filePath")
                GitResult(true, listOf("已暂存: $filePath"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "add 失败", e)
            GitResult(false, error = e.message ?: "add 失败")
        }
    }

    /**
     * 取消暂存文件
     */
    suspend fun unstageFile(path: String, filePath: String): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.reset().addPath(filePath).call()
                LogManager.d(TAG, "unstage 成功: $filePath")
                GitResult(true, listOf("已取消暂存: $filePath"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "unstage 失败", e)
            GitResult(false, error = e.message ?: "unstage 失败")
        }
    }

    // ─── git commit ───────────────────────────────────────────────────────

    suspend fun commit(
        path: String,
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val author = PersonIdent(authorName, authorEmail)
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(author)
                    .setCommitter(author)
                    .setAllowEmpty(false)
                    .call()
                val sha = commit.name.take(7)
                LogManager.i(TAG, "commit 成功: $sha $message (作者: $authorName <$authorEmail>)")
                GitResult(true, listOf("[main $sha] $message"))
            }
        } catch (e: EmptyCommitException) {
            LogManager.d(TAG, "nothing to commit")
            GitResult(true, listOf("nothing to commit"), "nothing to commit")
        } catch (e: NoHeadException) {
            // 首次提交没有 HEAD，也属于正常
            LogManager.d(TAG, "NoHeadException（首次提交）")
            GitResult(true, listOf("initial commit"))
        } catch (e: Exception) {
            LogManager.e(TAG, "commit 失败", e)
            GitResult(false, error = e.message ?: "commit 失败")
        }
    }

    // ─── git push ─────────────────────────────────────────────────────────

    /**
     * 推送到远程仓库
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide#PushCommand_.28git-push.29
     */
    suspend fun push(
        path: String,
        remoteUrl: String,
        branch: String,
        token: String,
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val creds = tokenCredentials(token)

                val results = git.push()
                    .setRemote("origin")
                    .setPushAll()
                    .setCredentialsProvider(creds)
                    .setForce(false)
                    .call()

                val msgs = results.flatMap { res ->
                    res.remoteUpdates.map { update ->
                        "${update.remoteName}: ${update.status}"
                    }
                }
                LogManager.i(TAG, "push 成功: $branch")
                GitResult(true, msgs.ifEmpty { listOf("推送成功") })
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "push 失败", e)
            GitResult(false, error = friendlyError(e))
        }
    }

    /**
     * 强制推送到远程仓库
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide#PushCommand_.28git-push.29
     */
    suspend fun forcePush(
        path: String,
        remoteUrl: String,
        branch: String,
        token: String,
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val creds = tokenCredentials(token)

                val results = git.push()
                    .setRemote("origin")
                    .setPushAll()
                    .setCredentialsProvider(creds)
                    .setForce(true)
                    .call()

                val msgs = results.flatMap { res ->
                    res.remoteUpdates.map { update ->
                        "${update.remoteName}: ${update.status}"
                    }
                }
                LogManager.i(TAG, "force push 成功: $branch")
                GitResult(true, msgs.ifEmpty { listOf("强制推送成功") })
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "force push 失败", e)
            GitResult(false, error = friendlyError(e))
        }
    }

    // ─── git pull ─────────────────────────────────────────────────────────

    /**
     * 从远程拉取
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide#PullCommand_.28git-pull.29
     */
    suspend fun pull(
        path: String,
        token: String,
        branch: String = "HEAD",
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val result = git.pull()
                    .setRemote("origin")
                    .setCredentialsProvider(tokenCredentials(token))
                    .call()

                if (result.isSuccessful) {
                    LogManager.i(TAG, "pull 成功")
                    GitResult(true, listOf("pull 成功"))
                } else {
                    val msg = result.mergeResult?.toString() ?: "pull 失败"
                    GitResult(false, error = msg)
                }
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "pull 失败", e)
            GitResult(false, error = friendlyError(e))
        }
    }

    /**
     * 强制拉取（会覆盖本地更改）
     * 使用 reset --hard 配合 fetch
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun forcePull(
        path: String,
        token: String,
        branch: String,
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                // 先 fetch
                git.fetch()
                    .setRemote("origin")
                    .setCredentialsProvider(tokenCredentials(token))
                    .call()

                // 然后 reset --hard
                git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/$branch")
                    .call()

                // 重新 checkout 到当前分支
                git.checkout()
                    .setName(branch)
                    .call()

                LogManager.i(TAG, "force pull 成功")
                GitResult(true, listOf("强制拉取成功，本地已重置为远程状态"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "force pull 失败", e)
            GitResult(false, error = friendlyError(e))
        }
    }

    // ─── git clone ────────────────────────────────────────────────────────

    suspend fun clone(
        url: String,
        targetDir: String,
        token: String,
        useRoot: Boolean = false,   // JGit 原生实现，无需 root
    ): GitResult = withContext(Dispatchers.IO) {
        try {
            val dir = File(targetDir)
            dir.mkdirs()

            LogManager.i(TAG, "clone 开始: ${sanitizeUrl(url)} → $targetDir")

            Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .setCredentialsProvider(tokenCredentials(token))
                .setCloneAllBranches(false)
                .setProgressMonitor(LogProgressMonitor())
                .call()
                .use { git ->
                    val repo = git.repository
                    val config = repo.config
                    
                    val originalUrl = config.getString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin", ConfigConstants.CONFIG_KEY_URL)
                    if (originalUrl != null) {
                        val cleanUrl = sanitizeUrl(originalUrl).replace("https://***@", "https://")
                        config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin", ConfigConstants.CONFIG_KEY_URL, cleanUrl)
                        config.save()
                    }
                    
                    LogManager.i(TAG, "clone 成功: $targetDir")
                    GitResult(true, listOf("克隆成功：$targetDir"))
                }
        } catch (e: Exception) {
            LogManager.e(TAG, "clone 失败", e)
            GitResult(false, error = friendlyError(e))
        }
    }

    // ─── git reset ────────────────────────────────────────────────────────

    suspend fun reset(path: String, sha: String, mode: String = "mixed"): GitResult =
        withContext(Dispatchers.IO) {
            try {
                openGit(path).use { git ->
                    val resetType = when (mode.lowercase()) {
                        "soft"  -> ResetCommand.ResetType.SOFT
                        "hard"  -> ResetCommand.ResetType.HARD
                        else    -> ResetCommand.ResetType.MIXED
                    }
                    git.reset().setMode(resetType).setRef(sha).call()
                    LogManager.i(TAG, "reset --$mode $sha 成功")
                    GitResult(true, listOf("reset --$mode ${sha.take(7)} 成功"))
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "reset 失败", e)
                GitResult(false, error = e.message ?: "reset 失败")
            }
        }

    // ─── 查询操作 ─────────────────────────────────────────────────────────

    /**
     * 获取当前分支名称
     */
    suspend fun currentBranch(path: String): String? = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.repository.branch.also { LogManager.d(TAG, "branch: $it") }
            }
        } catch (e: Exception) { LogManager.w(TAG, "currentBranch 异常", e); null }
    }

    /**
     * 获取最后一次提交信息
     */
    suspend fun lastCommitMsg(path: String): String? = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val repo = git.repository
                val headId = repo.resolve("HEAD") ?: return@withContext null
                RevWalk(repo).use { walk ->
                    val commit = walk.parseCommit(headId)
                    val sha = commit.name.take(7)
                    val msg = commit.shortMessage
                    "$sha $msg"
                }
            }
        } catch (e: Exception) { LogManager.w(TAG, "lastCommitMsg 异常", e); null }
    }

    /**
     * 获取远程URL
     */
    suspend fun remoteUrl(path: String): String? = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val url = git.repository.config
                    .getString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin",
                        ConfigConstants.CONFIG_KEY_URL)
                if (url != null) sanitizeUrl(url) else null
            }
        } catch (e: Exception) { null }
    }

    /**
     * 获取仓库的状态信息（包括所有变更文件）
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide#Porcelain_API
     */
    suspend fun getStatus(path: String): Status? = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.status().call()
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "getStatus 异常", e)
            null
        }
    }

    /**
     * 获取所有变更文件列表
     */
    suspend fun getChangedFiles(path: String): List<ChangedFile> = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val status = git.status().call()
                val files = mutableListOf<ChangedFile>()
                
                // 已暂存的文件
                status.added.forEach { files.add(ChangedFile(it, FileStatus.STAGED)) }
                status.changed.forEach { files.add(ChangedFile(it, FileStatus.STAGED)) }
                status.removed.forEach { files.add(ChangedFile(it, FileStatus.STAGED)) }
                
                // 未暂存的修改
                status.modified.forEach { 
                    if (!files.any { f -> f.path == it }) {
                        files.add(ChangedFile(it, FileStatus.MODIFIED))
                    }
                }
                status.uncommittedChanges.forEach { 
                    if (!files.any { f -> f.path == it }) {
                        files.add(ChangedFile(it, FileStatus.MODIFIED))
                    }
                }
                
                // 未跟踪文件
                status.untracked.forEach { files.add(ChangedFile(it, FileStatus.UNTRACKED)) }
                
                // 缺失文件
                status.missing.forEach { files.add(ChangedFile(it, FileStatus.MISSING)) }
                
                // 冲突文件
                status.conflicting.forEach { files.add(ChangedFile(it, FileStatus.CONFLICTING)) }
                
                files
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "getChangedFiles 异常", e)
            emptyList()
        }
    }

    /**
     * 获取仓库状态统计
     */
    suspend fun getRepoStatusStats(path: String): RepoStatusStats = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val status = git.status().call()
                
                var modifiedCount = 0
                var addedCount = 0
                var removedCount = 0
                var stagedCount = 0
                
                // 已暂存的文件
                stagedCount += status.added.size + status.changed.size + status.removed.size
                
                // 未暂存的修改
                modifiedCount += status.modified.size
                status.uncommittedChanges.forEach { 
                    if (!status.modified.contains(it)) {
                        modifiedCount++
                    }
                }
                
                // 未跟踪文件算作新增
                addedCount += status.untracked.size
                
                // 缺失文件算作删除
                removedCount += status.missing.size
                
                RepoStatusStats(modifiedCount, addedCount, removedCount, stagedCount)
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "getRepoStatusStats 异常", e)
            RepoStatusStats(0, 0, 0, 0)
        }
    }

    /**
     * 获取某个提交的变更文件列表
     * 参考JGit官方文档和Cookbook: https://github.com/centic9/jgit-cookbook
     */
    suspend fun getCommitChangedFiles(path: String, sha: String): List<ChangedFile> = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val files = mutableListOf<ChangedFile>()
                val commitId = ObjectId.fromString(sha)
                val repository = git.repository
                
                RevWalk(repository).use { walk ->
                    val commit = walk.parseCommit(commitId)
                    val outputStream = ByteArrayOutputStream()
                    
                    DiffFormatter(outputStream).use { formatter ->
                        formatter.setRepository(repository)
                        
                        val diffs = if (commit.parentCount > 0) {
                            val parent = walk.parseCommit(commit.getParent(0))
                            if (parent == null || parent.tree == null || commit.tree == null) {
                                LogManager.w(TAG, "getCommitChangedFiles: parent或tree为空")
                                emptyList()
                            } else {
                                formatter.scan(parent.tree, commit.tree)
                            }
                        } else {
                            if (commit.tree == null) {
                                LogManager.w(TAG, "getCommitChangedFiles: 初始提交tree为空")
                                emptyList()
                            } else {
                                formatter.scan(null, commit.tree)
                            }
                        }
                        
                        LogManager.d(TAG, "getCommitChangedFiles: 找到 ${diffs.size} 个变更文件")
                        
                        diffs.forEach { diff ->
                            val changeType = diff.changeType
                            val filePath = diff.newPath?.takeIf { it != "/dev/null" } ?: diff.oldPath
                            val status = when (changeType) {
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD -> FileStatus.ADDED
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> FileStatus.REMOVED
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY -> FileStatus.MODIFIED
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME -> FileStatus.MODIFIED
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY -> FileStatus.MODIFIED
                            }
                            LogManager.d(TAG, "  - $filePath: $changeType")
                            files.add(ChangedFile(filePath, status))
                        }
                    }
                }
                
                files
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "getCommitChangedFiles 异常", e)
            emptyList()
        }
    }

    /**
     * 获取某个提交中某个文件的diff
     * 参考JGit官方文档和Cookbook: https://github.com/centic9/jgit-cookbook
     */
    suspend fun getCommitFileDiff(path: String, sha: String, filePath: String): String = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val outputStream = ByteArrayOutputStream()
                val commitId = ObjectId.fromString(sha)
                val repository = git.repository
                
                RevWalk(repository).use { walk ->
                    val commit = walk.parseCommit(commitId)
                    
                    DiffFormatter(outputStream).use { formatter ->
                        formatter.setRepository(repository)
                        formatter.setDiffComparator(RawTextComparator.DEFAULT)
                        
                        val diffs = if (commit.parentCount > 0) {
                            val parent = commit.getParent(0)
                            formatter.scan(parent.tree, commit.tree)
                        } else {
                            formatter.scan(null, commit.tree)
                        }
                        
                        val targetDiff = diffs.find { diff ->
                            diff.newPath == filePath || diff.oldPath == filePath
                        }
                        
                        if (targetDiff != null) {
                            formatter.format(targetDiff)
                        } else {
                            outputStream.write("未找到文件 $filePath 的变更".toByteArray())
                        }
                    }
                }
                
                outputStream.toString("UTF-8")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "getCommitFileDiff 失败", e)
            "Error: ${e.message}"
        }
    }

    /**
     * 获取提交历史
     * @param limit 限制返回的提交数量，默认20
     */
    suspend fun getCommitHistory(path: String, limit: Int = 20): List<CommitInfo> = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val commits = mutableListOf<CommitInfo>()
                git.log().setMaxCount(limit).call().forEach { commit ->
                    commits.add(
                        CommitInfo(
                            sha = commit.name,
                            shortSha = commit.name.take(7),
                            message = commit.fullMessage,
                            author = commit.authorIdent.name,
                            authorEmail = commit.authorIdent.emailAddress,
                            time = Date(commit.commitTime * 1000L)
                        )
                    )
                }
                commits
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "getCommitHistory 异常", e)
            emptyList()
        }
    }
    
    /**
     * 检查本地仓库是否有未提交的变更
     */
    suspend fun hasLocalChanges(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val status = git.status().call()
                !status.isClean
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "hasLocalChanges 异常", e)
            false
        }
    }
    
    /**
     * 检查并获取冲突状态
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun checkForConflicts(
        path: String,
        remoteUrl: String,
        branch: String,
        token: String,
    ): ConflictCheckResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val status = git.status().call()
                val hasLocalChanges = !status.isClean
                
                var hasRemoteChanges = false
                var localCommitsAhead = 0
                var remoteCommitsAhead = 0
                
                try {
                    val creds = tokenCredentials(token)
                    
                    git.fetch()
                        .setRemote("origin")
                        .setCredentialsProvider(creds)
                        .call()
                    
                    val headId = git.repository.resolve("HEAD")
                    val remoteBranchId = git.repository.resolve("origin/$branch")
                    
                    if (headId != null && remoteBranchId != null) {
                        val walk = RevWalk(git.repository)
                        val headCommit = walk.parseCommit(headId)
                        val remoteCommit = walk.parseCommit(remoteBranchId)
                        
                        val isAncestor = walk.isMergedInto(headCommit, remoteCommit)
                        val isDescendant = walk.isMergedInto(remoteCommit, headCommit)
                        
                        if (!isAncestor && !isDescendant) {
                            hasRemoteChanges = true
                            localCommitsAhead = countCommits(git, headId, remoteBranchId)
                            remoteCommitsAhead = countCommits(git, remoteBranchId, headId)
                        } else if (!isAncestor) {
                            localCommitsAhead = countCommits(git, headId, remoteBranchId)
                        } else if (!isDescendant) {
                            hasRemoteChanges = true
                            remoteCommitsAhead = countCommits(git, remoteBranchId, headId)
                        }
                        
                        walk.close()
                    }
                } catch (e: Exception) {
                    LogManager.w(TAG, "检查远程变更失败", e)
                }
                
                ConflictCheckResult(
                    hasLocalChanges = hasLocalChanges,
                    hasRemoteChanges = hasRemoteChanges,
                    localCommitsAhead = localCommitsAhead,
                    remoteCommitsAhead = remoteCommitsAhead,
                    isConflicting = hasLocalChanges && hasRemoteChanges
                )
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "checkForConflicts 异常", e)
            ConflictCheckResult(
                hasLocalChanges = false,
                hasRemoteChanges = false,
                localCommitsAhead = 0,
                remoteCommitsAhead = 0,
                isConflicting = false
            )
        }
    }
    
    /**
     * 计算从from到to之间的提交数量
     */
    private fun countCommits(git: Git, from: ObjectId, to: ObjectId): Int {
        return try {
            val walk = RevWalk(git.repository)
            walk.markStart(walk.parseCommit(from))
            walk.markUninteresting(walk.parseCommit(to))
            
            var count = 0
            for (commit in walk) {
                count++
            }
            walk.close()
            count
        } catch (e: Exception) {
            LogManager.w(TAG, "countCommits 异常", e)
            0
        }
    }

    /**
     * 获取所有分支列表
     */
    suspend fun getBranches(path: String): List<BranchInfo> = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val currentBranch = git.repository.branch
                val branches = mutableListOf<BranchInfo>()
                
                // 本地分支
                git.branchList().call().forEach { ref ->
                    val name = ref.name.substringAfter("refs/heads/")
                    branches.add(
                        BranchInfo(
                            name = name,
                            isCurrent = name == currentBranch,
                            isRemote = false
                        )
                    )
                }
                
                // 远程分支
                git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call().forEach { ref ->
                    val name = ref.name.substringAfter("refs/remotes/origin/")
                    branches.add(
                        BranchInfo(
                            name = name,
                            isCurrent = false,
                            isRemote = true
                        )
                    )
                }
                
                branches
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "getBranches 异常", e)
            emptyList()
        }
    }

    /**
     * 切换分支
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun checkoutBranch(path: String, branchName: String, createIfNotExists: Boolean = false): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.checkout()
                    .setName(branchName)
                    .setCreateBranch(createIfNotExists)
                    .call()
                LogManager.i(TAG, "checkout 成功: $branchName")
                GitResult(true, listOf("已切换到分支: $branchName"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "checkout 失败", e)
            GitResult(false, error = e.message ?: "checkout 失败")
        }
    }

    /**
     * 创建新分支
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun createBranch(path: String, branchName: String): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.branchCreate()
                    .setName(branchName)
                    .call()
                LogManager.i(TAG, "create branch 成功: $branchName")
                GitResult(true, listOf("已创建分支: $branchName"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "create branch 失败", e)
            GitResult(false, error = e.message ?: "create branch 失败")
        }
    }

    /**
     * 删除分支
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun deleteBranch(path: String, branchName: String): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.branchDelete()
                    .setBranchNames(branchName)
                    .setForce(false)
                    .call()
                LogManager.i(TAG, "delete branch 成功: $branchName")
                GitResult(true, listOf("已删除分支: $branchName"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "delete branch 失败", e)
            GitResult(false, error = e.message ?: "delete branch 失败")
        }
    }

    /**
     * 重命名分支
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun renameBranch(path: String, oldName: String, newName: String): GitResult = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                git.branchRename()
                    .setOldName(oldName)
                    .setNewName(newName)
                    .call()
                LogManager.i(TAG, "rename branch 成功: $oldName -> $newName")
                GitResult(true, listOf("已重命名分支: $oldName -> $newName"))
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "rename branch 失败", e)
            GitResult(false, error = e.message ?: "rename branch 失败")
        }
    }

    /**
     * 获取工作目录与HEAD之间的diff
     * 参考JGit官方文档：https://wiki.eclipse.org/JGit/User_Guide
     */
    suspend fun getDiff(path: String, filePath: String? = null): String = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val outputStream = ByteArrayOutputStream()
                
                git.repository.newObjectReader().use { reader ->
                    DiffFormatter(outputStream).use { formatter ->
                        formatter.setRepository(git.repository)
                        formatter.setDiffComparator(RawTextComparator.DEFAULT)
                        formatter.setDetectRenames(true)
                        
                        val head = git.repository.resolve("HEAD")
                        if (head != null) {
                            val oldTree = CanonicalTreeParser(null, reader, git.repository.parseCommit(head).tree)
                            val newTree = CanonicalTreeParser()
                            
                            val diffs = if (filePath != null) {
                                git.diff()
                                    .setOldTree(oldTree)
                                    .setNewTree(null)
                                    .setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath))
                                    .call()
                            } else {
                                git.diff()
                                    .setOldTree(oldTree)
                                    .setNewTree(null)
                                    .call()
                            }
                            
                            diffs.forEach { diff ->
                                formatter.format(diff)
                            }
                        } else {
                            // 初始提交，没有HEAD
                            val status = git.status().call()
                            outputStream.write("Initial commit - no changes to diff yet\n".toByteArray())
                        }
                    }
                }
                
                outputStream.toString("UTF-8")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "getDiff 失败", e)
            "Error: ${e.message}"
        }
    }

    // ─── 获取变动文件信息 ───────────────────────────────────────────────────

    /**
     * 获取仓库的变动文件状态
     * @return 变动文件数量，如果没有变动则返回 null
     */
    suspend fun getChangedFilesCount(path: String): Int? = withContext(Dispatchers.IO) {
        try {
            openGit(path).use { git ->
                val status = git.status().call()
                val changedCount = status.modified.size + status.added.size + status.removed.size + status.changed.size
                if (changedCount > 0) changedCount else null
            }
        } catch (e: Exception) { 
            LogManager.w(TAG, "getChangedFilesCount 异常", e)
            null 
        }
    }

    // ─── 兼容性 run() 接口（供 LocalRepoViewModel 调用）──────────────────

    /**
     * 通用命令兼容层，将 git 命令行风格的参数映射到 JGit API
     * 支持：remote set-url / remote add / reset --soft/mixed/hard
     */
    suspend fun run(path: String, vararg args: String, useRoot: Boolean = false): GitResult =
        withContext(Dispatchers.IO) {
            try {
                when {
                    // git remote set-url origin <url>
                    args.size >= 4
                    && args[0] == "remote" && args[1] == "set-url" && args[2] == "origin" ->
                        setRemoteUrlCmd(path, args[3])

                    // git remote add origin <url>
                    args.size >= 4
                    && args[0] == "remote" && args[1] == "add" && args[2] == "origin" ->
                        addRemoteCmd(path, args[3])

                    // git reset --soft/mixed/hard <sha>
                    args.size >= 3 && args[0] == "reset" -> {
                        val mode = args[1].trimStart('-')
                        val sha  = args[2]
                        reset(path, sha, mode)
                    }

                    else -> {
                        LogManager.w(TAG, "run() 未支持的命令: ${args.toList()}")
                        GitResult(false, error = "不支持的 git 命令: ${args.toList()}")
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "run() 异常", e)
                GitResult(false, error = e.message ?: "执行失败")
            }
        }

    // ─── 私有辅助 ─────────────────────────────────────────────────────────

    private fun openGit(path: String): Git {
        val gitDir = File(path, ".git")
        val repo = RepositoryBuilder()
            .setGitDir(if (gitDir.exists()) gitDir else null)
            .setWorkTree(File(path))
            .setMustExist(false)
            .build()
        return Git(repo)
    }

    private fun setRemoteUrl(git: Git, url: String) {
        val config = git.repository.config
        config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin",
            ConfigConstants.CONFIG_KEY_URL, url)
        config.save()
    }

    private suspend fun setRemoteUrlCmd(path: String, url: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                openGit(path).use { git -> setRemoteUrl(git, url) }
                GitResult(true, listOf("remote.origin.url=$url"))
            } catch (e: Exception) {
                GitResult(false, error = e.message ?: "set-url 失败")
            }
        }

    private suspend fun addRemoteCmd(path: String, url: String): GitResult =
        withContext(Dispatchers.IO) {
            try {
                openGit(path).use { git ->
                    val config = git.repository.config
                    config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin",
                        ConfigConstants.CONFIG_KEY_URL, url)
                    config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, "origin",
                        "fetch", "+refs/heads/*:refs/remotes/origin/*")
                    config.save()
                }
                GitResult(true, listOf("已添加 remote origin: $url"))
            } catch (e: Exception) {
                GitResult(false, error = e.message ?: "remote add 失败")
            }
        }

    private fun tokenCredentials(token: String) =
        UsernamePasswordCredentialsProvider(token, "")

    /** 将 JGit 异常转换为用户友好的错误消息 */
    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        val cleanedMsg = sanitizeUrl(msg)
        return when {
            cleanedMsg.contains("not authorized", ignoreCase = true) ||
            cleanedMsg.contains("Authentication", ignoreCase = true) ->
                "认证失败：请检查 GitHub Token 是否有效"
            cleanedMsg.contains("Repository not found", ignoreCase = true) ->
                "仓库不存在或无权限访问"
            cleanedMsg.contains("Connection refused", ignoreCase = true) ||
            cleanedMsg.contains("UnknownHost", ignoreCase = true) ||
            cleanedMsg.contains("connection failed", ignoreCase = true) ||
            cleanedMsg.contains("cannot open git-upload-pack", ignoreCase = true) ->
                "网络连接失败，请检查网络"
            cleanedMsg.contains("rejected", ignoreCase = true) ->
                "推送被拒绝（远程有新提交，请先 pull）"
            else -> cleanedMsg.take(200)
        }
    }
    
    /** 清理URL中的敏感信息 */
    private fun sanitizeUrl(url: String): String {
        var result = url
        result = result.replace(Regex("https?://[^@]+@"), "https://***@")
        result = result.replace(Regex("http?://[^@]+@"), "http://***@")
        result = result.replace(Regex("ghp_[a-zA-Z0-9]+"), "ghp_***")
        result = result.replace(Regex("github_pat_[a-zA-Z0-9_]+"), "github_pat_***")
        result = result.replace(Regex("gho_[a-zA-Z0-9]+"), "gho_***")
        return result
    }
}

// ─── JGit 进度监听（输出到日志）────────────────────────────────────────────

private class LogProgressMonitor : org.eclipse.jgit.lib.ProgressMonitor {
    private val TAG = "JGitProgress"
    override fun start(totalTasks: Int) {}
    override fun beginTask(title: String, totalWork: Int) {
        LogManager.d(TAG, "▶ $title")
    }
    override fun update(completed: Int) {}
    override fun endTask() {}
    override fun isCancelled() = false
    override fun showDuration(enabled: Boolean) {}
}
