package com.gitmob.app.ui.repos

import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.core.event.RepoUpdateEventBus
import com.gitmob.app.data.model.CreatedRepository
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerPage
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import com.gitmob.app.data.model.RepositoryLicense
import com.gitmob.app.data.repository.RepoRepository
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class RepoCreateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = owner("u1", "octocat", RepositoryCreateOwnerType.USER)

    @Test
    fun `initialize is idempotent and loads the default owner`() = runTest {
        val repository = mockk<RepoRepository>()
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage())
        val viewModel = createViewModel(repository)

        viewModel.initialize(user)
        viewModel.updateName("draft")
        viewModel.initialize(owner("u2", "other", RepositoryCreateOwnerType.USER))

        assertEquals(user, viewModel.state.value.owner)
        assertEquals("draft", viewModel.state.value.name)
        assertEquals(null, viewModel.state.value.activePicker)
        coVerify(exactly = 1) { repository.getRepositoryCreateOwners(null) }
    }

    @Test
    fun `organization default remains visible when absent from the first owner page`() = runTest {
        val organization = owner("o-default", "acme", RepositoryCreateOwnerType.ORGANIZATION)
        val repository = mockk<RepoRepository>()
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage())
        val viewModel = createViewModel(repository)

        viewModel.initialize(organization)

        assertEquals(organization, viewModel.state.value.owner)
        assertEquals(listOf("u1", "o-default"), viewModel.state.value.owners.map { it.id })
    }

    @Test
    fun `picker cancellation does not write draft and confirmation writes once`() = runTest {
        val organization = owner("o1", "acme", RepositoryCreateOwnerType.ORGANIZATION)
        val license = RepositoryLicense("mit", "MIT License")
        val repository = mockk<RepoRepository>(relaxed = true)
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage(listOf(organization)))
        coEvery { repository.getGitignoreTemplates() } returns ApiResult.Success(listOf("Kotlin"))
        val viewModel = createViewModel(repository)

        viewModel.initialize(user)
        viewModel.openOwnerPicker()
        viewModel.selectOwner(organization)
        assertEquals(RepoCreatePicker.OWNER, viewModel.state.value.activePicker)
        viewModel.cancelPicker()
        assertEquals(null, viewModel.state.value.activePicker)
        assertEquals(user, viewModel.state.value.owner)

        viewModel.openOwnerPicker()
        viewModel.selectOwner(organization)
        viewModel.confirmOwner()
        assertEquals(organization, viewModel.state.value.owner)

        viewModel.openLicensePicker()
        viewModel.selectLicense(license)
        viewModel.cancelPicker()
        assertEquals(null, viewModel.state.value.license)
        viewModel.openLicensePicker()
        viewModel.selectLicense(license)
        viewModel.confirmLicense()
        assertEquals(license, viewModel.state.value.license)

        viewModel.openGitignorePicker()
        viewModel.selectGitignore("Kotlin")
        viewModel.cancelPicker()
        assertEquals(null, viewModel.state.value.gitignore)
        viewModel.openGitignorePicker()
        viewModel.selectGitignore("Kotlin")
        viewModel.confirmGitignore()
        assertEquals("Kotlin", viewModel.state.value.gitignore)
    }

    @Test
    fun `owner pages merge and deduplicate by id`() = runTest {
        val acme = owner("o1", "acme", RepositoryCreateOwnerType.ORGANIZATION)
        val beta = owner("o2", "beta", RepositoryCreateOwnerType.ORGANIZATION)
        val repository = mockk<RepoRepository>()
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(
            ownerPage(listOf(acme), hasNextPage = true, endCursor = "cursor-1"),
        )
        coEvery { repository.getRepositoryCreateOwners("cursor-1") } returns ApiResult.Success(
            ownerPage(listOf(acme, beta)),
        )
        val viewModel = createViewModel(repository)

        viewModel.initialize(user)
        viewModel.loadMoreOwners()

        assertEquals(listOf("u1", "o1", "o2"), viewModel.state.value.owners.map { it.id })
        assertFalse(viewModel.state.value.ownersHasNextPage)
        coVerify(exactly = 1) { repository.getRepositoryCreateOwners("cursor-1") }
    }

    @Test
    fun `create emits event and keeps form state for navigation`() = runTest {
        val repository = mockk<RepoRepository>()
        val userRepository = mockk<UserRepository>(relaxed = true)
        val created = CreatedRepository("octocat", "new-repo")
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage())
        coEvery { repository.createRepository(any()) } returns ApiResult.Success(created)
        val viewModel = RepoCreateViewModel(repository, userRepository, RepoUpdateEventBus(), ErrorEventBus())
        viewModel.initialize(user)
        viewModel.updateName("new-repo")

        viewModel.create()

        assertFalse(viewModel.state.value.isCreating)
        assertEquals(user, viewModel.state.value.owner)
        coVerify(exactly = 1) { repository.createRepository(any()) }
        coVerify(exactly = 1) { userRepository.invalidateAllCaches() }
    }

    @Test
    fun `create failure leaves form available for retry`() = runTest {
        val repository = mockk<RepoRepository>()
        coEvery { repository.getRepositoryCreateOwners(null) } returns ApiResult.Success(ownerPage())
        coEvery { repository.createRepository(any()) } returns ApiResult.Failure(ApiError.NetworkError)
        val viewModel = createViewModel(repository)
        viewModel.initialize(user)
        viewModel.updateName("new-repo")

        viewModel.create()

        assertEquals("new-repo", viewModel.state.value.name)
        assertFalse(viewModel.state.value.isCreating)
        assertEquals(null, viewModel.state.value.activePicker)
    }

    private fun createViewModel(repository: RepoRepository) = RepoCreateViewModel(
        repository,
        mockk<UserRepository>(relaxed = true),
        RepoUpdateEventBus(),
        ErrorEventBus(),
    )

    private fun owner(id: String, login: String, type: RepositoryCreateOwnerType) = RepositoryCreateOwner(
        id = id,
        login = login,
        name = login,
        avatarUrl = null,
        type = type,
        canCreateRepository = true,
    )

    private fun ownerPage(
        organizations: List<RepositoryCreateOwner> = emptyList(),
        hasNextPage: Boolean = false,
        endCursor: String? = null,
    ) = RepositoryCreateOwnerPage(
        viewer = user,
        organizations = organizations,
        hasNextPage = hasNextPage,
        endCursor = endCursor,
    )
}
