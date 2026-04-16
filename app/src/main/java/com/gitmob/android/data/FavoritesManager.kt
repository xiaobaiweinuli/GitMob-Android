package com.gitmob.android.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitmob.android.api.GHRepo
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.util.LogManager
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
    val id: Long,
    val fullName: String,
    val name: String,
    val ownerLogin: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val isPrivate: Boolean,
    val archived: Boolean,
    val htmlUrl: String,
    val website: String?,
    val topics: List<String>,
    val groupId: String?,        // null = 未分组
)

data class FavoritesState(
    val groups: List<FavGroup> = emptyList(),
    val ungroupedRepos: List<FavRepo> = emptyList(),
    /** fullName → FavRepo，包含所有收藏（ungrouped + 各分组内） */
    val allRepos: Map<String, FavRepo> = emptyMap(),
    /** id → FavRepo，用于通过仓库 id 快速查找 */
    val allReposById: Map<Long, FavRepo> = emptyMap(),
)

// ─── JSON 序列化 ──────────────────────────────────────────────────────────────

private fun FavRepo.toJson() = JSONObject().apply {
    put("id", id); put("fullName", fullName); put("name", name); put("ownerLogin", ownerLogin)
    put("description", description ?: ""); put("language", language ?: "")
    put("stars", stars); put("isPrivate", isPrivate); put("archived", archived); put("htmlUrl", htmlUrl)
    put("website", website ?: "")
    put("topics", JSONArray(topics))
    put("groupId", groupId ?: "")
}

private fun FavGroup.toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("description", description)
    put("repoIds", JSONArray(repoIds))
}

private fun JSONObject.toFavRepo() = FavRepo(
    id          = optLong("id", 0L), // 向后兼容：旧数据没有 id 时默认为 0
    fullName    = optString("fullName"),
    name        = optString("name"),
    ownerLogin  = optString("ownerLogin"),
    description = optString("description").ifBlank { null },
    language    = optString("language").ifBlank { null },
    stars       = optInt("stars"),
    isPrivate   = optBoolean("isPrivate"),
    archived    = optBoolean("archived"),
    htmlUrl     = optString("htmlUrl"),
    website     = optString("website").ifBlank { null },
    topics      = (0 until (optJSONArray("topics")?.length() ?: 0)).map {
        optJSONArray("topics")!!.optString(it)
    },
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
    id          = id,
    fullName    = fullName,
    name        = name,
    ownerLogin  = owner.login,
    description = description,
    language    = language,
    stars       = stars,
    isPrivate   = private,
    archived    = archived,
    htmlUrl     = htmlUrl,
    website     = homepage,
    topics      = topics,
    groupId     = groupId,
)

// ─── Manager ─────────────────────────────────────────────────────────────────

// ─── 导入导出相关类型 ─────────────────────────────────────────────────────────

enum class ImportConflictType {
    GROUP_NAME,    // 分组名称冲突
    REPO_EXISTS,   // 仓库已存在
}

data class ImportConflict(
    val type: ImportConflictType,
    val groupName: String? = null,       // 分组冲突时的名称
    val repoFullName: String? = null,    // 仓库冲突时的 fullName
    val existingGroupId: String? = null, // 已存在分组的 ID
    val newGroupId: String? = null,      // 新分组的 ID
)

enum class ConflictResolution {
    SKIP,        // 跳过
    OVERWRITE,   // 覆盖
    MERGE,       // 合并（仅分组合并适用）
    RENAME,      // 重命名（仅分组适用）
}

data class ImportResult(
    val success: Boolean,
    val importedGroups: Int = 0,
    val importedRepos: Int = 0,
    val conflicts: List<ImportConflict> = emptyList(),
    val errorMessage: String? = null,
)

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
        val allReposArr  = root.optJSONArray("allRepos")  ?: JSONArray()

        val groups    = (0 until groupsArr.length()).map { groupsArr.getJSONObject(it).toFavGroup() }
        val ungrouped = (0 until ungroupedArr.length()).map { ungroupedArr.getJSONObject(it).toFavRepo() }

        val allReposList = if (allReposArr.length() > 0) {
            (0 until allReposArr.length()).map { allReposArr.getJSONObject(it).toFavRepo() }
        } else {
            ungrouped
        }
        val allReposMap = allReposList.associateBy { it.fullName }
        val allReposByIdMap = allReposList.associateBy { it.id }

        FavoritesState(groups = groups, ungroupedRepos = ungrouped, allRepos = allReposMap, allReposById = allReposByIdMap)
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
        // 重新构建 allReposById
        val newAllReposById = newAllRepos.values.associateBy { it.id }
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped, allRepos = newAllRepos, allReposById = newAllReposById))
    }

    // ── 收藏操作 ─────────────────────────────────────────────────────────────────

    /** 新增收藏 */
    fun addFavorite(repo: GHRepo, groupId: String?) {
        val s = _state.value
        val favRepo = repo.toFavRepo(groupId)
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
        val newAllReposById = newAllRepos.values.associateBy { it.id }
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped, allRepos = newAllRepos, allReposById = newAllReposById))
    }

    /**
     * 进入收藏仓库详情时自动调用：同步最新的 stars/language/description 等数据。
     * 不改变分组，仅更新可变字段。
     */
    fun updateFavRepoData(repo: GHRepo) {
        val s = _state.value
        
        val old = s.allReposById[repo.id] ?: return
        
        val updated = old.copy(
            id          = repo.id,
            name        = repo.name,
            fullName    = repo.fullName,
            description = repo.description,
            language    = repo.language,
            stars       = repo.stars,
            isPrivate   = repo.private,
            archived    = repo.archived,
            htmlUrl     = repo.htmlUrl,
            website     = repo.homepage,
            topics      = repo.topics,
        )
        
        if (updated == old) {
            return
        }
        
        val finalAllRepos: MutableMap<String, FavRepo>
        val finalAllReposById: MutableMap<Long, FavRepo>
        val finalUngrouped: List<FavRepo>
        val finalGroups: List<FavGroup>
        
        if (old.fullName != repo.fullName) {
            finalAllRepos = s.allRepos.filterKeys { it != old.fullName }.toMutableMap()
            finalAllRepos[repo.fullName] = updated
            finalAllReposById = s.allReposById.toMutableMap()
            finalAllReposById[repo.id] = updated
            finalUngrouped = s.ungroupedRepos.map { 
                if (it.id == repo.id) updated else it 
            }
            finalGroups = s.groups.map { group ->
                val newRepoIds = group.repoIds.map { id ->
                    if (id == old.fullName) repo.fullName else id
                }
                group.copy(repoIds = newRepoIds)
            }
        } else {
            finalAllRepos = (s.allRepos + (repo.fullName to updated)).toMutableMap()
            finalAllReposById = (s.allReposById + (repo.id to updated)).toMutableMap()
            finalUngrouped = s.ungroupedRepos.map { if (it.id == repo.id) updated else it }
            finalGroups = s.groups
        }
        
        save(FavoritesState(groups = finalGroups, ungroupedRepos = finalUngrouped, allRepos = finalAllRepos, allReposById = finalAllReposById))
    }

    /** 移出收藏 */
    fun removeFavorite(fullName: String) {
        val s = _state.value
        val newUngrouped = s.ungroupedRepos.filter { it.fullName != fullName }
        val newGroups    = s.groups.map { g ->
            g.copy(repoIds = g.repoIds.filter { it != fullName })
        }
        val newAllRepos = s.allRepos - fullName
        val newAllReposById = newAllRepos.values.associateBy { it.id }
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped,
            allRepos = newAllRepos, allReposById = newAllReposById))
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
        val newAllRepos = s.allRepos + (fullName to updatedRepo)
        val newAllReposById = newAllRepos.values.associateBy { it.id }
        save(FavoritesState(groups = newGroups, ungroupedRepos = newUngrouped, allRepos = newAllRepos, allReposById = newAllReposById))
    }

    // ─── 导出导入功能 ─────────────────────────────────────────────────────────────────

    /**
     * 导出收藏夹数据为 JSON 字符串
     */
    fun exportFavorites(): String {
        return serialize(_state.value)
    }

    /**
     * 解析导入数据并检测冲突
     */
    fun analyzeImport(jsonString: String): ImportResult {
        return try {
            val importedState = parse(jsonString)
            val existingState = _state.value

            val conflicts = mutableListOf<ImportConflict>()

            // 检测分组名称冲突
            val existingGroupNames = existingState.groups.associateBy { it.name }
            importedState.groups.forEach { newGroup ->
                if (existingGroupNames.containsKey(newGroup.name)) {
                    conflicts.add(ImportConflict(
                        type = ImportConflictType.GROUP_NAME,
                        groupName = newGroup.name,
                        existingGroupId = existingGroupNames[newGroup.name]?.id,
                        newGroupId = newGroup.id
                    ))
                }
            }

            // 检测仓库冲突
            importedState.allRepos.keys.forEach { fullName ->
                if (existingState.allRepos.containsKey(fullName)) {
                    conflicts.add(ImportConflict(
                        type = ImportConflictType.REPO_EXISTS,
                        repoFullName = fullName
                    ))
                }
            }

            ImportResult(
                success = true,
                importedGroups = importedState.groups.size,
                importedRepos = importedState.allRepos.size,
                conflicts = conflicts
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                errorMessage = e.message ?: "解析失败"
            )
        }
    }

    /**
     * 执行导入
     * @param jsonString JSON 字符串
     * @param resolutions 冲突解决方案 map (冲突索引 -> 解决方案)
     */
    fun performImport(
        jsonString: String,
        resolutions: Map<Int, ConflictResolution> = emptyMap()
    ): ImportResult {
        return try {
            val importedState = parse(jsonString)
            val existingState = _state.value

            val conflicts = mutableListOf<ImportConflict>()
            val existingGroupNames = existingState.groups.associateBy { it.name }

            // 先检测所有冲突
            importedState.groups.forEach { newGroup ->
                if (existingGroupNames.containsKey(newGroup.name)) {
                    conflicts.add(ImportConflict(
                        type = ImportConflictType.GROUP_NAME,
                        groupName = newGroup.name,
                        existingGroupId = existingGroupNames[newGroup.name]?.id,
                        newGroupId = newGroup.id
                    ))
                }
            }

            importedState.allRepos.keys.forEach { fullName ->
                if (existingState.allRepos.containsKey(fullName)) {
                    conflicts.add(ImportConflict(
                        type = ImportConflictType.REPO_EXISTS,
                        repoFullName = fullName
                    ))
                }
            }

            // 处理分组
            val finalGroups = existingState.groups.toMutableList()
            val groupIdMapping = mutableMapOf<String, String>() // 新ID -> 最终ID

            importedState.groups.forEachIndexed { idx, newGroup ->
                val conflictIdx = conflicts.indexOfFirst {
                    it.type == ImportConflictType.GROUP_NAME && it.newGroupId == newGroup.id
                }

                if (conflictIdx >= 0) {
                    val resolution = resolutions[conflictIdx] ?: ConflictResolution.SKIP
                    when (resolution) {
                        ConflictResolution.SKIP -> {
                            // 跳过，不处理
                        }
                        ConflictResolution.OVERWRITE -> {
                            // 替换：删除旧分组，添加新分组
                            finalGroups.removeIf { it.id == existingGroupNames[newGroup.name]?.id }
                            finalGroups.add(newGroup)
                            groupIdMapping[newGroup.id] = newGroup.id
                        }
                        ConflictResolution.MERGE -> {
                            // 合并：保留旧分组 ID，合并仓库列表
                            val existingGroup = existingGroupNames[newGroup.name]
                            if (existingGroup != null) {
                                val mergedRepoIds = (existingGroup.repoIds + newGroup.repoIds).distinct()
                                val mergedGroup = existingGroup.copy(repoIds = mergedRepoIds)
                                finalGroups.removeIf { it.id == existingGroup.id }
                                finalGroups.add(mergedGroup)
                                groupIdMapping[newGroup.id] = existingGroup.id
                            }
                        }
                        ConflictResolution.RENAME -> {
                            // 重命名：添加数字后缀
                            var newName = newGroup.name
                            var counter = 1
                            while (finalGroups.any { it.name == newName }) {
                                newName = "${newGroup.name} ($counter)"
                                counter++
                            }
                            val renamedGroup = newGroup.copy(name = newName)
                            finalGroups.add(renamedGroup)
                            groupIdMapping[newGroup.id] = renamedGroup.id
                        }
                    }
                } else {
                    // 无冲突，直接添加
                    finalGroups.add(newGroup)
                    groupIdMapping[newGroup.id] = newGroup.id
                }
            }

            // 处理仓库
            val finalAllRepos = existingState.allRepos.toMutableMap()
            val finalUngrouped = existingState.ungroupedRepos.toMutableList()

            importedState.allRepos.values.forEach { importedRepo ->
                val conflictIdx = conflicts.indexOfFirst {
                    it.type == ImportConflictType.REPO_EXISTS && it.repoFullName == importedRepo.fullName
                }

                if (conflictIdx >= 0) {
                    val resolution = resolutions[conflictIdx] ?: ConflictResolution.SKIP
                    when (resolution) {
                        ConflictResolution.SKIP -> {
                            // 跳过
                        }
                        ConflictResolution.OVERWRITE -> {
                            // 覆盖
                            val finalGroupId = importedRepo.groupId?.let { groupIdMapping[it] ?: it }
                            val updatedRepo = importedRepo.copy(groupId = finalGroupId)
                            finalAllRepos[importedRepo.fullName] = updatedRepo

                            // 更新未分组列表
                            if (finalGroupId == null) {
                                finalUngrouped.removeIf { it.fullName == importedRepo.fullName }
                                finalUngrouped.add(updatedRepo)
                            } else {
                                finalUngrouped.removeIf { it.fullName == importedRepo.fullName }
                            }
                        }
                        else -> {
                            // 其他操作对于仓库只支持 SKIP 和 OVERWRITE
                        }
                    }
                } else {
                    // 无冲突，直接添加
                    val finalGroupId = importedRepo.groupId?.let { groupIdMapping[it] ?: it }
                    val newRepo = importedRepo.copy(groupId = finalGroupId)
                    finalAllRepos[importedRepo.fullName] = newRepo

                    if (finalGroupId == null) {
                        finalUngrouped.add(newRepo)
                    }
                }
            }

            // 清理未分组列表中已在分组内的仓库
            val groupedRepoIds = finalGroups.flatMap { it.repoIds }.toSet()
            val cleanedUngrouped = finalUngrouped.filter { it.fullName !in groupedRepoIds }

            // 保存最终状态
            val finalState = FavoritesState(
                groups = finalGroups,
                ungroupedRepos = cleanedUngrouped,
                allRepos = finalAllRepos
            )
            save(finalState)

            ImportResult(
                success = true,
                importedGroups = finalGroups.size - existingState.groups.size,
                importedRepos = finalAllRepos.size - existingState.allRepos.size,
                conflicts = conflicts
            )
        } catch (e: Exception) {
            ImportResult(
                success = false,
                errorMessage = e.message ?: "导入失败"
            )
        }
    }
}
