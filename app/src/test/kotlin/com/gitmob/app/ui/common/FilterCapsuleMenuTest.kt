package com.gitmob.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.ui.theme.GitMobTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilterCapsuleMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun text(@StringRes resource: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resource, *args)

    private fun description(@StringRes filter: Int, selected: String) =
        text(R.string.work_select_filter_value, text(filter), selected)

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
        composeRule.onNodeWithText(text(R.string.work_filter_open)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.common_all)).assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.common_state_closed)).assertCountEquals(0)
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
        composeRule.onNodeWithContentDescription(description(R.string.work_filter_state, text(R.string.work_filter_open))).performClick()
        composeRule.onNodeWithText(text(R.string.common_all)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.common_state_closed)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.work_filter_open)).assertCountEquals(2)
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
        composeRule.onNodeWithContentDescription(description(R.string.work_filter_state, text(R.string.work_filter_open))).performClick()
        composeRule.onNodeWithText(text(R.string.common_state_closed)).performClick()
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

        composeRule.onNodeWithText(text(R.string.work_filter_state)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.common_all)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(description(R.string.work_filter_state, text(R.string.common_all))).assertIsDisplayed()
    }

    @Test
    fun `空选中值允许不在菜单中且重置后恢复中性胶囊`() {
        var selected by mutableStateOf<Boolean?>(null)
        composeRule.setContent {
            FilterCapsuleMenu(
                selected = selected,
                options = listOf(false, true),
                optionLabel = {
                    stringResource(when (it) {
                        null -> R.string.common_all
                        false -> R.string.gist_category_original
                        true -> R.string.repo_fork
                    })
                },
                onSelected = { selected = it },
                filterLabel = stringResource(R.string.repo_fork),
                neutralLabel = stringResource(R.string.repo_fork),
                isNeutral = { it == null },
            )
        }

        composeRule.onNodeWithContentDescription(description(R.string.repo_fork, text(R.string.common_all))).assertIsNotSelected().performClick()
        assertEquals(null, selected)
        composeRule.onAllNodesWithText(text(R.string.common_all)).assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.repo_fork)).assertCountEquals(2)
        composeRule.onNodeWithText(text(R.string.gist_category_original)).assertIsDisplayed().performClick()
        assertEquals(false, selected)
        composeRule.onNodeWithContentDescription(description(R.string.repo_fork, text(R.string.gist_category_original))).assertIsDisplayed().assertIsSelected()

        composeRule.runOnIdle { selected = null }
        composeRule.onNodeWithContentDescription(description(R.string.repo_fork, text(R.string.common_all))).assertIsDisplayed().assertIsNotSelected()
        composeRule.onAllNodesWithText(text(R.string.gist_category_original)).assertCountEquals(0)
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
        composeRule.onNodeWithText(text(R.string.issue_labels)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.common_all)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.common_all))).assertIsDisplayed()
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
        composeRule.onNodeWithText(text(R.string.issue_filter_selected_count, 1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.issue_filter_selected_count, 1))).performClick()
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).assertIsDisplayed()
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

        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.common_all))).assertIsNotSelected().performClick()
        composeRule.onNodeWithText("bug").performClick()
        composeRule.onNodeWithText("enhancement").assertIsDisplayed().performClick()
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).assertIsDisplayed()
        assertEquals(setOf("bug", "enhancement"), selected)
        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.issue_filter_selected_count, 2))).assertIsSelected()
        composeRule.onNodeWithText("bug").performClick()
        assertEquals(setOf("enhancement"), selected)
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).performClick()
        composeRule.onNodeWithText("enhancement").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.common_all))).assertIsNotSelected()
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
        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.issue_filter_selected_count, 1))).performClick()
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).performClick()
        assertEquals(emptySet<String>(), result)
    }
    @Test
    fun longCustomLabelsWrapAndMenuContentStillSelects() {
        val label = "A long repository milestone label ".repeat(4)
        var selected by mutableStateOf("first")
        composeRule.setContent {
            GitMobTheme {
                FilterCapsuleMenu(
                    selected = selected,
                    options = listOf("first", "second"),
                    optionLabel = { it },
                    onSelected = { selected = it },
                    filterLabel = "Milestone",
                    selectedContent = { Text(label) },
                    optionContent = { Text("Option " + it) },
                )
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertTrue(!layouts.single().hasVisualOverflow)
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithText("Option second").performClick()
        assertEquals("second", selected)
        composeRule.onNodeWithText("Option first").assertDoesNotExist()
    }

    @Test
    fun longMultiSelectMenuScrollsAndKeepsSelectionUntilCleared() {
        val options = List(40) { "label-$it" }
        var selected by mutableStateOf(emptySet<String>())
        composeRule.setContent {
            GitMobTheme {
                FilterMultiCapsuleMenu(
                    selected = selected,
                    options = options,
                    emptyLabel = stringResource(R.string.common_all),
                    selectedCountRes = R.string.issue_filter_selected_count,
                    clearLabel = stringResource(R.string.issue_filter_clear_labels),
                    onSelect = { selected = it },
                    filterLabel = stringResource(R.string.issue_labels),
                )
            }
        }

        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.common_all))).performClick()
        composeRule.onNodeWithText(options.last()).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(setOf(options.last()), selected)
        composeRule.onNodeWithText(options.last()).assertIsDisplayed()
        composeRule.onNodeWithText(options.first()).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(setOf(options.first(), options.last()), selected)
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).performScrollTo().performClick()
        assertEquals(emptySet<String>(), selected)
        composeRule.onNodeWithText(options.first()).assertDoesNotExist()
    }

    @Test
    fun multiSelectMenuWithoutOptionsCanClearExistingSelection() {
        var selected by mutableStateOf(setOf("removed-label"))
        composeRule.setContent {
            GitMobTheme {
                FilterMultiCapsuleMenu(
                    selected = selected,
                    options = emptyList(),
                    emptyLabel = stringResource(R.string.common_all),
                    selectedCountRes = R.string.issue_filter_selected_count,
                    clearLabel = stringResource(R.string.issue_filter_clear_labels),
                    onSelect = { selected = it },
                    filterLabel = stringResource(R.string.issue_labels),
                )
            }
        }

        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.issue_filter_selected_count, 1))).performClick()
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).assertIsDisplayed().performClick()
        assertEquals(emptySet<String>(), selected)
        composeRule.onNodeWithText(text(R.string.issue_filter_clear_labels)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(description(R.string.issue_labels, text(R.string.common_all))).assertIsNotSelected()
    }

}
