package com.gitmob.app.data.local.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_settings WHERE accountKey = :accountKey")
    suspend fun settings(accountKey: String): LibrarySettingsEntity?
    @Query("SELECT COALESCE((SELECT target FROM library_aliases WHERE source = :accountKey), :accountKey)")
    fun observeAccountKey(accountKey: String): Flow<String>
    @Query("SELECT * FROM library_settings WHERE accountKey = :accountKey")
    fun observeSettings(accountKey: String): Flow<LibrarySettingsEntity?>
    @Query("SELECT g.*, COUNT(f.itemId) AS count FROM favorite_groups g LEFT JOIN favorite_items f ON g.accountKey = f.accountKey AND g.id = f.groupId WHERE g.accountKey = :accountKey GROUP BY g.accountKey, g.id ORDER BY g.sortOrder")
    fun observeGroups(accountKey: String): Flow<List<FavoriteGroupCount>>
    @Query("SELECT f.kind, f.itemId, f.groupId, f.payload FROM favorite_items f WHERE f.accountKey = :accountKey AND (:groupId IS NULL OR f.groupId = :groupId) AND (:kind IS NULL OR f.kind = :kind) AND (:itemId IS NULL OR f.itemId = :itemId) ORDER BY f.groupId, f.sortOrder")
    fun observeFavorites(accountKey: String, groupId: String?, kind: String?, itemId: String?): Flow<List<FavoriteCardRow>>
    @Query("SELECT 'repository' AS kind, v.repositoryId AS id, v.payload, v.visitedAt, v.visitSequence FROM repository_visits v WHERE v.accountKey = :accountKey UNION ALL SELECT 'gist' AS kind, gistId AS id, payload, visitedAt, visitSequence FROM gist_visits WHERE accountKey = :accountKey ORDER BY visitSequence DESC")
    fun observeHistory(accountKey: String): Flow<List<HistoryRow>>
    @Query("SELECT (SELECT COUNT(*) FROM repository_visits WHERE accountKey = :accountKey) + (SELECT COUNT(*) FROM gist_visits WHERE accountKey = :accountKey)")
    fun observeHistoryCount(accountKey: String): Flow<Int>
    @Query("SELECT (SELECT COUNT(*) FROM repository_visits WHERE accountKey = :accountKey) + (SELECT COUNT(*) FROM gist_visits WHERE accountKey = :accountKey)")
    suspend fun historyCount(accountKey: String): Int
    @Query("SELECT 'repository' AS kind, repositoryId AS id, visitSequence FROM repository_visits WHERE accountKey = :accountKey UNION ALL SELECT 'gist' AS kind, gistId AS id, visitSequence FROM gist_visits WHERE accountKey = :accountKey ORDER BY visitSequence DESC")
    suspend fun historyKeys(accountKey: String): List<HistoryKey>
    @Upsert suspend fun gistVisit(value: GistVisitEntity)
    @Query("DELETE FROM gist_visits WHERE accountKey = :accountKey AND gistId = :id")
    suspend fun deleteGistVisit(accountKey: String, id: String)
    @Query("DELETE FROM gist_visits WHERE accountKey = :accountKey")
    suspend fun clearGistVisits(accountKey: String)

    @Transaction
    @Query("SELECT * FROM library_settings WHERE accountKey = COALESCE((SELECT target FROM library_aliases WHERE source = :accountKey), :accountKey)")
    fun observe(accountKey: String): Flow<LibraryPartition?>

    @Query("SELECT target FROM library_aliases WHERE source = :accountKey")
    suspend fun alias(accountKey: String): String?
    @Transaction
    @Query("SELECT * FROM library_settings WHERE accountKey = :accountKey")
    suspend fun partition(accountKey: String): LibraryPartition?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureSettings(value: LibrarySettingsEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureGroup(value: FavoriteGroupEntity)
    @Upsert suspend fun settings(value: LibrarySettingsEntity)
    @Upsert suspend fun group(value: FavoriteGroupEntity)
    @Upsert suspend fun favorite(value: FavoriteEntity)
    @Query("UPDATE favorite_items SET payload = :payload WHERE accountKey = :accountKey AND kind = :kind AND itemId = :id")
    suspend fun updateFavoritePayload(accountKey: String, kind: String, id: String, payload: String)
    @Upsert suspend fun visit(value: RepositoryVisitEntity)
    @Upsert suspend fun saveAlias(value: LibraryAliasEntity)
    @Query("DELETE FROM favorite_items WHERE accountKey = :accountKey AND kind = :kind AND itemId = :id")
    suspend fun deleteFavorite(accountKey: String, kind: String, id: String)
    @Query("DELETE FROM favorite_groups WHERE accountKey = :accountKey AND id = :id")
    suspend fun deleteGroup(accountKey: String, id: String)
    @Query("DELETE FROM repository_visits WHERE accountKey = :accountKey AND repositoryId = :id")
    suspend fun deleteVisit(accountKey: String, id: String)
    @Query("DELETE FROM repository_visits WHERE accountKey = :accountKey")
    suspend fun clearVisits(accountKey: String)
    @Query("DELETE FROM favorite_items WHERE accountKey = :accountKey")
    suspend fun clearFavorites(accountKey: String)
    @Query("DELETE FROM favorite_groups WHERE accountKey = :accountKey")
    suspend fun clearGroups(accountKey: String)
    @Query("DELETE FROM library_settings WHERE accountKey = :accountKey")
    suspend fun clearSettings(accountKey: String)
}
