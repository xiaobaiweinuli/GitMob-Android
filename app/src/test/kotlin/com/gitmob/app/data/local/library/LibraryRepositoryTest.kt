package com.gitmob.app.data.local.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.auth.AccountSession
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.data.model.DEFAULT_FAVORITE_GROUP
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.FavoriteItem
import com.gitmob.app.data.model.LibraryContent
import com.gitmob.app.data.model.HistoryItem
import com.gitmob.app.data.model.RepoCardData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var session: AccountSession
    private val first = GitHubAccount("1", 1, "U1", "first")
    private val second = GitHubAccount("2", 2, "U2", "second")
    private val card = RepoCardData("repo", "owner", null, null, "PUBLIC", false, false, null, null, null, null, 1, 2, 3, emptyList(), "main")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        session = AccountSession().apply { replace(listOf(first, second), first.key) }
        repository = LibraryRepository(database, Json, session)
    }
    @After fun tearDown() { session.epoch.job.cancel(); database.close() }
    private fun <T> ApiResult<T>.ok(): T { assertTrue(toString(), this is ApiResult.Success); return (this as ApiResult.Success).data }
    private data class Snapshot(
        val settings: com.gitmob.app.data.model.LibrarySettings,
        val groups: List<com.gitmob.app.data.model.FavoriteGroup>,
        val favorites: List<FavoriteItem>,
        val history: List<com.gitmob.app.data.model.HistoryItem>,
    )
    private suspend fun state(epoch: AccountEpoch = session.epoch): Snapshot {
        val groups = repository.observeGroups(epoch).first()
        return Snapshot(repository.observeSettings(epoch).first(), groups,
            groups.sortedBy { it.id }.flatMap { repository.observe(epoch, com.gitmob.app.data.model.LibraryContent.Group(it.id)).first().favorites },
            repository.observe(epoch, com.gitmob.app.data.model.LibraryContent.History).first().history)
    }

    @Test fun defaultsAndNames() = runTest {
        val epoch = session.epoch
        val initial = state()
        assertEquals(100, initial.settings.historyLimit)
        assertTrue(initial.settings.favoritesEnabled && initial.settings.historyEnabled)
        assertEquals(listOf(DEFAULT_FAVORITE_GROUP), initial.groups.map { it.id })
        val group = repository.createGroup(epoch, " Work ").ok()
        assertEquals("Work", group.name)
        assertTrue(repository.createGroup(epoch, "work") is ApiResult.Failure)
        assertTrue(repository.createGroup(epoch, " ") is ApiResult.Failure)
        assertTrue(repository.createGroup(epoch, "a".repeat(31)) is ApiResult.Failure)
        assertTrue(repository.renameGroup(epoch, DEFAULT_FAVORITE_GROUP, "changed") is ApiResult.Failure)
        assertTrue(repository.deleteGroup(epoch, DEFAULT_FAVORITE_GROUP) is ApiResult.Failure)
    }
    @Test fun favoriteMoveUniquenessAndDeleteGroup() = runTest {
        val epoch = session.epoch
        val group = repository.createGroup(epoch, "Work").ok().id
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), DEFAULT_FAVORITE_GROUP).ok()
        repository.saveFavorite(epoch, FavoriteItem.Repository("R2", card.copy(name = "second")), group).ok()
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), group).ok()
        assertEquals(listOf("R2", "R1"), state().favorites.map { it.id })
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), group).ok()
        assertEquals(2, state().favorites.size)
        repository.reorderFavorites(epoch, group, listOf("repository:R1", "repository:R2")).ok()
        repository.deleteGroup(epoch, group).ok()
        assertEquals(listOf("R1", "R2"), state().favorites.map { it.id })
        assertTrue(state().favorites.all { it.groupId == DEFAULT_FAVORITE_GROUP })
        val remove = repository.createGroup(epoch, "Remove").ok().id
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), remove).ok()
        repository.recordVisit(epoch, "R1", card).ok()
        repository.deleteGroup(epoch, remove, true).ok()
        assertEquals(listOf("R2"), state().favorites.map { it.id })
        assertEquals(listOf("R1"), state().history.map { it.id })
    }
    @Test fun reorderMustMatchFullSetAndKeepDefaultFirst() = runTest {
        val epoch = session.epoch
        val a = repository.createGroup(epoch, "A").ok().id
        val b = repository.createGroup(epoch, "B").ok().id
        repository.reorderGroups(epoch, listOf(DEFAULT_FAVORITE_GROUP, b, a)).ok()
        assertEquals(listOf(DEFAULT_FAVORITE_GROUP, b, a), state().groups.map { it.id })
        assertTrue(repository.reorderGroups(epoch, listOf(b, DEFAULT_FAVORITE_GROUP, a)) is ApiResult.Failure)
        assertTrue(repository.reorderGroups(epoch, listOf(DEFAULT_FAVORITE_GROUP, a, a)) is ApiResult.Failure)
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), a).ok()
        assertTrue(repository.reorderFavorites(epoch, a, listOf("missing")) is ApiResult.Failure)
        assertEquals(listOf("R1"), state().favorites.map { it.id })
    }
    @Test fun historyDeduplicatesTrimsAndRetainsDataWhenDisabled() = runTest {
        val epoch = session.epoch
        assertTrue(repository.setHistoryLimit(epoch, 0) is ApiResult.Failure)
        assertTrue(repository.setHistoryLimit(epoch, 1001) is ApiResult.Failure)
        repository.setHistoryLimit(epoch, 2).ok()
        for (id in listOf("1", "2", "1", "3")) repository.recordVisit(epoch, id, card.copy(name = id)).ok()
        assertEquals(listOf("3", "1"), state().history.map { it.id })
        repository.setHistoryEnabled(epoch, false).ok()
        repository.recordVisit(epoch, "4", card).ok()
        assertEquals(listOf("3", "1"), state().history.map { it.id })
        repository.setHistoryLimit(epoch, 1, confirmTrim = true).ok()
        assertEquals(listOf("3"), state().history.map { it.id })
        repository.setHistoryEnabled(epoch, true).ok()
        repository.saveFavorite(epoch, FavoriteItem.Repository("3", card), DEFAULT_FAVORITE_GROUP).ok()
        repository.clearHistory(epoch).ok()
        assertEquals(1, state().favorites.size)
        assertTrue(state().history.isEmpty())
        assertEquals(card, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
    }
    @Test fun accountIsolationAndOldEpochCannotWrite() = runTest {
        val epoch = session.epoch
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), DEFAULT_FAVORITE_GROUP).ok()
        repository.setFavoritesEnabled(epoch, false).ok()
        repository.setHistoryLimit(epoch, 25).ok()
        session.changes.withLock { session.replace(listOf(first, second), second.key) }
        assertTrue(state().favorites.isEmpty())
        assertEquals(100, state().settings.historyLimit)
        assertTrue(state().settings.favoritesEnabled)
        try { repository.clearHistory(epoch); fail("Old epoch accepted") } catch (_: CancellationException) { }
        repository.saveFavorite(session.epoch, FavoriteItem.Repository("R1", card.copy(name = "other")), DEFAULT_FAVORITE_GROUP).ok()
        session.changes.withLock { session.replace(listOf(first, second), first.key) }
        assertEquals("repo", state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card.name)
        assertFalse(state().settings.favoritesEnabled)
        assertEquals(25, state().settings.historyLimit)
    }
    @Test fun queuedOldWriteRechecksEpochAfterAccountLock() = runTest {
        val old = session.epoch
        state()
        session.changes.lock()
        val pending = async { try { repository.recordVisit(old, "late", card); false } catch (_: CancellationException) { true } }
        testScheduler.runCurrent()
        session.replace(listOf(first, second), second.key)
        session.changes.unlock()
        assertTrue(pending.await())
        assertTrue(database.libraryDao().partition(first.key)!!.visits.isEmpty())
        assertTrue(state().history.isEmpty())
    }
    @Test fun failedFavoriteTransactionLeavesNoFavorite() = runTest {
        val epoch = session.epoch
        assertTrue(repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), "missing") is ApiResult.Failure)
        assertNull(database.libraryDao().partition(first.key))
        assertTrue(state().favorites.isEmpty())
        assertTrue(database.libraryDao().partition(first.key)!!.favorites.isEmpty())
    }
    private fun gist(id: String) = com.gitmob.app.data.model.GistListItem(
        id = "G_$id", apiName = id, ownerLogin = "owner", description = null, url = "https://gist.github.com/owner/$id",
        isPublic = true, isFork = false, isOwnedByViewer = false, createdAt = "", updatedAt = "",
        stargazerCount = 0, commentCount = 0, previewFile = null, fileCount = 1, isFileCountCapped = false,
    )

    @Test fun staleCountCannotTrimWithoutExplicitConfirmation() = runTest {
        val epoch = session.epoch
        repository.recordVisit(epoch, "repo", card).ok()
        val displayedCount = repository.observeHistoryCount(epoch).first()
        repository.recordGistVisit(epoch, gist("new")).ok()
        assertEquals(1, displayedCount)
        val result = repository.setHistoryLimit(epoch, displayedCount).ok()
        assertEquals(com.gitmob.app.data.model.HistoryLimitResult.NeedsConfirmation(2), result)
        assertEquals(100, repository.observeSettings(epoch).first().historyLimit)
        assertEquals(2, repository.observeHistoryCount(epoch).first())
        assertEquals(com.gitmob.app.data.model.HistoryLimitResult.Saved,
            repository.setHistoryLimit(epoch, 1, confirmTrim = true).ok())
        assertEquals(listOf("gist:new"), state().history.map { it.key })
    }

    @Test fun mixedHistoryUsesTypeAndIdWithOneLimitAndDisabledRetention() = runTest {
        val epoch = session.epoch
        repository.setHistoryLimit(epoch, 2).ok()
        repository.recordVisit(epoch, "same", card).ok()
        repository.recordGistVisit(epoch, gist("same")).ok()
        assertEquals(listOf("gist:same", "repository:same"), state().history.map { it.key })
        repository.recordVisit(epoch, "same", card).ok()
        assertEquals(listOf("repository:same", "gist:same"), state().history.map { it.key })
        repository.recordGistVisit(epoch, gist("new")).ok()
        assertEquals(listOf("gist:new", "repository:same"), state().history.map { it.key })
        repository.setHistoryEnabled(epoch, false).ok()
        repository.recordGistVisit(epoch, gist("ignored")).ok()
        assertEquals(2, state().history.size)
        repository.deleteGistVisit(epoch, "new").ok()
        assertEquals(listOf("repository:same"), state().history.map { it.key })
        repository.clearHistory(epoch).ok()
        assertTrue(state().history.isEmpty())
    }

    @Test fun pageQueriesDoNotDecodeUnrelatedCards() = runTest {
        val epoch = session.epoch
        repository.saveFavorite(epoch, FavoriteItem.Repository("good", card), DEFAULT_FAVORITE_GROUP).ok()
        val other = repository.createGroup(epoch, "Other").ok().id
        repository.saveFavorite(epoch, FavoriteItem.Repository("bad", card), other).ok()
        database.libraryDao().updateFavoritePayload(first.key, "repository", "bad", "invalid JSON")
        assertEquals(100, repository.observeSettings(epoch).first().historyLimit)
        val groups = repository.observeGroups(epoch).first()
        assertEquals(2, groups.sumOf { it.count })
        val selected = repository.observe(epoch, com.gitmob.app.data.model.LibraryContent.Group(DEFAULT_FAVORITE_GROUP)).first()
        assertEquals(listOf("good"), selected.favorites.map { it.id })
        assertEquals(0, repository.observeHistoryCount(epoch).first())
    }

    @Test fun gistHistorySurvivesAccountSwitchAndLegacyMigration() = runTest {
        val epoch = session.epoch
        repository.recordGistVisit(epoch, gist("private")).ok()
        session.changes.withLock { session.replace(listOf(first, second), second.key) }
        assertTrue(state().history.isEmpty())
        repository.recordGistVisit(session.epoch, gist("second")).ok()
        session.changes.withLock { session.replace(listOf(first, second), first.key) }
        assertEquals(listOf("private"), state().history.map { it.id })
        session.changes.withLock { LibraryAccountMigration(database).migrateLocked(first.key, "stable") }
        assertEquals(listOf("private"), state().history.map { it.id })
        assertNull(database.libraryDao().partition(first.key))
    }

    @Test fun legacyMigrationMergesAndCanBeRetriedAfterCredentialFailure() = runTest {
        val epoch = session.epoch
        val sourceGroup = repository.createGroup(epoch, "Work").ok().id
        repository.saveFavorite(epoch, FavoriteItem.Repository("old", card), sourceGroup).ok()
        repository.recordVisit(epoch, "shared", card).ok()
        val dao = database.libraryDao()
        dao.ensureSettings(LibrarySettingsEntity("stable", historyLimit = 1))
        dao.ensureGroup(FavoriteGroupEntity("stable", DEFAULT_FAVORITE_GROUP, "", "", 0))
        dao.group(FavoriteGroupEntity("stable", "target-work", "Work", "work", 1))
        dao.favorite(FavoriteEntity("stable", "repository", "existing", "target-work", 0, Json.encodeToString(card)))
        val migration = LibraryAccountMigration(database)
        session.changes.withLock { migration.migrateLocked(first.key, "stable") }
        val merged = state()
        assertEquals(listOf("existing", "old"), merged.favorites.map { it.id })
        assertEquals(2, merged.groups.size)
        assertEquals(1, merged.settings.historyLimit)
        session.changes.withLock { migration.migrateLocked(first.key, "stable") }
        assertEquals(merged, state())
        repository.saveFavorite(epoch, FavoriteItem.Repository("after-failure", card), DEFAULT_FAVORITE_GROUP).ok()
        assertEquals(3, state().favorites.size)
        assertNull(dao.partition(first.key))
    }

    @Test fun favoriteAndHistoryOwnTheirCardPayloads() = runTest {
        val epoch = session.epoch
        val favorite = card.copy(name = "favorite")
        val visited = card.copy(name = "visited")
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", favorite), DEFAULT_FAVORITE_GROUP).ok()
        repository.recordVisit(epoch, "R1", visited).ok()
        assertEquals(favorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        assertEquals(visited, (state().history.single() as HistoryItem.Repository).card)
        val originalVisit = state().history.single()

        val refreshed = card.copy(name = "refreshed")
        repository.refreshFavorite(epoch, "R1", refreshed).ok()
        assertEquals(refreshed, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        assertEquals(originalVisit, state().history.single())
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", favorite), DEFAULT_FAVORITE_GROUP).ok()
        assertEquals(originalVisit, state().history.single())

        repository.recordVisit(epoch, "R1", refreshed).ok()
        assertEquals(favorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        assertEquals(refreshed, (state().history.single() as HistoryItem.Repository).card)
    }

    @Test fun favoriteRefreshWorksWithHistoryDisabledAndPreservesGroupAndOrder() = runTest {
        val epoch = session.epoch
        val group = repository.createGroup(epoch, "Work").ok().id
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), group).ok()
        repository.saveFavorite(epoch, FavoriteItem.Repository("R2", card), group).ok()
        repository.recordVisit(epoch, "R1", card).ok()
        repository.recordVisit(epoch, "R2", card).ok()
        val previousHistory = state().history
        repository.setHistoryEnabled(epoch, false).ok()
        val updated = card.copy(name = "updated")
        repository.refreshFavorite(epoch, "R1", updated).ok()
        repository.refreshFavorite(epoch, "not-favorited", updated).ok()
        repository.recordVisit(epoch, "R1", updated).ok()
        repository.recordVisit(epoch, "not-favorited", updated).ok()
        val result = state()
        assertEquals(listOf("R1", "R2"), result.favorites.map { it.id })
        assertTrue(result.favorites.all { it.groupId == group })
        assertEquals(updated, result.favorites.filterIsInstance<FavoriteItem.Repository>().first().card)
        assertEquals(card, result.favorites.filterIsInstance<FavoriteItem.Repository>().last().card)
        assertEquals(previousHistory, result.history)
    }

    @Test fun historyStillRecordsWithFavoritesDisabled() = runTest {
        val epoch = session.epoch
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card), DEFAULT_FAVORITE_GROUP).ok()
        repository.setFavoritesEnabled(epoch, false).ok()
        val visited = card.copy(name = "visited")
        repository.recordVisit(epoch, "R1", visited).ok()
        assertEquals(card, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        assertEquals(visited, (state().history.single() as HistoryItem.Repository).card)
    }

    @Test fun historyDeletionAndRetentionDoNotRemoveFavorites() = runTest {
        val epoch = session.epoch
        val favorite = card.copy(name = "favorite")
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", favorite), DEFAULT_FAVORITE_GROUP).ok()
        repository.recordVisit(epoch, "R1", card).ok()
        repository.deleteVisit(epoch, "R1").ok()
        assertTrue(state().history.isEmpty())
        assertEquals(favorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)

        repository.recordVisit(epoch, "R1", card).ok()
        repository.recordVisit(epoch, "R2", card).ok()
        repository.setHistoryLimit(epoch, 1, confirmTrim = true).ok()
        assertEquals(listOf("R2"), state().history.map { it.id })
        assertEquals(favorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)

        repository.recordVisit(epoch, "R1", card).ok()
        repository.recordGistVisit(epoch, gist("new")).ok()
        assertEquals(listOf("gist:new"), state().history.map { it.key })
        assertEquals(favorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        repository.clearHistory(epoch).ok()
        assertTrue(state().history.isEmpty())
        assertEquals(favorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
    }

    @Test fun removingFavoriteRetainsHistoryCard() = runTest {
        val epoch = session.epoch
        repository.recordVisit(epoch, "R1", card).ok()
        val history = state().history
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card.copy(name = "favorite")), DEFAULT_FAVORITE_GROUP).ok()
        repository.removeFavorite(epoch, FavoriteItem.Repository("R1", card)).ok()
        repository.refreshFavorite(epoch, "R1", card.copy(name = "later")).ok()
        assertTrue(state().favorites.isEmpty())
        assertEquals(history, state().history)
    }

    @Test fun favoriteRefreshIsAccountScopedAndRejectsQueuedOldEpoch() = runTest {
        val old = session.epoch
        repository.saveFavorite(old, FavoriteItem.Repository("R1", card), DEFAULT_FAVORITE_GROUP).ok()
        session.changes.lock()
        val pending = async {
            try { repository.refreshFavorite(old, "R1", card.copy(name = "late")); false }
            catch (_: CancellationException) { true }
        }
        testScheduler.runCurrent()
        session.replace(listOf(first, second), second.key)
        session.changes.unlock()
        assertTrue(pending.await())
        repository.refreshFavorite(session.epoch, "R1", card.copy(name = "not-favorited")).ok()
        assertTrue(state().favorites.isEmpty())
        repository.saveFavorite(session.epoch, FavoriteItem.Repository("R1", card), DEFAULT_FAVORITE_GROUP).ok()
        repository.refreshFavorite(session.epoch, "R1", card.copy(name = "second")).ok()
        assertEquals("second", state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card.name)
        session.changes.withLock { session.replace(listOf(first, second), first.key) }
        assertEquals(card, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
    }

    @Test fun accountIdentityMergeKeepsFavoriteAndHistoryPayloadsIndependent() = runTest {
        val epoch = session.epoch
        repository.saveFavorite(epoch, FavoriteItem.Repository("R1", card.copy(name = "source-favorite")), DEFAULT_FAVORITE_GROUP).ok()
        val visited = card.copy(name = "source-history")
        repository.recordVisit(epoch, "R1", visited).ok()
        val dao = database.libraryDao()
        dao.ensureSettings(LibrarySettingsEntity("stable"))
        dao.ensureGroup(FavoriteGroupEntity("stable", DEFAULT_FAVORITE_GROUP, "", "", 0))
        val targetFavorite = card.copy(name = "target-favorite")
        dao.favorite(FavoriteEntity("stable", "repository", "R1", DEFAULT_FAVORITE_GROUP, 0, Json.encodeToString(targetFavorite)))
        dao.visit(RepositoryVisitEntity("stable", "R1", 0, 1, Json.encodeToString(card)))
        session.changes.withLock { LibraryAccountMigration(database).migrateLocked(first.key, "stable") }
        assertEquals(targetFavorite, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        assertEquals(visited, (state().history.single() as HistoryItem.Repository).card)
        repository.refreshFavorite(epoch, "R1", card).ok()
        assertEquals(card, state().favorites.filterIsInstance<FavoriteItem.Repository>().single().card)
        assertEquals(visited, (state().history.single() as HistoryItem.Repository).card)
        assertNull(dao.partition(first.key))
    }

    @Test fun mixedFavoritesUseTypeAndIdForQueriesOrderingMovesAndDeletion() = runTest {
        val epoch = session.epoch
        val repo = FavoriteItem.Repository("same", card)
        val snippet = FavoriteItem.Gist(gist("same"))
        val group = repository.createGroup(epoch, "Mixed").ok().id
        repository.saveFavorite(epoch, repo, group).ok()
        repository.saveFavorite(epoch, snippet, group).ok()
        repository.saveFavorite(epoch, snippet, group).ok()
        assertEquals(2, repository.observeGroups(epoch).first().single { it.id == group }.count)
        assertEquals(listOf(repo.key), repository.observe(epoch, LibraryContent.Repository("same")).first().favorites.map { it.key })
        assertEquals(listOf(snippet.key), repository.observe(epoch, LibraryContent.Gist("same")).first().favorites.map { it.key })
        repository.reorderFavorites(epoch, group, listOf(snippet.key, repo.key)).ok()
        assertEquals(listOf(snippet.key, repo.key), state().favorites.map { it.key })
        repository.saveFavorite(epoch, snippet, DEFAULT_FAVORITE_GROUP).ok()
        assertEquals(DEFAULT_FAVORITE_GROUP, state().favorites.single { it.key == snippet.key }.groupId)
        assertEquals(group, state().favorites.single { it.key == repo.key }.groupId)
        repository.removeFavorite(epoch, snippet).ok()
        assertEquals(listOf(repo.key), state().favorites.map { it.key })
    }

    @Test fun gistFavoritePayloadAndHistoryAreIndependentAcrossRefreshDeletionAndSettings() = runTest {
        val epoch = session.epoch
        val saved = gist("abc").copy(description = "favorite")
        val visited = saved.copy(description = "history")
        val item = FavoriteItem.Gist(saved)
        repository.saveFavorite(epoch, item, DEFAULT_FAVORITE_GROUP).ok()
        repository.recordGistVisit(epoch, visited).ok()
        assertEquals(saved, (state().favorites.single() as FavoriteItem.Gist).gist)
        assertEquals(visited, (state().history.single() as HistoryItem.Gist).gist)
        val previousHistory = state().history
        repository.setHistoryEnabled(epoch, false).ok()
        val updated = saved.copy(description = "updated")
        repository.refreshGistFavorite(epoch, updated).ok()
        repository.refreshGistFavorite(epoch, gist("not-favorited")).ok()
        repository.recordGistVisit(epoch, updated).ok()
        assertEquals(previousHistory, state().history)
        assertEquals(updated, (state().favorites.single() as FavoriteItem.Gist).gist)
        repository.clearHistory(epoch).ok()
        assertEquals(updated, (state().favorites.single() as FavoriteItem.Gist).gist)
        assertTrue(state().history.isEmpty())
        repository.setFavoritesEnabled(epoch, false).ok()
        assertTrue(repository.saveFavorite(epoch, item, DEFAULT_FAVORITE_GROUP) is ApiResult.Failure)
        repository.setHistoryEnabled(epoch, true).ok()
        repository.recordGistVisit(epoch, visited).ok()
        repository.removeFavorite(epoch, item).ok()
        assertEquals(visited, (state().history.single() as HistoryItem.Gist).gist)
        assertTrue(state().favorites.isEmpty())
    }

    @Test fun deletingMixedGroupMovesOrRemovesBothKindsWithoutChangingHistory() = runTest {
        val epoch = session.epoch
        val group = repository.createGroup(epoch, "Mixed").ok().id
        val repo = FavoriteItem.Repository("same", card)
        val snippet = FavoriteItem.Gist(gist("same"))
        repository.saveFavorite(epoch, repo, group).ok()
        repository.saveFavorite(epoch, snippet, group).ok()
        repository.recordVisit(epoch, repo.id, card).ok()
        repository.recordGistVisit(epoch, snippet.gist).ok()
        val history = state().history
        repository.deleteGroup(epoch, group).ok()
        assertEquals(listOf(repo.key, snippet.key), state().favorites.map { it.key })
        assertTrue(state().favorites.all { it.groupId == DEFAULT_FAVORITE_GROUP })
        val remove = repository.createGroup(epoch, "Remove").ok().id
        repository.saveFavorite(epoch, repo, remove).ok()
        repository.saveFavorite(epoch, snippet, remove).ok()
        repository.deleteGroup(epoch, remove, removeFavorites = true).ok()
        assertTrue(state().favorites.isEmpty())
        assertEquals(history, state().history)
    }

    @Test fun gistFavoritesAreAccountScopedAndQueuedOldRefreshCannotCrossAccounts() = runTest {
        val old = session.epoch
        val item = FavoriteItem.Gist(gist("private").copy(isPublic = false))
        repository.saveFavorite(old, item, DEFAULT_FAVORITE_GROUP).ok()
        session.changes.lock()
        val pending = async {
            try { repository.refreshGistFavorite(old, item.gist.copy(description = "late")); false }
            catch (_: CancellationException) { true }
        }
        testScheduler.runCurrent()
        session.replace(listOf(first, second), second.key)
        session.changes.unlock()
        assertTrue(pending.await())
        assertTrue(state().favorites.isEmpty())
        repository.saveFavorite(session.epoch, item.copy(gist = item.gist.copy(description = "second")), DEFAULT_FAVORITE_GROUP).ok()
        session.changes.withLock { session.replace(listOf(first, second), first.key) }
        assertEquals(item.gist, (state().favorites.single() as FavoriteItem.Gist).gist)
        session.changes.withLock { LibraryAccountMigration(database).migrateLocked(first.key, "stable") }
        assertEquals(item.gist, (state().favorites.single() as FavoriteItem.Gist).gist)
        assertNull(database.libraryDao().partition(first.key))
    }

    @Test fun accountIdentityMergeKeepsSameIdRepositoryAndGistFavorites() = runTest {
        val epoch = session.epoch
        val item = FavoriteItem.Gist(gist("same"))
        repository.saveFavorite(epoch, item, DEFAULT_FAVORITE_GROUP).ok()
        repository.recordGistVisit(epoch, item.gist.copy(description = "history")).ok()
        val dao = database.libraryDao()
        dao.ensureSettings(LibrarySettingsEntity("stable"))
        dao.ensureGroup(FavoriteGroupEntity("stable", DEFAULT_FAVORITE_GROUP, "", "", 0))
        dao.favorite(FavoriteEntity("stable", "repository", "same", DEFAULT_FAVORITE_GROUP, 0, Json.encodeToString(card)))
        session.changes.withLock { LibraryAccountMigration(database).migrateLocked(first.key, "stable") }
        assertEquals(listOf("repository:same", "gist:same"), state().favorites.map { it.key })
        assertEquals(2, state().groups.single().count)
        assertEquals(item.gist, (state().favorites.last() as FavoriteItem.Gist).gist)
        assertEquals("history", (state().history.single() as HistoryItem.Gist).gist.description)
    }
}
