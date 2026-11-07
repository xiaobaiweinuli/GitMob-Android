package com.gitmob.app.data.local.library

import androidx.room.withTransaction
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.data.model.DEFAULT_FAVORITE_GROUP
import com.gitmob.app.data.model.FavoriteGroup
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.data.model.LibrarySettings
import com.gitmob.app.data.model.LibraryState
import com.gitmob.app.data.model.LibraryContent
import com.gitmob.app.data.model.HistoryLimitResult
import com.gitmob.app.data.model.HistoryItem
import com.gitmob.app.data.model.GistListItem
import com.gitmob.app.data.model.RepoCardData
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.R
import javax.inject.Singleton
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Singleton
class LibraryRepository @Inject constructor(
    private val database: LibraryDatabase,
    private val json: Json,
    private val session: AccountSession,
) {
    private val dao get() = database.libraryDao()

    fun observeSettings(account: AccountEpoch): Flow<LibrarySettings> = observeKey(account) { key ->
        dao.observeSettings(key).map { checkNotNull(it).toModel() }.distinctUntilChanged()
    }

    fun observeGroups(account: AccountEpoch): Flow<List<FavoriteGroup>> = observeKey(account) { key ->
        dao.observeGroups(key).distinctUntilChanged().map { rows -> rows.map { it.group.toModel(it.count) } }
    }

    fun observeHistoryCount(account: AccountEpoch): Flow<Int> = observeKey(account) { key ->
        dao.observeHistoryCount(key).distinctUntilChanged()
    }

    fun observe(account: AccountEpoch, content: LibraryContent): Flow<LibraryState> = observeKey(account) { key ->
        when (content) {
            LibraryContent.History -> dao.observeHistory(key).distinctUntilChanged().map { rows ->
                LibraryState(loaded = true, history = rows.map { row ->
                    when (row.kind) {
                        "repository" -> HistoryItem.Repository(row.id, json.decodeFromString<RepoCardData>(row.payload), row.visitedAt)
                        "gist" -> HistoryItem.Gist(row.id, json.decodeFromString<GistListItem>(row.payload), row.visitedAt)
                        else -> error("Unknown history kind")
                    }
                })
            }
            is LibraryContent.Group, is LibraryContent.Repository, is LibraryContent.Gist -> dao.observeFavorites(
                key, (content as? LibraryContent.Group)?.id,
                when (content) { is LibraryContent.Repository -> "repository"; is LibraryContent.Gist -> "gist"; else -> null },
                when (content) { is LibraryContent.Repository -> content.id; is LibraryContent.Gist -> content.id; else -> null },
            ).distinctUntilChanged().map { rows ->
                LibraryState(loaded = true, favorites = rows.map { row ->
                    when (row.kind) {
                        "repository" -> FavoriteItem.Repository(row.itemId, json.decodeFromString<RepoCardData>(row.payload), row.groupId)
                        "gist" -> FavoriteItem.Gist(json.decodeFromString<GistListItem>(row.payload), row.groupId)
                        else -> error("Unknown favorite kind")
                    }
                })
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observeKey(account: AccountEpoch, query: (String) -> Flow<T>): Flow<T> = flow {
        account.ensureCurrent()
        val key = accountKey(account)
        // A normal subscription does not write or read the entire account partition.
        if (dao.settings(key) == null && ensure(account) is ApiResult.Failure) {
            error("Failed to initialize repository library")
        }
        emitAll(dao.observeAccountKey(checkNotNull(account.accountKey)).distinctUntilChanged().flatMapLatest(query))
    }.map { account.ensureCurrent(); it }.flowOn(Dispatchers.Default)

    suspend fun settings(account: AccountEpoch): ApiResult<LibrarySettings> = ensure(account).map { it.toModel() }

    suspend fun setFavoritesEnabled(account: AccountEpoch, enabled: Boolean) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        dao.settings(checkNotNull(dao.settings(key)).copy(favoritesEnabled = enabled))
    }

    suspend fun setHistoryEnabled(account: AccountEpoch, enabled: Boolean) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        dao.settings(checkNotNull(dao.settings(key)).copy(historyEnabled = enabled))
    }

    suspend fun setHistoryLimit(account: AccountEpoch, limit: Int, confirmTrim: Boolean = false): ApiResult<HistoryLimitResult> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        if (limit !in 1..1000) throw UserVisibleException(R.string.library_limit_invalid)
        // Recheck under the same transaction as the write; UI counts may be stale or unavailable.
        val count = dao.historyCount(key)
        if (count > limit && !confirmTrim) return@write HistoryLimitResult.NeedsConfirmation(count)
        dao.settings(checkNotNull(dao.settings(key)).copy(historyLimit = limit))
        pruneHistory(key, limit)
        HistoryLimitResult.Saved
    }

    suspend fun clearHistory(account: AccountEpoch) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        dao.clearVisits(key); dao.clearGistVisits(key)
    }

    suspend fun createGroup(account: AccountEpoch, name: String): ApiResult<FavoriteGroup> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        val clean = name.trim(); if (clean.length !in 1..30) throw UserVisibleException(R.string.library_invalid_group)
        val partition = dao.partition(key)!!
        val normalized = clean.lowercase(java.util.Locale.ROOT)
        if (partition.groups.any { it.normalizedName == normalized }) throw UserVisibleException(R.string.library_duplicate_group)
        val group = FavoriteGroupEntity(key, UUID.randomUUID().toString(), clean, normalized,
            partition.groups.maxOfOrNull { it.sortOrder }?.plus(1) ?: 1)
        dao.group(group)
        group.toModel(0)
    }

    suspend fun renameGroup(account: AccountEpoch, id: String, name: String): ApiResult<Unit> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        require(id != DEFAULT_FAVORITE_GROUP)
        val clean = name.trim(); if (clean.length !in 1..30) throw UserVisibleException(R.string.library_invalid_group)
        val partition = dao.partition(key)!!
        require(partition.groups.any { it.id == id })
        if (partition.groups.any { it.id != id && it.normalizedName == clean.lowercase(java.util.Locale.ROOT) }) throw UserVisibleException(R.string.library_duplicate_group)
        dao.group(partition.groups.first { it.id == id }.copy(name = clean, normalizedName = clean.lowercase(java.util.Locale.ROOT)))
    }

    suspend fun deleteGroup(account: AccountEpoch, id: String, removeFavorites: Boolean = false): ApiResult<Unit> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        require(id != DEFAULT_FAVORITE_GROUP)
        val partition = dao.partition(key)!!
        require(partition.groups.any { it.id == id })
        partition.favorites.filter { it.groupId == id }.sortedBy { it.sortOrder }.forEachIndexed { index, item ->
            if (removeFavorites) dao.deleteFavorite(key, item.kind, item.itemId)
            else dao.favorite(item.copy(groupId = DEFAULT_FAVORITE_GROUP, sortOrder = nextOrder(partition, DEFAULT_FAVORITE_GROUP) + index))
        }
        dao.deleteGroup(key, id)
        normalizeGroups(dao.partition(key)!!)
    }

    suspend fun reorderGroups(account: AccountEpoch, orderedIds: List<String>) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        val partition = dao.partition(key)!!
        val expected = partition.groups.sortedBy { it.sortOrder }.map { it.id }
        require(orderedIds.size == expected.size && orderedIds.toSet() == expected.toSet() && orderedIds.firstOrNull() == DEFAULT_FAVORITE_GROUP)
        orderedIds.forEachIndexed { index, id -> dao.group(partition.groups.first { it.id == id }.copy(sortOrder = index)) }
    }

    suspend fun saveFavorite(account: AccountEpoch, item: FavoriteItem, groupId: String): ApiResult<Unit> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        val partition = dao.partition(key)!!
        require(partition.settings.favoritesEnabled)
        require(partition.groups.any { it.id == groupId })
        val old = partition.favorites.firstOrNull { it.key == item.key }
        dao.favorite(FavoriteEntity(key, item.kind, item.id, groupId,
            if (old?.groupId == groupId) old.sortOrder else nextOrder(partition, groupId),
            when (item) {
                is FavoriteItem.Repository -> json.encodeToString(item.card)
                is FavoriteItem.Gist -> json.encodeToString(item.gist)
            }))
        normalizeFavorites(dao.partition(key)!!, groupId)
    }

    suspend fun refreshFavorite(account: AccountEpoch, repositoryId: String, card: RepoCardData): ApiResult<Unit> = write(account) {
        dao.updateFavoritePayload(accountKey(account), "repository", repositoryId, json.encodeToString(card))
    }

    suspend fun refreshGistFavorite(account: AccountEpoch, gist: GistListItem): ApiResult<Unit> = write(account) {
        dao.updateFavoritePayload(accountKey(account), "gist", gist.apiName, json.encodeToString(gist))
    }

    suspend fun removeFavorite(account: AccountEpoch, item: FavoriteItem): ApiResult<Unit> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        dao.deleteFavorite(key, item.kind, item.id)
    }

    suspend fun reorderFavorites(account: AccountEpoch, groupId: String, orderedIds: List<String>) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        val partition = dao.partition(key)!!
        val current = partition.favorites.filter { it.groupId == groupId }.sortedBy { it.sortOrder }
        require(orderedIds.size == current.size && orderedIds.toSet() == current.map { it.key }.toSet())
        orderedIds.forEachIndexed { index, id -> dao.favorite(current.first { it.key == id }.copy(sortOrder = index)) }
    }

    suspend fun recordVisit(account: AccountEpoch, repositoryId: String, card: RepoCardData) = write(account) {
        val key = accountKey(account)
        val settings = ensureLocked(key)
        if (!settings.historyEnabled) return@write
        val sequence = (dao.historyKeys(key).firstOrNull()?.visitSequence ?: 0) + 1
        dao.visit(RepositoryVisitEntity(key, repositoryId, System.currentTimeMillis(), sequence, json.encodeToString(card)))
        pruneHistory(key, settings.historyLimit)
    }

    suspend fun recordGistVisit(account: AccountEpoch, gist: GistListItem) = write(account) {
        val key = accountKey(account)
        val settings = ensureLocked(key)
        if (!settings.historyEnabled) return@write
        val sequence = (dao.historyKeys(key).firstOrNull()?.visitSequence ?: 0) + 1
        dao.gistVisit(GistVisitEntity(key, gist.apiName, json.encodeToString(gist), System.currentTimeMillis(), sequence))
        pruneHistory(key, settings.historyLimit)
    }

    suspend fun deleteVisit(account: AccountEpoch, repositoryId: String) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        dao.deleteVisit(key, repositoryId)
    }

    suspend fun deleteGistVisit(account: AccountEpoch, gistId: String) = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
        dao.deleteGistVisit(key, gistId)
    }

    private suspend fun ensure(account: AccountEpoch): ApiResult<LibrarySettingsEntity> = write(account) {
        val key = accountKey(account); account.ensureCurrent(); ensureLocked(key)
    }

    private suspend fun ensureLocked(key: String): LibrarySettingsEntity {
        dao.ensureSettings(LibrarySettingsEntity(key))
        dao.ensureGroup(FavoriteGroupEntity(key, DEFAULT_FAVORITE_GROUP, "", "", 0))
        return checkNotNull(dao.settings(key))
    }

    private suspend fun <T> write(account: AccountEpoch, block: suspend () -> T): ApiResult<T> =
        session.changes.withLock {
            safeCall {
                account.ensureCurrent()
                database.withTransaction {
                    val value = block()
                    account.ensureCurrent()
                    value
                }
            }
        }

    private suspend fun accountKey(account: AccountEpoch): String {
        account.ensureCurrent()
        val key = checkNotNull(account.accountKey) { "An authenticated account is required" }
        return dao.alias(key) ?: key
    }

    private suspend fun pruneHistory(key: String, limit: Int) {
        dao.historyKeys(key).drop(limit).forEach {
            if (it.kind == "gist") dao.deleteGistVisit(key, it.id) else dao.deleteVisit(key, it.id)
        }
    }

    private fun nextOrder(partition: LibraryPartition, groupId: String) = partition.favorites.filter { it.groupId == groupId }.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
    private suspend fun normalizeGroups(partition: LibraryPartition) { partition.groups.sortedBy { it.sortOrder }.forEachIndexed { i, g -> dao.group(g.copy(sortOrder = i)) } }
    private suspend fun normalizeFavorites(partition: LibraryPartition, groupId: String) { partition.favorites.filter { it.groupId == groupId }.sortedBy { it.sortOrder }.forEachIndexed { i, f -> dao.favorite(f.copy(sortOrder = i)) } }
}

private fun LibrarySettingsEntity.toModel() = LibrarySettings(favoritesEnabled, historyEnabled, historyLimit)
private fun FavoriteGroupEntity.toModel(count: Int) = FavoriteGroup(id, name, count, sortOrder)
