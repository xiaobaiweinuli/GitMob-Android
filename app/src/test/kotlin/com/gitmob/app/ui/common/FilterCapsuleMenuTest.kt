package com.gitmob.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gitmob.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterCapsuleMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private enum class TestFilter(@StringRes val labelRes: Int) {
        ALL(R.string.common_all),
        OPEN(R.string.work_filter_open),
        CLOSED(R.string.common_state_closed),
    }

    @Test
    fun `胶囊只显示当前选中值的文案`() {
        composeRule.setContent {
            FilterCapsuleMenu(
                selected = TestFilter.OPEN,
                options = TestFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = {},
                filterLabel = stringResource(R.string.work_filter_state),
            )
        }
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onAllNodesWithText("All").assertCountEquals(0)
        composeRule.onAllNodesWithText("Closed").assertCountEquals(0)
    }

    @Test
    fun `点击胶囊弹出全部选项`() {
        composeRule.setContent {
            FilterCapsuleMenu(
                selected = TestFilter.OPEN,
                options = TestFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = {},
                filterLabel = stringResource(R.string.work_filter_state),
            )
        }
        composeRule.onNodeWithContentDescription("Select State, current Open").performClick()
        // 菜单弹出：全部 + 打开（选中）+ 关闭；"Open" 同时出现在胶囊和菜单项
        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithText("Closed").assertIsDisplayed()
        composeRule.onAllNodesWithText("Open").assertCountEquals(2)
    }

    @Test
    fun `选择选项时回调收到该值`() {
        var result: TestFilter? = null
        composeRule.setContent {
            FilterCapsuleMenu(
                selected = TestFilter.OPEN,
                options = TestFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = { result = it },
                filterLabel = stringResource(R.string.work_filter_state),
            )
        }
        composeRule.onNodeWithContentDescription("Select State, current Open").performClick()
        composeRule.onNodeWithText("Closed").performClick()
        assertEquals(TestFilter.CLOSED, result)
    }

    @Test
    fun `未筛选时显示维度名且无障碍保留真实值`() {
        composeRule.setContent {
            FilterCapsuleMenu(
                selected = TestFilter.ALL,
                options = TestFilter.entries,
                optionLabel = { stringResource(it.labelRes) },
                onSelected = {},
                filterLabel = stringResource(R.string.work_filter_state),
                neutralLabel = stringResource(R.string.work_filter_state),
                isNeutral = { it == TestFilter.ALL },
            )
        }

        composeRule.onNodeWithText("State").assertIsDisplayed()
        composeRule.onAllNodesWithText("All").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Select State, current All").assertIsDisplayed()
    }

    @Test
    fun `多选胶囊未选时显示空标签`() {
        composeRule.setContent {
            FilterMultiCapsuleMenu(
                selected = emptySet(),
                options = listOf("bug", "enhancement"),
                emptyLabel = stringResource(R.string.common_all),
                selectedCountRes = R.string.issue_filter_selected_count,
                clearLabel = stringResource(R.string.issue_filter_clear_labels),
                onSelect = {},
                filterLabel = stringResource(R.string.issue_labels),
            )
        }
        composeRule.onNodeWithText("Labels").assertIsDisplayed()
        composeRule.onAllNodesWithText("All").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Select Labels, current All").assertIsDisplayed()
    }

    @Test
    fun `多选胶囊已选显示数量并可勾选回调集合`() {
        var result: Set<String>? = null
        composeRule.setContent {
            FilterMultiCapsuleMenu(
                selected = setOf("bug"),
                options = listOf("bug", "enhancement"),
                emptyLabel = stringResource(R.string.common_all),
                selectedCountRes = R.string.issue_filter_selected_count,
                clearLabel = stringResource(R.string.issue_filter_clear_labels),
                onSelect = { result = it },
                filterLabel = stringResource(R.string.issue_labels),
            )
        }
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Select Labels, current 1 selected").performClick()
        composeRule.onNodeWithText("Clear labels").assertIsDisplayed()
        composeRule.onNodeWithText("enhancement").performClick()
        assertEquals(setOf("bug", "enhancement"), result)
    }

    @Test
    fun `多选标签后菜单保持打开可连续选择`() {
        var selected by mutableStateOf(emptySet<String>())
        composeRule.setContent {
            FilterMultiCapsuleMenu(
                selected = selected,
                options = listOf("bug", "enhancement"),
                emptyLabel = stringResource(R.string.common_all),
                selectedCountRes = R.string.issue_filter_selected_count,
                clearLabel = stringResource(R.string.issue_filter_clear_labels),
                onSelect = { selected = it },
                filterLabel = stringResource(R.string.issue_labels),
            )
        }

        composeRule.onNodeWithContentDescription("Select Labels, current All").performClick()
        composeRule.onNodeWithText("bug").performClick()
        composeRule.onNodeWithText("enhancement").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Clear labels").assertIsDisplayed()
        assertEquals(setOf("bug", "enhancement"), selected)
    }

    @Test
    fun `多选胶囊清除回调清空集合`() {
        var result: Set<String>? = null
        composeRule.setContent {
            FilterMultiCapsuleMenu(
                selected = setOf("bug"),
                options = listOf("bug", "enhancement"),
                emptyLabel = stringResource(R.string.common_all),
                selectedCountRes = R.string.issue_filter_selected_count,
                clearLabel = stringResource(R.string.issue_filter_clear_labels),
                onSelect = { result = it },
                filterLabel = stringResource(R.string.issue_labels),
            )
        }
        composeRule.onNodeWithContentDescription("Select Labels, current 1 selected").performClick()
        composeRule.onNodeWithText("Clear labels").performClick()
        assertEquals(emptySet<String>(), result)
    }
}
