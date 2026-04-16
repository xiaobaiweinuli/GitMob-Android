package com.gitmob.android.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.ui.filepicker.BookmarkPath
import com.gitmob.android.local.GitRunner
import com.gitmob.android.local.LocalRepo
import com.gitmob.android.local.LocalRepoStatus
import com.gitmob.android.local.LocalRepoStorage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

data class LocalRepoListState(
    val repos: List<LocalRepo> = emptyList(),
    val loading: Boolean = false,
    val toast: String? = null,
    val showFilePicker: Boolean = false,
    val showClonePicker: Boolean = false,   // 为克隆操作选择目标目录
    val pendingCloneUrl: String = "",       // 待克隆的 GitHub repo url
    // 每次关闭 picker 时递增，使 SaveableStateProvider key 变化，
    // 下次打开时 rememberSaveable 状态重置为初始值（初始目录）
    val filePickerSessionId: Int = 0,
    val clonePickerSessionId: Int = 0,
)

class LocalRepoViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = LocalRepoStorage(app)
    private val gson = com.google.gson.Gson()
    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(LocalRepoListState())
    val state = _state.asStateFlow()

    private var token: String = ""

    // 自定义书签（持久化在 DataStore）
    // JsonParser 逐元素解析书签列表，不依赖泛型签名，R8 安全
    val customBookmarks: kotlinx.coroutines.flow.StateFlow<List<BookmarkPath>> =
        tokenStorage.bookmarksJson.map { json ->
            try {
                if (json.isNullOrBlank()) emptyList()
                else {
                    val array = com.google.gson.JsonParser.parseString(json).asJsonArray
                    array.mapNotNull { element ->
                        try { gson.fromJson(element, BookmarkPath::class.java) }
                        catch (_: Exception) { null }
                    }
                }
            } catch (_: Exception) { emptyList() }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            storage.repos.collect { repos ->
                _state.update { it.copy(repos = repos) }
            }
        }
        viewModelScope.launch {
            tokenStorage.accessToken.collect { t ->
                token = t ?: ""
            }
        }
    }

    /** 从文件选择器导入目录 */
    fun importDirectory(path: String) = viewModelScope.launch {
        try {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            toast("路径无效：$path"); return@launch
        }
        val isGit = GitRunner.isGitRepo(path)
        val status = if (isGit) LocalRepoStatus.GIT_INITIALIZED else LocalRepoStatus.PENDING_INIT
        val repo = LocalRepo(
            id = storage.newId(), path = path, name = dir.name,
            status = status,
            currentBranch = if (isGit) GitRunner.currentBranch(path) else null,
            lastCommit = if (isGit) GitRunner.lastCommitMsg(path) else null,
            remoteUrl = if (isGit) GitRunner.remoteUrl(path) else null,
        )
        storage.addOrUpdate(repo)
        toast("已导入：${dir.name}")
        _state.update { it.copy(showFilePicker = false) }
        } catch (e: Exception) { toast("导入失败：${e.message}") }
    }

    /** 新建本地项目（创建目录 + git init） */
    fun createLocalProject(path: String, name: String) = viewModelScope.launch {
        try {
        val dir = File(path, name)
        dir.mkdirs()
        val result = GitRunner.init(dir.absolutePath)
        val repo = LocalRepo(
            id = storage.newId(), path = dir.absolutePath, name = name,
            status = if (result.success) LocalRepoStatus.GIT_INITIALIZED else LocalRepoStatus.ERROR,
            currentBranch = "main",
            error = if (!result.success) result.error else null,
        )
        storage.addOrUpdate(repo)
        toast(if (result.success) "已创建：$name" else "初始化失败：${result.error}")
        } catch (e: Exception) { toast("创建失败：${e.message}") }
    }

    /** 克隆远程仓库到选定目录 */
    fun cloneRepo(url: String, targetDir: String) = viewModelScope.launch {
        toast("克隆中…")
        val repoName = url.substringAfterLast("/").removeSuffix(".git")
        val fullTarget = "$targetDir/$repoName"
        val result = GitRunner.clone(url, fullTarget, token)
        if (result.success) {
            val repo = LocalRepo(
                id = storage.newId(), path = fullTarget, name = repoName,
                status = LocalRepoStatus.GIT_INITIALIZED,
                remoteUrl = url,
                currentBranch = GitRunner.currentBranch(fullTarget),
            )
            storage.addOrUpdate(repo)
            toast("克隆成功：$repoName")
        } else {
            toast("克隆失败：${result.error.take(100)}")
        }
    }

    fun scanRepo(repoId: String) = viewModelScope.launch {
        val repo = _state.value.repos.find { it.id == repoId } ?: return@launch
        val isGit = GitRunner.isGitRepo(repo.path)
        // suspend 调用必须在协程体内完成，不能放进普通 lambda
        val branch    = if (isGit) GitRunner.currentBranch(repo.path) else null
        val lastMsg   = if (isGit) GitRunner.lastCommitMsg(repo.path) else null
        val remoteUrl = if (isGit) GitRunner.remoteUrl(repo.path) else null
        val changedFilesCount = if (isGit) GitRunner.getChangedFilesCount(repo.path) else null
        storage.update(repoId) {
            it.copy(
                status        = if (isGit) LocalRepoStatus.GIT_INITIALIZED else LocalRepoStatus.PENDING_INIT,
                currentBranch = branch,
                lastCommit    = lastMsg,
                remoteUrl     = remoteUrl,
                changedFilesCount = changedFilesCount,
            )
        }
    }

    fun removeRepo(repoId: String) = viewModelScope.launch {
        storage.remove(repoId)
        toast("已移除")
    }

    fun showFilePicker() = _state.update { it.copy(showFilePicker = true) }
    fun hideFilePicker() = _state.update { it.copy(showFilePicker = false, filePickerSessionId = it.filePickerSessionId + 1) }

    fun startClone(repoUrl: String) {
        _state.update { it.copy(showClonePicker = true, pendingCloneUrl = repoUrl) }
    }
    fun hideClonePicker() = _state.update { it.copy(showClonePicker = false, pendingCloneUrl = "", clonePickerSessionId = it.clonePickerSessionId + 1) }

    /** git reset --soft/mixed/hard <sha>（JGit 原生实现） */
    fun gitReset(repoId: String, sha: String, mode: String = "mixed") = viewModelScope.launch {
        val repo = _state.value.repos.find { it.id == repoId } ?: return@launch
        try {
            val result = GitRunner.reset(repo.path, sha, mode)
            if (result.success) {
                // 刷新状态
                val branch = GitRunner.currentBranch(repo.path)
                val last   = GitRunner.lastCommitMsg(repo.path)
                val updated = repo.copy(currentBranch = branch, lastCommit = last,
                    status = LocalRepoStatus.GIT_INITIALIZED)
                storage.addOrUpdate(updated)
                toast("✓ reset $mode 到 ${sha.take(7)}")
            } else {
                toast("reset 失败：${result.error.take(100)}")
            }
        } catch (e: Exception) {
            toast("reset 异常：${e.message}")
        }
    }
    fun clearToast() = _state.update { it.copy(toast = null) }

    fun addBookmark(bm: BookmarkPath) = viewModelScope.launch {
        val current = customBookmarks.value.toMutableList()
        if (current.none { it.path == bm.path }) {
            current.add(bm)
            tokenStorage.saveBookmarksJson(gson.toJson(current))
        }
    }

    fun removeBookmark(bm: BookmarkPath) = viewModelScope.launch {
        val updated = customBookmarks.value.filter { it.path != bm.path }
        tokenStorage.saveBookmarksJson(gson.toJson(updated))
    }
    private fun toast(msg: String) = _state.update { it.copy(toast = msg) }
}
