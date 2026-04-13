package com.gitmob.android.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.GHRepo
import com.gitmob.android.auth.TokenStorage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ─── 数据模型 ─────────────────────────────────────────────────────────────────

data class FavGroup(
    val id: String,
    val name: String,
    val description: String,
    val repoIds: List<String>,   // fullName 列表
)

data class FavRepo(
    val fullName: String,
    val name: String,
    val ownerLogin: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val isPrivate: Boolean,
    val htmlUrl: String,
    val groupId: String?,        // null = 未分组
)

data class FavoritesState(
    val groups: List<FavGroup> = emptyList(),
    val ungroupedRepos: List<FavRepo> = emptyList(),
    /** fullName → FavRepo，包含所有收藏（ungrouped + 各分组内） */
    val allRepos: Map<String, FavRepo> = emptyMap(),
)

// ─── JSON 序列化 ──────────────────────────────────────────────────────────────

private fun FavRepo.toJson() = JSONObject().apply {
    put("fullName", fullName); put("name", name); put("ownerLogin", ownerLogin)
    put("description", description ?: ""); put("language", language ?: "")
    put("stars", stars); put("isPrivate", isPrivate); put("htmlUrl", htmlUrl)
    put("groupId", groupId ?: "")
}

private fun FavGroup.toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("description", description)
    put("repoIds", JSONArray(repoIds))
}

private fun JSONObject.toFavRepo() = FavRepo(
    fullName    = optString("fullName"),
    name        = optString("name"),
    ownerLogin  = optString("ownerLogin"),
    description = optString("description").ifBlank { null },
    language    = optString("language").ifBlank { null },
    stars       = optInt("stars"),
    isPrivate   = optBoolean("isPrivate"),
    htmlUrl     = optString("htmlUrl"),
    groupId     = optString("groupId").ifBlank { null },
)

private fun JSONObject.toFavGroup() = FavGroup(
    id          = optString("id"),
    name        = optString("name"),
    description = optString("description"),
    repoIds     = (0 until (optJSONArray("repoIds")?.length() ?: 0)).map {
        optJSONArray("repoIds")!!.optString(it)
    },
)

private fun GHRepo.toFavRepo(groupId: String?) = FavRepo(
    fullName    = fullName,
    name        = name,
    ownerLogin  = owner.login,
    description = description,
    language    = language,
    stars       = stars,
    isPrivate   = private,
    htmlUrl     = htmlUrl,
    groupId     = groupId,
)

// ─── Manager ─────────────────────────────────────────────────────────────────

class FavoritesManager(app: Application) : AndroidViewModel(app) {

    private val tokenStorage = TokenStorage(app)
    private val _state = MutableStateFlow(FavoritesState())
    val state = _state.asStateFlow()
    private var currentLogin: String = ""

    fun init(login: String) {
        if (login == currentLogin) return
        currentLogin = login
        viewModelScope.launch {
            tokenStorage.favoritesJsonFlow(login).collect { raw ->
                _state.value = parse(raw)
            }
        }
    }

    // ── JSON 解析（修复：allRepos 同时包含 ungrouped + 各分组内仓库）────────────

    private fun parse(raw: String): FavoritesState = try {
        val root        = JSONObject(raw)
        val groupsArr   = root.optJSONArray("groups")   ?: JSONArray()
        val ungroupedArr = root.optJSONArray("ungrouped") ?: JSONArray()
        // "allRepos" 保存所有收藏的完整 FavRepo 数据
        val allReposArr  = root.optJSONArray("allRepos")  ?: JSONArray()

        val groups    = (0 until groupsArr.length()).map { groupsArr.getJSONObject(it).toFavGroup() }
        val ungrouped = (0 until ungroupedArr.length()).map { ungroupedArr.getJSONObject(it).toFavRepo() }

        // 优先用 allRepos 数组，降级时从 ungrouped 恢复（向后兼容旧数据）
        val allReposMap: Map<String, FavRepo> = if (allReposArr.length() > 0) {
            (0 until allReposArr.length()).map { allReposArr.getJSONObject(it).toFavRepo() }
                .associateBy { it.fullName }
        } else {
            ungrouped.associateBy { it.fullName }
        }

        FavoritesState(groups = groups, ungroupedRepos = ungrouped, allRepos = allReposMap)
    } catch (_: Exception) { FavoritesState() }

    private fun serialize(s: FavoritesState): String {
        val root        = JSONObject()
        val groupsArr   = JSONArray().also { arr -> s.groups.forEach { arr.put(it.toJson()) } }
        val ungroupedArr = JSONArray().also { arr -> s.ungroupedRepos.forEach { arr.put(it.toJson()) } }
        // 将所有收藏（含分组内）序列化到 allRepos 数组
        val allReposArr  = JSONArray().also { arr -> s.allRepos.values.forEach { arr.put(it.toJson()) } }
        root.put("groups", groupsArr)
        root.put("ungrouped", ungroupedArr)
        root.put("allRepos", allReposArr)
        return root.toString()
    }

    private fun save(newState: FavoritesState) {
        _state.value = newState
        viewModelScope.launch { tokenStorage.saveFavoritesJson(currentLogin, serialize(newState)) }
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────────

    fun isFavorited(fullName: String) = _state.value.allRepos.containsKey(fullName)

    fun getRepoGroup(fullName: String) = _state.value.allRepos[fullName]?.groupId

    fun getReposInGroup(groupId: String?): List<FavRepo> =
        if (groupId == null) _state.value.ungroupedRepos
        else {
            val group = _state.value.groups.firstOrNull { it.id == groupId } ?: return emptyList()
            // 直接从 allRepos 查找，不再依赖 ungrouped
            group.repoIds.mapNotNull { _state.value.allRepos[it] }
        }

    // ── 分组操作 ─────────────────────────────────────────────────────────────────

    fun addGroup(name: String, description: String): String {
        val id = java.util.UUID.randomUUID().toString()
        save(_state.value.copy(groups = _state.value.groups + FavGroup(id, name, description, emptyList())))
        return id
    }

    /** 删除分组。deleteMode = "group_only" | "all" */
    fun deleteGroup(groupId: String, deleteMode: String) {
        val s     = _state.value
        val group = s.groups.firstOrNull { it.id == groupId } ?: return
        val newGroups = s.groups.filter { it.id != groupId }
        val (newUngrouped, newAllRepos) = if (deleteMode == "all") {
            val ungroup = s.ungroupedRepos
            val all     = s.allRepos.filter { it.key !in group.repoIds }
            ungroup to all
        } else {
            // 移到未分组
            val toMove    = group.repoIds.mapNotNull { s.allRepos[it]?.copy(groupId = null) }
            val ungroup   = (s.ungroupedRepos + toMove).distinctBy { it.fullName }
            val movedMap  = toMove.associateBy { it.fullName }
            val all       = s.allRepos + movedMap
            ungroup to all
        }
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped, allRepos = newAllRepos))
    }

    // ── 收藏操作 ─────────────────────────────────────────────────────────────────

    /** 新增收藏 */
    fun addFavorite(repo: GHRepo, groupId: String?) {
        val s = _state.value
        val favRepo = repo.toFavRepo(groupId)
        // 若已收藏到其他分组，先从旧位置移除
        val cleanedGroups = s.groups.map { g ->
            g.copy(repoIds = g.repoIds.filter { it != repo.fullName })
        }
        val cleanedUngrouped = s.ungroupedRepos.filter { it.fullName != repo.fullName }

        val newUngrouped = if (groupId == null)
            (listOf(favRepo) + cleanedUngrouped).distinctBy { it.fullName }
        else cleanedUngrouped

        val newGroups = if (groupId != null) {
            cleanedGroups.map { g ->
                if (g.id == groupId) g.copy(repoIds = (g.repoIds + repo.fullName).distinct())
                else g
            }
        } else cleanedGroups

        val newAllRepos = s.allRepos + (repo.fullName to favRepo)
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped, allRepos = newAllRepos))
    }

    /**
     * 进入收藏仓库详情时自动调用：同步最新的 stars/language/description 等数据。
     * 不改变分组，仅更新可变字段。
     */
    fun updateFavRepoData(repo: GHRepo) {
        val s = _state.value
        val old = s.allRepos[repo.fullName] ?: return
        val updated = old.copy(
            description = repo.description,
            language    = repo.language,
            stars       = repo.stars,
            isPrivate   = repo.private,
            htmlUrl     = repo.htmlUrl,
        )
        if (updated == old) return  // 无变化不触发写入
        val newUngrouped = s.ungroupedRepos.map { if (it.fullName == repo.fullName) updated else it }
        val newAllRepos  = s.allRepos + (repo.fullName to updated)
        save(FavoritesState(groups = s.groups, ungroupedRepos = newUngrouped, allRepos = newAllRepos))
    }

    /** 移出收藏 */
    fun removeFavorite(fullName: String) {
        val s = _state.value
        val newUngrouped = s.ungroupedRepos.filter { it.fullName != fullName }
        val newGroups    = s.groups.map { g ->
            g.copy(repoIds = g.repoIds.filter { it != fullName })
        }
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped,
            allRepos = s.allRepos - fullName))
    }

    /** 修改分组（移动到其他分组或未分组） */
    fun moveFavorite(fullName: String, newGroupId: String?) {
        val s    = _state.value
        val repo = s.allRepos[fullName] ?: return
        // 从旧位置移除
        val cleanedGroups    = s.groups.map { g -> g.copy(repoIds = g.repoIds.filter { it != fullName }) }
        val cleanedUngrouped = s.ungroupedRepos.filter { it.fullName != fullName }
        // 放入新位置
        val updatedRepo  = repo.copy(groupId = newGroupId)
        val newUngrouped = if (newGroupId == null)
            (listOf(updatedRepo) + cleanedUngrouped).distinctBy { it.fullName }
        else cleanedUngrouped
        val newGroups = if (newGroupId != null) {
            cleanedGroups.map { g ->
                if (g.id == newGroupId) g.copy(repoIds = (g.repoIds + fullName).distinct())
                else g
            }
        } else cleanedGroups
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped,
            allRepos = s.allRepos + (fullName to updatedRepo)))
    }
}
