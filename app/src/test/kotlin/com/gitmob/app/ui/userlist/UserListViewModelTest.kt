package com.gitmob.app.ui.userlist

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.PagedUsers
import com.gitmob.app.data.model.SimpleUser
import com.gitmob.app.data.repository.RepoDetailRepository
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class UserListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `init只触发一次加载，重复调用不重复请求`() = runTest {
        val repo = mockk<UserRepository>()
        var callCount = 0
        coEvery { repo.getFollowers("octocat", null) } answers {
            callCount++
            ApiResult.Success(PagedUsers(1, listOf(SimpleUser("a", null, null, null)), false, null))
        }

        val viewModel = UserListViewModel(repo, mockk<RepoDetailRepository>(relaxed = true), ErrorEventBus())
        viewModel.init("octocat", UserListMode.FOLLOWERS)
        viewModel.init("octocat", UserListMode.FOLLOWERS) // 重复调用

        assertEquals(1, callCount)
    }

    @Test
    fun `loadMore追加数据而不是替换`() = runTest {
        val repo = mockk<UserRepository>()
        coEvery { repo.getFollowers("octocat", null) } returns ApiResult.Success(
            PagedUsers(2, listOf(SimpleUser("a", null, null, null)), true, "cursor1"),
        )
        coEvery { repo.getFollowers("octocat", "cursor1") } returns ApiResult.Success(
            PagedUsers(2, listOf(SimpleUser("b", null, null, null)), false, null),
        )

        val viewModel = UserListViewModel(repo, mockk<RepoDetailRepository>(relaxed = true), ErrorEventBus())
        viewModel.init("octocat", UserListMode.FOLLOWERS)
        viewModel.loadMore()

        val users = viewModel.state.value.users
        assertEquals(2, users.size)
        assertEquals("a", users[0].login)
        assertEquals("b", users[1].login)
        assertFalse(viewModel.state.value.hasNextPage)
    }

    @Test
    fun `加载失败时loadFailed为true`() = runTest {
        val repo = mockk<UserRepository>()
        coEvery { repo.getFollowing("octocat", null) } returns ApiResult.Failure(ApiError.NetworkError)

        val viewModel = UserListViewModel(repo, mockk<RepoDetailRepository>(relaxed = true), ErrorEventBus())
        viewModel.init("octocat", UserListMode.FOLLOWING)

        assertEquals(true, viewModel.state.value.loadFailed)
    }
}
