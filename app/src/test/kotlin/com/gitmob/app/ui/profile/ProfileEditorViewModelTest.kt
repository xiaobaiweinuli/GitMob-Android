package com.gitmob.app.ui.profile

import androidx.lifecycle.viewModelScope
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.BannerEvent
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.EditableProfile
import com.gitmob.app.data.model.GitHubAccount
import com.gitmob.app.data.model.ProfileDraft
import com.gitmob.app.data.model.ProfileField
import com.gitmob.app.data.model.ProfileSaveResult
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorViewModelTest {
    @get:Rule val accounts = AccountSessionRule()
    @get:Rule val main = MainDispatcherRule()

    private val original = EditableProfile(AuthenticatedUser(1, "U_1", "viewer", "Original", null), ProfileDraft(name = "Original"))

    @Test
    fun `加载失败可重试且资料成功后不重复加载覆盖草稿`() = runTest {
        val repository = mockk<UserRepository>()
        coEvery { repository.getEditableProfile() } returnsMany listOf(ApiResult.Failure(ApiError.NetworkError), ApiResult.Success(original))
        val vm = ProfileEditorViewModel(repository, ErrorEventBus(), accounts.session)
        try {
            assertTrue(vm.state.value.loadFailed)
            assertFalse(vm.state.value.isLoading)
            assertFalse(vm.state.value.canSave)
            vm.update(ProfileField.NAME, "ignored")
            assertEquals(ProfileDraft(), vm.state.value.draft)

            vm.load()
            vm.update(ProfileField.NAME, "  keep exact input  ")
            vm.load()

            assertEquals(original, vm.state.value.original)
            assertEquals("  keep exact input  ", vm.state.value.draft.name)
            assertTrue(vm.state.value.canSave)
            coVerify(exactly = 2) { repository.getEditableProfile() }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `校验按字段触发且保存定位首个错误不发请求`() = runTest {
        val repository = mockk<UserRepository>()
        coEvery { repository.getEditableProfile() } returns ApiResult.Success(original)
        val vm = ProfileEditorViewModel(repository, ErrorEventBus(), accounts.session)
        try {
            vm.save()
            coVerify(exactly = 0) { repository.saveEditableProfile(any(), any()) }
            vm.update(ProfileField.BIO, "😀".repeat(161))
            vm.update(ProfileField.BLOG, "not a url")
            vm.update(ProfileField.EMAIL, "broken")
            vm.update(ProfileField.TWITTER_USERNAME, "@invalid")
            assertTrue(vm.state.value.errors.isEmpty())
            vm.validate(ProfileField.EMAIL)
            assertEquals(mapOf(ProfileField.EMAIL to R.string.profile_email_invalid), vm.state.value.errors)

            vm.save()

            assertEquals(ProfileField.BIO, vm.state.value.focusField)
            assertEquals(1, vm.state.value.focusRequest)
            assertEquals(setOf(ProfileField.BIO, ProfileField.BLOG, ProfileField.EMAIL, ProfileField.TWITTER_USERNAME), vm.state.value.errors.keys)
            vm.save()
            assertEquals(2, vm.state.value.focusRequest)
            coVerify(exactly = 0) { repository.saveEditableProfile(any(), any()) }
            vm.update(ProfileField.BIO, "😀".repeat(160))
            assertNull(vm.state.value.errors[ProfileField.BIO])
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `保存期间冻结草稿防重复且失败保留输入重试采用服务器基线`() = runTest {
        val repository = mockk<UserRepository>()
        coEvery { repository.getEditableProfile() } returns ApiResult.Success(original)
        val response = CompletableDeferred<ApiResult<ProfileSaveResult>>()
        coEvery { repository.saveEditableProfile(any(), any()) } coAnswers { response.await() }
        val vm = ProfileEditorViewModel(repository, ErrorEventBus(), accounts.session)
        try {
            vm.update(ProfileField.NAME, "  draft  ")
            vm.setHireable(true)
            val draft = vm.state.value.draft
            vm.save()
            vm.save()
            vm.update(ProfileField.NAME, "ignored")
            vm.setHireable(false)
            assertTrue(vm.state.value.isSaving)
            assertFalse(vm.state.value.canSave)
            assertEquals(draft, vm.state.value.draft)
            coVerify(exactly = 1) { repository.saveEditableProfile(original, draft) }

            response.complete(ApiResult.Failure(ApiError.Forbidden))

            assertEquals(original, vm.state.value.original)
            assertEquals(draft, vm.state.value.draft)
            assertTrue(vm.state.value.canSave)
            assertNotNull(vm.state.value.saveErrorRes)
            val saved = original.copy(user = original.user.copy(name = "Server"), values = draft.copy(name = "Server"))
            coEvery { repository.saveEditableProfile(original, draft) } returns ApiResult.Success(ProfileSaveResult(saved))
            vm.save()
            runCurrent()
            assertEquals(saved, vm.state.value.original)
            assertEquals(saved.values, vm.state.value.draft)
            assertFalse(vm.state.value.hasChanges)
            assertFalse(vm.state.value.canSave)
            assertNull(vm.state.value.saveErrorRes)
            assertEquals(R.string.profile_saved, vm.state.value.saveStatusRes)
            vm.update(ProfileField.NAME, "Server changed")
            assertNull(vm.state.value.saveStatusRes)
            assertTrue(vm.state.value.hasChanges)
        } finally {
            response.complete(ApiResult.Failure(ApiError.NetworkError))
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `远端成功本地同步失败仍更新基线并单独提示`() = runTest {
        val repository = mockk<UserRepository>()
        coEvery { repository.getEditableProfile() } returns ApiResult.Success(original)
        val saved = original.copy(values = original.values.copy(name = "Saved"))
        coEvery { repository.saveEditableProfile(any(), any()) } returns ApiResult.Success(ProfileSaveResult(saved, localSyncFailed = true))
        val bus = ErrorEventBus()
        val events = mutableListOf<BannerEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            bus.eventsFor(accounts.session.epoch).collect { events += it }
        }
        val vm = ProfileEditorViewModel(repository, bus, accounts.session)
        try {
            vm.update(ProfileField.NAME, "Saved")
            vm.save()
            runCurrent()
            assertEquals(saved, vm.state.value.original)
            assertFalse(vm.state.value.hasChanges)
            assertNull(vm.state.value.saveErrorRes)
            assertEquals(R.string.profile_local_sync_failed, vm.state.value.saveStatusRes)
            assertEquals(listOf(BannerEvent.Notice(R.string.profile_local_sync_failed)), events)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `换账号后迟到资料或保存结果不能回填或发新账号提示`() = runTest {
        val repository = mockk<UserRepository>()
        val load = CompletableDeferred<ApiResult<EditableProfile>>()
        coEvery { repository.getEditableProfile() } coAnswers { withContext(NonCancellable) { load.await() } }
        val bus = ErrorEventBus()
        val loading = ProfileEditorViewModel(repository, bus, accounts.session)
        try {
            accounts.session.replace(accounts.session.state.value.accounts, "1")
            load.complete(ApiResult.Success(original))
            assertNull(loading.state.value.original)

            coEvery { repository.getEditableProfile() } returns ApiResult.Success(original)
            val save = CompletableDeferred<ApiResult<ProfileSaveResult>>()
            coEvery { repository.saveEditableProfile(any(), any()) } coAnswers { withContext(NonCancellable) { save.await() } }
            val saving = ProfileEditorViewModel(repository, bus, accounts.session)
            try {
                saving.update(ProfileField.NAME, "draft")
                saving.save()
                val second = GitHubAccount("2", 2, "U_2", "second")
                accounts.session.replace(listOf(second), second.key)
                val events = mutableListOf<BannerEvent>()
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    bus.eventsFor(accounts.session.epoch).collect { events += it }
                }
                save.complete(ApiResult.Success(ProfileSaveResult(original.copy(values = ProfileDraft(name = "late")))))
                runCurrent()
                assertEquals("draft", saving.state.value.draft.name)
                assertEquals(original, saving.state.value.original)
                assertNull(saving.state.value.saveStatusRes)
                assertTrue(events.isEmpty())
                saving.save()
                coVerify(exactly = 1) { repository.saveEditableProfile(any(), any()) }
            } finally {
                save.complete(ApiResult.Failure(ApiError.NetworkError))
                saving.viewModelScope.cancel()
            }
        } finally {
            load.complete(ApiResult.Success(original))
            loading.viewModelScope.cancel()
        }
    }
}
