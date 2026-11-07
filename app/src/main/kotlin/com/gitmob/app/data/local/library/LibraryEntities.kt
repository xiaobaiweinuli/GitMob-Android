package com.gitmob.app.data.local.library

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Relation

@Entity(tableName = "library_settings", primaryKeys = ["accountKey"])
data class LibrarySettingsEntity(val accountKey: String, val favoritesEnabled: Boolean = true,
    val historyEnabled: Boolean = true, val historyLimit: Int = 100)

@Entity(tableName = "favorite_groups", primaryKeys = ["accountKey", "id"],
    indices = [Index(value = ["accountKey", "normalizedName"], unique = true)])
data class FavoriteGroupEntity(val accountKey: String, val id: String, val name: String,
    val normalizedName: String, val sortOrder: Int)

@Entity(tableName = "favorite_items", primaryKeys = ["accountKey", "kind", "itemId"],
    indices = [Index(value = ["accountKey", "groupId"])], foreignKeys = [
        ForeignKey(entity = FavoriteGroupEntity::class, parentColumns = ["accountKey", "id"],
            childColumns = ["accountKey", "groupId"]),
    ])
data class FavoriteEntity(val accountKey: String, val kind: String, val itemId: String,
    val groupId: String, val sortOrder: Int, val payload: String)

@Entity(tableName = "repository_visits", primaryKeys = ["accountKey", "repositoryId"])
data class RepositoryVisitEntity(val accountKey: String, val repositoryId: String,
    val visitedAt: Long, val visitSequence: Long, val payload: String)

@Entity(tableName = "library_aliases", primaryKeys = ["source"])
data class LibraryAliasEntity(val source: String, val target: String)

/** Full partition is used for account migration and transactional mutations. */
data class LibraryPartition(
    @Embedded val settings: LibrarySettingsEntity,
    @Relation(parentColumn = "accountKey", entityColumn = "accountKey") val groups: List<FavoriteGroupEntity>,
    @Relation(parentColumn = "accountKey", entityColumn = "accountKey") val favorites: List<FavoriteEntity>,
    @Relation(parentColumn = "accountKey", entityColumn = "accountKey") val visits: List<RepositoryVisitEntity>,
    @Relation(parentColumn = "accountKey", entityColumn = "accountKey") val gistVisits: List<GistVisitEntity>,
)

@Entity(tableName = "gist_visits", primaryKeys = ["accountKey", "gistId"])
data class GistVisitEntity(val accountKey: String, val gistId: String, val payload: String,
    val visitedAt: Long, val visitSequence: Long)

data class FavoriteGroupCount(@Embedded val group: FavoriteGroupEntity, val count: Int)
data class FavoriteCardRow(val kind: String, val itemId: String, val groupId: String, val payload: String)
data class HistoryRow(val kind: String, val id: String, val payload: String, val visitedAt: Long, val visitSequence: Long)
data class HistoryKey(val kind: String, val id: String, val visitSequence: Long)

internal val FavoriteEntity.key: String get() = "$kind:$itemId"
