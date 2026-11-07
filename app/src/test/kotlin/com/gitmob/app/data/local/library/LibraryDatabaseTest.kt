package com.gitmob.app.data.local.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.core.di.LibraryModule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDatabaseTest {
    @Test fun freshDatabaseStoresFavoriteAndHistoryPayloadsIndependently() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "repository-library.db"
        context.deleteDatabase(name)
        val database = LibraryModule.database(context)
        try {
            val dao = database.libraryDao()
            assertNull(dao.partition("account-a"))
            assertNull(dao.partition("account-b"))
            dao.ensureSettings(LibrarySettingsEntity("account-a"))
            dao.ensureGroup(FavoriteGroupEntity("account-a", "default", "", "", 0))
            dao.favorite(FavoriteEntity("account-a", "repository", "R1", "default", 0, "favorite payload"))
            dao.visit(RepositoryVisitEntity("account-a", "R1", 200000, 1, "history payload"))
            dao.gistVisit(GistVisitEntity("account-a", "G1", "gist payload", 200001, 2))
            val partition = checkNotNull(dao.partition("account-a"))
            assertEquals(LibrarySettingsEntity("account-a"), partition.settings)
            assertEquals("favorite payload", partition.favorites.single().payload)
            assertEquals("history payload", partition.visits.single().payload)
            assertEquals("gist payload", partition.gistVisits.single().payload)
            dao.deleteFavorite("account-a", "repository", "R1")
            assertEquals("history payload", checkNotNull(dao.partition("account-a")).visits.single().payload)
            dao.favorite(FavoriteEntity("account-a", "repository", "R1", "default", 0, "saved favorite"))
            dao.clearVisits("account-a")
            dao.clearGistVisits("account-a")
            val cleared = checkNotNull(dao.partition("account-a"))
            assertEquals("saved favorite", cleared.favorites.single().payload)
            assertTrue(cleared.visits.isEmpty())
            assertTrue(cleared.gistVisits.isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
