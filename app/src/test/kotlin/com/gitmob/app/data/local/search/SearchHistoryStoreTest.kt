package com.gitmob.app.data.local.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.testutil.AccountSessionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchHistoryStoreTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun historyPreservesExactQueryAndMovesDuplicatesToFrontWithBoundedSize() {
        val queries = (1..PageSize.SEARCH_HISTORY).map { "query$it" }
        val updated = updatedHistory(queries, "  query10  ")
        assertEquals(PageSize.SEARCH_HISTORY, updated.size)
        assertEquals("query10", updated.first())
        assertEquals(1, updated.count { it == "query10" })
        assertEquals("\"Hello  World\" language:Kotlin", updatedHistory(updated, " \"Hello  World\" language:Kotlin ").first())
        assertEquals(queries, updatedHistory(queries, "  "))
        assertEquals(listOf("Query1", "query1"), updatedHistory(listOf("query1"), "Query1"))
    }

    @Test fun historyIsIsolatedAndSurvivesSwitchingBackToSameAccount() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SearchHistoryStore(PreferenceDataStoreFactory.create(scope = scope) {
                temporary.root.resolve("search.preferences_pb")
            }, Json)
            val firstAccount = accounts.session.state.value.activeAccount!!
            val firstEpoch = accounts.session.epoch
            assertTrue(store.record(firstEpoch, "first") is ApiResult.Success)
            val other = GitHubAccount("2", 2, "U_2", "other")
            accounts.session.replace(listOf(firstAccount, other), other.key)
            assertTrue(store.observe(accounts.session.epoch).first().isEmpty())
            store.record(accounts.session.epoch, "second")
            store.clear(accounts.session.epoch)
            accounts.session.replace(listOf(firstAccount, other), firstAccount.key)
            assertEquals(listOf("first"), store.observe(accounts.session.epoch).first())
            assertNotEquals(firstEpoch.id, accounts.session.epoch.id)
        } finally { scope.cancel() }
    }
}
