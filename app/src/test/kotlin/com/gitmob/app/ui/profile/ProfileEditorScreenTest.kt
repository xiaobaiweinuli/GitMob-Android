package com.gitmob.app.ui.profile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.error.ApiError
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.data.model.AuthenticatedUser
import com.gitmob.app.data.model.EditableProfile
import com.gitmob.app.data.model.ProfileDraft
import com.gitmob.app.data.model.ProfileSaveResult
import com.gitmob.app.data.repository.UserRepository
import com.gitmob.app.testutil.AccountSessionRule
import com.gitmob.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h900dp")
class ProfileEditorScreenTest {
    private val accounts = AccountSessionRule()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(MainDispatcherRule()).around(accounts).around(composeRule)

    private val original = EditableProfile(
        AuthenticatedUser(1, "U_1", "viewer", "Original", null),
        ProfileDraft("Original", "Original bio", "Original company", "Original location", "https://example.test", "old@example.test", "old_account"),
    )
    private val fieldLabels = listOf(R.string.profile_name, R.string.profile_bio, R.string.profile_company,
        R.string.profile_location, R.string.profile_blog, R.string.profile_email, R.string.profile_twitter)
    private fun text(resource: Int) = ApplicationProvider.getApplicationContext<Context>().getString(resource)
    private fun field(resource: Int) = composeRule.onNodeWithContentDescription(text(resource))
    private fun hireable() = composeRule.onNode(hasText(text(R.string.profile_hireable)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    private fun systemBack() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    private fun repository() = mockk<UserRepository>().also {
        coEvery { it.getEditableProfile() } returns ApiResult.Success(original)
    }
    private fun setContent(vm: ProfileEditorViewModel, onBack: () -> Unit = {}) {
        composeRule.setContent { MaterialTheme { ProfileEditorScreen(onBack, vm) } }
    }

    @Test
    fun `独立编辑页显示只读身份七个文本字段和可保存的工作机会开关`() {
        val vm = ProfileEditorViewModel(repository(), ErrorEventBus(), accounts.session)
        setContent(vm)

        composeRule.onNodeWithText(text(R.string.profile_edit_title)).assertIsDisplayed()
        composeRule.onNodeWithText("@viewer").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.profile_readonly_note)).assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(7)
        fieldLabels.forEach { field(it).assertExists() }
        composeRule.onNodeWithText(text(R.string.common_save)).assertIsNotEnabled()
        hireable().performScrollTo().assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithText(text(R.string.common_save)).assertIsEnabled()
        assertEquals(original.values.copy(hireable = true), vm.state.value.draft)
    }

    @Test
    fun `未修改直接返回而未保存的顶部和系统返回都确认并保留或放弃输入`() {
        val repository = repository()
        val vm = ProfileEditorViewModel(repository, ErrorEventBus(), accounts.session)
        var returns = 0
        setContent(vm) { returns++ }
        composeRule.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        assertEquals(1, returns)

        field(R.string.profile_name).performTextReplacement("  draft input  ")
        composeRule.onNodeWithContentDescription(text(R.string.common_back)).performClick()
        composeRule.onNodeWithText(text(R.string.profile_discard_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.profile_keep_editing)).assertIsFocused()
        assertEquals(1, returns)
        composeRule.onNodeWithText(text(R.string.profile_keep_editing)).performClick()
        composeRule.onNodeWithText(text(R.string.profile_discard_title)).assertDoesNotExist()
        assertEquals("  draft input  ", vm.state.value.draft.name)

        systemBack()
        composeRule.onNodeWithText(text(R.string.profile_discard_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.profile_keep_editing)).assertIsFocused()
        composeRule.onNodeWithText(text(R.string.profile_discard)).performClick()
        assertEquals(2, returns)
        coVerify(exactly = 0) { repository.saveEditableProfile(any(), any()) }
    }

    @Test
    fun `保存中冻结表单和返回失败保留输入可重试成功采用服务端基线`() {
        val repository = repository()
        val response = CompletableDeferred<ApiResult<ProfileSaveResult>>()
        coEvery { repository.saveEditableProfile(any(), any()) } coAnswers { response.await() }
        val vm = ProfileEditorViewModel(repository, ErrorEventBus(), accounts.session)
        var returns = 0
        try {
            setContent(vm) { returns++ }
            field(R.string.profile_name).performTextReplacement("Draft")
            val draft = original.values.copy(name = "Draft")
            composeRule.onNodeWithText(text(R.string.common_save)).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.isSaving }
            fieldLabels.forEach { field(it).assertIsNotEnabled() }
            composeRule.onNodeWithContentDescription(text(R.string.common_back)).assertIsNotEnabled()
            composeRule.onNode(hasText(text(R.string.profile_saving)) and hasClickAction()).assertIsNotEnabled()
            hireable().performScrollTo().assertIsNotEnabled()
            systemBack()
            composeRule.onNodeWithText(text(R.string.profile_discard_title)).assertDoesNotExist()
            assertEquals(0, returns)
            coVerify(exactly = 1) { repository.saveEditableProfile(original, draft) }

            response.complete(ApiResult.Failure(ApiError.Forbidden))
            composeRule.waitUntil(timeoutMillis = 5_000) { !vm.state.value.isSaving }
            assertEquals(draft, vm.state.value.draft)
            val saved = original.copy(user = original.user.copy(name = "Server"), values = draft.copy(name = "Server"))
            coEvery { repository.saveEditableProfile(original, draft) } returns ApiResult.Success(ProfileSaveResult(saved))
            composeRule.onNodeWithText(text(R.string.common_retry)).performScrollTo().assertIsEnabled().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.saveStatusRes == R.string.profile_saved }
            composeRule.onNodeWithText(text(R.string.profile_saved)).performScrollTo().assertIsDisplayed()
            assertEquals(saved, vm.state.value.original)
            assertEquals("Server", field(R.string.profile_name).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            composeRule.onNodeWithText(text(R.string.common_save)).assertIsNotEnabled()
            composeRule.onNodeWithContentDescription(text(R.string.common_back)).performClick()
            assertEquals(1, returns)
            composeRule.onNodeWithText(text(R.string.profile_discard_title)).assertDoesNotExist()
            coVerify(exactly = 2) { repository.saveEditableProfile(original, draft) }
        } finally {
            response.complete(ApiResult.Failure(ApiError.NetworkError))
        }
    }

    @Test
    fun `保存无效资料显示字段错误并聚焦首个错误且不发送请求`() {
        val repository = repository()
        val vm = ProfileEditorViewModel(repository, ErrorEventBus(), accounts.session)
        setContent(vm)
        field(R.string.profile_email).performScrollTo().performTextReplacement("broken")
        composeRule.onNodeWithText(text(R.string.common_save)).performClick()

        composeRule.onNodeWithText(text(R.string.profile_email_invalid)).assertIsDisplayed()
        field(R.string.profile_email).assertIsFocused()
        coVerify(exactly = 0) { repository.saveEditableProfile(any(), any()) }
    }
}
