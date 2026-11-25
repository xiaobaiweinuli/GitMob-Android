package com.gitmob.app.ui.common

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.imePadding
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.gitmob.app.data.model.ConversationEdit
import com.gitmob.app.data.model.IssueFormField
import com.gitmob.app.ui.repoissues.IssueFormFieldContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoraEmbeddedViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oversizedEmbeddedViewerReachesMiddleEndAndFollowingPageContent() {
        val text = "x\n".repeat(300_000)
        lateinit var root: View
        lateinit var page: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            root = LocalView.current
            page = rememberLazyListState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) {
                MaterialTheme {
                    LazyColumn(state = page, modifier = Modifier.fillMaxSize()) {
                        item { Surface { Column {
                            Text("FILE HEADER")
                            SoraTextViewer(text, true, rememberSoraViewerState("oversized"), embedded = true)
                        } } }
                        item { Text("AFTER HUGE DOCUMENT") }
                    }
                }
            }
        }
        val viewer = compose.awaitSoraViewer(root)
        val pageHeight = compose.runOnIdle {
            assertTrue(viewer.layout.layoutHeight > 16_777_215)
            assertTrue(viewer.height <= root.height)
            page.layoutInfo.visibleItemsInfo.first().size.also {
                assertTrue(it < 16_777_215)
                scope.launch { page.scrollToItem(0, it / 2) }
            }
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertTrue("Middle rows must remain reachable: "+viewer.firstVisibleLine, viewer.firstVisibleLine in 140_000..160_000)
            scope.launch { page.scrollToItem(0, pageHeight - root.height) }
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertEquals(300_000, viewer.lastVisibleLine)
            scope.launch { page.scrollToItem(1) }
        }
        compose.onNodeWithText("AFTER HUGE DOCUMENT").assertIsDisplayed()
    }

    @Test fun oversizedEmbeddedFieldRevealsCaretAndAllowsEditingAtTheEnd() {
        val state = SoraEditorState("x\n".repeat(300_000) + "END")
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(4f, 1f)) {
                MaterialTheme { LazyColumn(Modifier.fillMaxSize()) {
                    item { Text("TITLE") }
                    item { SoraTextField(state, enabled = true, showLineNumbers = true, label = { Text("Body") }) }
                    item { Text("AFTER BODY") }
                } }
            }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.runOnIdle {
            assertTrue(editor.layout.layoutHeight > 16_777_215)
            editor.requestFocus()
            editor.setSelection(150_000, 0)
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertTrue("Visible lines "+editor.firstVisibleLine+".."+editor.lastVisibleLine+", offset="+editor.offsetY+", page="+editor.embeddedState?.windowTop,
                editor.firstVisibleLine <= 150_000 && editor.lastVisibleLine >= 150_000)
            editor.setSelection(300_000, 3)
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertEquals(300_000, editor.lastVisibleLine)
            editor.onCreateInputConnection(EditorInfo())!!.commitText("!", 1)
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertTrue(state.text.endsWith("END!"))
            assertEquals(300_000, editor.lastVisibleLine)
            assertTrue(editor.height <= root.height)
        }
    }

    @Test fun issueTextareaKeepsFieldDecorationAndGrowsAsIMEAddsLines() {
        val state = SoraEditorState()
        lateinit var root: View
        lateinit var page: LazyListState
        compose.setContent {
            root = LocalView.current
            page = rememberLazyListState()
            MaterialTheme {
                LazyColumn(state = page, modifier = Modifier.fillMaxSize()) {
                    item {
                        IssueFormFieldContent(
                            IssueFormField.Textarea("body", "Steps", placeholder = "Describe the steps", required = true),
                            emptyMap(), "", emptySet(), {}, {}, {}, textareaState = state,
                        )
                    }
                    item { Text("AFTER FIELD") }
                }
            }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.onNodeWithText("Steps *", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Describe the steps").assertIsDisplayed()
        val initialHeight = compose.runOnIdle {
            assertTrue(editor.height >= editor.rowHeight * 4)
            assertFalse(editor.isHighlightCurrentLine)
            page.layoutInfo.visibleItemsInfo.first().size
        }
        compose.runOnIdle {
            editor.requestFocus()
            editor.onCreateInputConnection(EditorInfo())!!.commitText((1..20_000).joinToString("\n") { "step $it" }, 1)
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertEquals(20_000, state.content.lineCount)
            assertTrue(page.layoutInfo.visibleItemsInfo.first().size > initialHeight)
            assertTrue(editor.height <= root.height)
        }
        compose.awaitSoraLayout(editor)
        compose.runOnIdle {
            assertTrue("Caret visibility scrolls the outer form", page.firstVisibleItemScrollOffset > 0)
            assertEquals(19_999, editor.lastVisibleLine)
        }
    }

    @Test fun keyboardResizeRevealsTheUnchangedCaretInTheOuterPage() {
        val state = SoraEditorState((1..80).joinToString("\n") { "release note $it" })
        lateinit var root: View
        lateinit var page: LazyListState
        compose.setContent {
            root = LocalView.current
            page = rememberLazyListState()
            MaterialTheme {
                LazyColumn(state = page, modifier = Modifier.fillMaxSize().imePadding()) {
                    item { SoraTextEditor(state, embedded = true, showLineNumbers = false) }
                }
            }
        }
        val editor = compose.awaitSoraEditor(root)
        compose.runOnIdle {
            editor.requestFocus()
            editor.setSelection(45, 5)
        }
        compose.awaitSoraLayout(editor)
        val initialOffset = compose.runOnIdle { page.firstVisibleItemScrollOffset }
        val originalSelection = state.selection
        for (keyboardHeight in listOf(300, 420)) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build())
            }
            compose.awaitSoraLayout(editor)
            compose.runOnIdle {
                val location = IntArray(2).also(editor::getLocationInWindow)
                val caretBottom = location[1] + editor.layout.getCharLayoutOffset(45, 5)[0] - editor.offsetY
                val rootTop = IntArray(2).also(root::getLocationInWindow)[1]
                assertTrue("Caret $caretBottom must stay above keyboard ${rootTop + root.height - keyboardHeight}",
                    caretBottom <= rootTop + root.height - keyboardHeight)
                assertTrue(page.firstVisibleItemScrollOffset > initialOffset)
                assertEquals(originalSelection, state.selection)
            }
        }
    }

    @Test fun embeddedViewerUsesNativeWrappedHeightIncludingFontScale() {
        val text = (1..12).joinToString("\n") { "row $it " + "wrapped words ".repeat(15) }
        lateinit var root: View
        lateinit var page: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            root = LocalView.current
            page = rememberLazyListState()
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                MaterialTheme {
                    LazyColumn(state = page, modifier = Modifier.fillMaxSize()) {
                        item { SoraTextViewer(text, true, rememberSoraViewerState("wrapped"), embedded = true) }
                        item { Text("AFTER WRAPPED TEXT") }
                    }
                }
            }
        }
        val viewer = compose.awaitSoraViewer(root)
        compose.runOnIdle {
            assertTrue(viewer.layout.rowCount > viewer.text.lineCount)
            assertEquals(viewer.layout.layoutHeight, page.layoutInfo.visibleItemsInfo.first().size)
            assertTrue(viewer.height <= root.height)
            scope.launch { page.scrollToItem(1) }
        }
        compose.onNodeWithText("AFTER WRAPPED TEXT").assertIsDisplayed()
    }
    @Test fun embeddedViewerKeepsLogicalReadingPositionWhenWidthChanges() = assertEmbeddedReflowPosition(editable = false)

    @Test fun embeddedEditorKeepsLogicalReadingPositionWhenWidthChanges() = assertEmbeddedReflowPosition(editable = true)

    private fun assertEmbeddedReflowPosition(editable: Boolean) {
        val text = (1..200).joinToString("\n") { "row $it " + "wrapped words ".repeat(15) }
        val narrow = androidx.compose.runtime.mutableStateOf(false)
        lateinit var root: View
        lateinit var page: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            root = LocalView.current
            page = rememberLazyListState()
            scope = rememberCoroutineScope()
            MaterialTheme {
                LazyColumn(state = page, modifier = Modifier.fillMaxHeight().fillMaxWidth(if (narrow.value) 0.55f else 1f)) {
                    item {
                        if (editable) SoraTextEditor(androidx.compose.runtime.remember { SoraEditorState(text) }, embedded = true)
                        else SoraTextViewer(text, true, rememberSoraViewerState("reflow"), embedded = true)
                    }
                }
            }
        }
        val viewer = if (editable) compose.awaitSoraEditor(root) else compose.awaitSoraViewer(root)
        compose.runOnIdle { scope.launch { page.scrollToItem(0, viewer.layout.layoutHeight / 3) } }
        compose.awaitSoraLayout(viewer)
        val before = compose.runOnIdle { viewer.firstVisibleLine.also { narrow.value = true } }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertEquals("Narrower layout must keep the visible logical line", before, viewer.firstVisibleLine)
            narrow.value = false
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertEquals("Wider layout must keep the visible logical line", before, viewer.firstVisibleLine)
        }
    }

    @Test fun unwrappedEmbeddedSourceStillScrollsHorizontally() {
        lateinit var root: View
        lateinit var page: LazyListState
        compose.setContent {
            root = LocalView.current
            page = rememberLazyListState()
            MaterialTheme {
                LazyColumn(state = page, modifier = Modifier.fillMaxSize()) {
                    item { SoraTextViewer("long source ".repeat(200), false, rememberSoraViewerState("wide"),
                        modifier = Modifier.testTag("source"), embedded = true) }
                }
            }
        }
        val viewer = compose.awaitSoraViewer(root)
        compose.onNodeWithTag("source").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            // Robolectric does not advance the Android scroller with the Compose test clock.
            assertTrue("Horizontal gesture reaches Sora's native scroller", viewer.scroller.finalX > 0)
            viewer.scroller.abortAnimation()
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertTrue("x=${viewer.offsetX}, max=${viewer.scrollMaxX}, size=${viewer.width}x${viewer.height}", viewer.offsetX > 0)
            assertEquals(0, page.firstVisibleItemScrollOffset)
        }
    }

    @Test fun embeddedMarkdownPreviewUsesContentHeightInsteadOfASecondScrollViewport() {
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                LazyColumn {
                    item {
                        MarkdownBodyEditor(
                            SoraEditorState("# Notes"),
                            MarkdownEditorUiState(selectedTab = MarkdownEditorTab.PREVIEW, previewHtml = "<h1>Notes</h1>"),
                            {}, embedded = true, minLines = 10,
                        )
                    }
                }
            }
        }
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.runOnIdle {
            val preview = requireNotNull(find(root))
            assertTrue(preview is NoVerticalScrollWebView)
            assertFalse(preview.isVerticalScrollBarEnabled)
        }
    }

    @Test fun editHistoryUsesRemainingSheetHeightAndCanReachTheFinalLine() {
        compose.setContent {
            MaterialTheme {
                ConversationEditHistorySheet(
                    edits = emptyList(), isLoading = false, isLoadingMore = false,
                    loadFailed = false, hasNextPage = false,
                    selectedEdit = ConversationEdit("edit", "2026-11-25", diff = (1..200).joinToString("\n") { "+line $it" }),
                    onDismiss = {}, onLoadMore = {}, onRetry = {}, onSelect = {}, onClearSelected = {},
                )
            }
        }
        val root = compose.runOnIdle { requireNotNull(ShadowDialog.getLatestDialog()).window!!.decorView }
        val viewer = compose.awaitSoraViewer(root)
        compose.runOnIdle {
            assertEquals(android.graphics.Paint.Align.LEFT, viewer.lineNumberAlign)
            assertNull(viewer.embeddedState)
            assertTrue("History fills the sheet below its header", viewer.height > root.height * 0.6f)
            viewer.ensurePositionVisible(199, 0, true)
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle { assertEquals(199, viewer.lastVisibleLine) }
    }

}
