package com.gitmob.app.ui.repodiscussions

import androidx.lifecycle.viewModelScope
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEvent
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.core.permission.RepoCapabilities
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import com.gitmob.app.data.repository.RepoDiscussionRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoDiscussionListSearchTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun `discussion change refreshes category search and clearing keeps the same category`() = runTest(accounts.context) {
        val category = RepoDiscussionCategory("C", "Help", "", null, true)
        val filter = RepoDiscussionFilter(category = category)
        val page = RepoDiscussionPage("R", RepoPermission.READ, RepoCapabilities.NONE, true, listOf(category), 1, emptyList(), false, null)
        val repository = mockk<RepoDiscussionRepository>()
        coEvery { repository.getDiscussions("o", "r", any(), null) } returns ApiResult.Success(page)
        coEvery { repository.searchDiscussions("o", "r", "topic", filter, null) } returnsMany listOf(
            ApiResult.Success(page), ApiResult.Success(page.copy(totalCount = 2)),
        )
        val events = RepoUpdateEventBus()
        val vm = RepoDiscussionListViewModel(repository, ErrorEventBus(), events, accounts.session)
        vm.init("o", "r", RepoPermission.READ)
        vm.setCategory(category)
        vm.submitSearch("topic")
        advanceUntilIdle()
        events.emit(RepoUpdateEvent.DiscussionCountChanged("o", "r", 500))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.totalCount)
        coVerify(exactly = 2) { repository.searchDiscussions("o", "r", "topic", filter, null) }
        vm.submitSearch("")
        advanceUntilIdle()
        assertEquals(filter, vm.state.value.filter)
        coVerify(exactly = 2) { repository.getDiscussions("o", "r", filter, null) }
        vm.viewModelScope.cancel()
    }
}
