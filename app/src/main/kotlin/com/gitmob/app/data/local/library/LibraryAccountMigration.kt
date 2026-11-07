package com.gitmob.app.data.local.library

import androidx.room.withTransaction
import com.gitmob.app.data.model.DEFAULT_FAVORITE_GROUP
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Caller holds AccountSession.changes. Alias keeps a failed credential commit retryable. */
@Singleton
class LibraryAccountMigration @Inject constructor(private val database: LibraryDatabase) {
    suspend fun migrateLocked(source: String?, target: String) {
        if (source == null || source == target) return
        val dao = database.libraryDao()
        database.withTransaction {
            if (dao.alias(source) == target) return@withTransaction
            val old = dao.partition(source) ?: return@withTransaction
            val existing = dao.partition(target)
            dao.ensureSettings(old.settings.copy(accountKey = target))
            dao.ensureGroup(FavoriteGroupEntity(target, DEFAULT_FAVORITE_GROUP, "", "", 0))
            val groupMap = mutableMapOf(DEFAULT_FAVORITE_GROUP to DEFAULT_FAVORITE_GROUP)
            val names = existing?.groups.orEmpty().associateBy { it.normalizedName }.toMutableMap()
            var order = existing?.groups?.maxOfOrNull { it.sortOrder } ?: 0
            old.groups.filter { it.id != DEFAULT_FAVORITE_GROUP }.sortedBy { it.sortOrder }.forEach { group ->
                val match = names[group.normalizedName]
                if (match != null) groupMap[group.id] = match.id
                else {
                    val migrated = group.copy(accountKey = target, id = UUID.randomUUID().toString(), sortOrder = ++order)
                    dao.group(migrated); names[migrated.normalizedName] = migrated; groupMap[group.id] = migrated.id
                }
            }
            val knownFavorites = existing?.favorites.orEmpty().map { it.key }.toSet()
            val counts = existing?.favorites.orEmpty().groupBy { it.groupId }.mapValues { (_, values) -> values.maxOf { it.sortOrder } + 1 }.toMutableMap()
            old.favorites.sortedBy { it.sortOrder }.filter { it.key !in knownFavorites }.forEach { favorite ->
                val group = groupMap.getValue(favorite.groupId)
                val next = counts[group] ?: 0
                dao.favorite(favorite.copy(accountKey = target, groupId = group, sortOrder = next))
                counts[group] = next + 1
            }
            val visits = (old.visits + existing?.visits.orEmpty()).groupBy { it.repositoryId }
                .map { (_, values) -> values.maxBy { it.visitedAt } }
            val gistVisits = (old.gistVisits + existing?.gistVisits.orEmpty()).groupBy { it.gistId }
                .map { (_, values) -> values.maxBy { it.visitedAt } }
            val timeline = (visits.map { Triple("repository:" + it.repositoryId, it.visitedAt, it.visitSequence) } +
                gistVisits.map { Triple("gist:" + it.gistId, it.visitedAt, it.visitSequence) })
                .sortedWith(compareBy({ it.second }, { it.third })).takeLast((existing?.settings ?: old.settings).historyLimit)
            val sequences = timeline.mapIndexed { index, entry -> entry.first to index.toLong() + 1 }.toMap()
            dao.clearVisits(target); dao.clearGistVisits(target)
            visits.forEach { visit -> sequences["repository:" + visit.repositoryId]?.let { sequence ->
                dao.visit(visit.copy(accountKey = target, visitSequence = sequence))
            } }
            gistVisits.forEach { visit -> sequences["gist:" + visit.gistId]?.let { sequence ->
                dao.gistVisit(visit.copy(accountKey = target, visitSequence = sequence))
            } }
            dao.clearFavorites(source); dao.clearVisits(source); dao.clearGistVisits(source); dao.clearGroups(source)
            dao.clearSettings(source)
            dao.saveAlias(LibraryAliasEntity(source, target))
        }
    }
}
