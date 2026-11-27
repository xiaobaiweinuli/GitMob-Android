package com.gitmob.app.ui.common

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.ui.repocode.RepoFileDetailUiState
import com.gitmob.app.ui.repocode.RepoFileMenu
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoFileContent
import com.gitmob.app.data.translation.TranslationConfiguration
import com.gitmob.app.data.translation.TranslationPreferences
import com.gitmob.app.data.translation.TranslationTarget
import com.gitmob.app.ui.translation.LocalTranslationController
import com.gitmob.app.ui.translation.TranslationController
import com.gitmob.app.ui.translation.TranslationUiState
import com.gitmob.app.ui.translation.TranslationViewModel
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoraTextViewerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoringViewerSelectionDoesNotTakeFocusFromAnotherField() {
        val mounted = mutableStateOf(false)
        val wrap = mutableStateOf(false)
        val focus = FocusRequester()
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            val reading = rememberSoraViewerState("focus")
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField("title", {}, modifier = Modifier.testTag("title").focusRequester(focus))
                    if (mounted.value) SoraTextViewer("source", wrap.value, reading, Modifier.weight(1f))
                }
            }
        }
        compose.runOnIdle { focus.requestFocus(); mounted.value = true }
        val viewer = compose.awaitSoraViewer(root)
        compose.onNodeWithTag("title").assertIsFocused()
        compose.runOnIdle { assertFalse(viewer.isFocused); wrap.value = true }
        compose.awaitSoraLayout(viewer)
        compose.onNodeWithTag("title").assertIsFocused()
    }

    @Test fun diffKeepsSingleGutterBackgroundsAndPatchTextOutOfTheGutter() {
        val patch = "diff --git a/a.txt b/a.txt\n@@ -10,2 +20,2 @@ section\n-old\n+new\n kept"
        val diff = requireNotNull(com.gitmob.app.core.diff.UnifiedDiffParser.parse(patch))
        val document = diff.toSoraDocument()
        assertEquals(patch, document.content.toString())
        assertEquals(listOf("", "", "10", "20", "21"), document.lineLabels)
        assertEquals(listOf(1, 2, 3), document.styles!!.lineStyles.map { it.line })
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme { UnifiedDiffViewer(diff, documentId = "a.txt") }
        }
        val viewer = compose.awaitSoraViewer(root)
        compose.runOnIdle {
            assertEquals(android.graphics.Paint.Align.LEFT, viewer.lineNumberAlign)
            assertFalse(viewer.isEditable)
            assertTrue(viewer.isWordwrap)
            assertEquals(patch, viewer.text.toString())
            assertNotNull(viewer.styles)
            assertFalse(viewer.isFirstLineNumberAlwaysVisible)
            assertEquals(viewer.otherPaint.measureText("00") + viewer.lineNumberMarginLeft, viewer.measureLineNumber(), 0.01f)
            viewer.selectAll()
            viewer.copyText()
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(patch, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun addedAndDeletedFilesKeepTheirOwnLineNumbers() {
        for ((header, prefix) in listOf("@@ -0,0 +1,12 @@" to "+", "@@ -1,12 +0,0 @@" to "-")) {
            val patch = header + "\n" + (1..12).joinToString("\n") { "${prefix}line $it" }
            val diff = requireNotNull(com.gitmob.app.core.diff.UnifiedDiffParser.parse(patch))
            val document = diff.toSoraDocument()
            assertEquals(listOf("") + (1..12).map(Int::toString), document.lineLabels)
            assertEquals(patch, document.content.toString())
        }
    }

    @Test fun largeRawPatchExpandsInsideThePageWithABoundedNativeWindow() {
        val patch = "@@ -0,0 +1,20000 @@\n" + (1..20_000).joinToString("\n") { "+line $it" }
        lateinit var root: View
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            root = LocalView.current
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            MaterialTheme {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("page")) {
                    item { UnifiedDiffViewer(patch = patch, documentId = "large.diff") }
                    item { Text("AFTER PATCH") }
                }
            }
        }
        val viewer = compose.awaitSoraViewer(root)
        compose.runOnIdle {
            assertEquals(20_001, viewer.text.lineCount)
            assertTrue(viewer.height <= root.height)
            assertTrue(list.layoutInfo.visibleItemsInfo.first().size >= 20_001 * viewer.rowHeight)
        }
        // Starting a vertical drag over Sora must scroll the page, not trap the gesture.
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertTrue(list.firstVisibleItemScrollOffset > 0)
            assertTrue(viewer.offsetY > 0)
            scope.launch { list.scrollToItem(0, 19_990 * viewer.rowHeight) }
        }
        compose.awaitSoraLayout(viewer)
        compose.runOnIdle {
            assertTrue("Native window follows the outer scroll near the end", viewer.lastVisibleLine >= 19_990)
            assertTrue(viewer.height <= root.height)
            scope.launch { list.scrollToItem(1) }
        }
        compose.onNodeWithText("AFTER PATCH").assertExists()
    }

    @Test fun largeDocumentKeepsABoundedViewportAndCanReachItsLastLine() {
        val text = (1..30_000).joinToString("\n") { "source row $it" } + "\nEND"
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                Box(Modifier.size(260.dp, 180.dp)) {
                    SoraTextViewer(text, false, rememberSoraViewerState("large"))
                }
            }
        }
        val editor = awaitEditor { root }
        compose.runOnIdle {
            assertFalse(editor.isEditable)
            assertTrue(editor.isLineNumberEnabled)
            assertTrue(editor.isLineNumberPinned)
            assertTrue(editor.isFirstLineNumberAlwaysVisible)
            assertEquals(30_001, editor.text.lineCount)
            assertTrue(editor.layout.rowCount.toLong() * editor.rowHeight > 537_111)
            assertTrue(editor.height < 2_000)
            editor.setSelection(30_000, 3)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            editor.scroller.abortAnimation()
            assertTrue(editor.firstVisibleLine > 29_000)
            assertEquals(text, editor.text.toString())
        }
    }

    @Test fun wrapPreservesOriginalSeparatorsAndCopiesOffscreenSelection() {
        val text = "\r" + (1..80).joinToString("\r\n") { "  \t中文 👋 source row $it " + "value ".repeat(12) } + "\r\n\r\n"
        val wrapped = mutableStateOf(false)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                Box(Modifier.size(260.dp, 180.dp)) {
                    SoraTextViewer(text, wrapped.value, rememberSoraViewerState("copy"))
                }
            }
        }
        val editor = awaitEditor { root }
        compose.runOnIdle {
            assertEquals(text, editor.text.toString())
            assertTrue(editor.scrollMaxX > 0)
            wrapped.value = true
        }
        awaitLayout(editor) { editor.layout.rowCount > editor.text.lineCount }
        compose.runOnIdle {
            assertSame(editor, findEditor(root))
            editor.selectAll()
            editor.copyText()
            assertEquals(text, editor.text.toString())
            val clipboard = editor.context.getSystemService(ClipboardManager::class.java)
            assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun singleLongLineWrapsWithoutAddingNewlinesToTheDocument() {
        val text = "abcdefghij".repeat(10_000)
        val wrapped = mutableStateOf(false)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                Box(Modifier.size(260.dp, 180.dp)) {
                    SoraTextViewer(text, wrapped.value, rememberSoraViewerState("long-line"))
                }
            }
        }
        val editor = awaitEditor { root }
        compose.runOnIdle {
            assertEquals(1, editor.text.lineCount)
            assertTrue(editor.scrollMaxX > editor.width)
            wrapped.value = true
        }
        awaitLayout(editor) { editor.layout.rowCount > 1_000 }
        compose.runOnIdle {
            assertEquals(1, editor.text.lineCount)
            assertEquals(text, editor.text.toString())
            wrapped.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, editor.layout.rowCount) }
    }

    @Test fun wrapAndSourcePreviewSwitchKeepLogicalAnchorAndSelection() {
        val text = (1..300).joinToString("\n") { "row $it " + "long_value ".repeat(25) }
        val shown = mutableStateOf(true)
        val wrapped = mutableStateOf(false)
        lateinit var root: View
        lateinit var state: SoraViewerState
        compose.setContent {
            root = LocalView.current
            state = rememberSoraViewerState("anchor")
            MaterialTheme {
                Box(Modifier.size(260.dp, 180.dp)) {
                    if (shown.value) SoraTextViewer(text, wrapped.value, state)
                }
            }
        }
        val first = awaitEditor { root }
        compose.runOnIdle {
            first.setSelectionRegion(100, 4, 102, 9, false)
            first.scroller.startScroll(90, 100 * first.rowHeight + first.rowHeight / 2, 0, 0, 0)
            first.scroller.abortAnimation()
            wrapped.value = true
        }
        awaitLayout(first) { first.layout.rowCount > first.text.lineCount }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(100, first.firstVisibleLine)
            assertEquals(100, first.cursor.leftLine)
            assertEquals(102, first.cursor.rightLine)
            shown.value = false
        }
        compose.runOnIdle { assertTrue(first.isReleased); shown.value = true }
        val restored = awaitEditor { root }
        awaitLayout(restored) { restored.firstVisibleLine == 100 }
        compose.runOnIdle {
            assertNotSame(first, restored)
            assertEquals(4, restored.cursor.leftColumn)
            assertEquals(9, restored.cursor.rightColumn)
            wrapped.value = false
        }
        awaitLayout(restored) { !restored.isWordwrap }
        compose.runOnIdle {
            assertEquals(100, restored.firstVisibleLine)
            assertEquals(90, restored.offsetX)
        }
    }

    @Test fun savedStateRestoresSelectionAndAnchorButNewDocumentStartsAtTop() {
        val restoration = StateRestorationTester(compose)
        val identity = mutableStateOf("blob-a")
        val text = (1..500).joinToString("\n") { "row $it" }
        lateinit var root: View
        restoration.setContent {
            root = LocalView.current
            MaterialTheme {
                Box(Modifier.size(260.dp, 180.dp)) {
                    SoraTextViewer(text, false, rememberSoraViewerState(identity.value))
                }
            }
        }
        val first = awaitEditor { root }
        compose.runOnIdle {
            first.setSelectionRegion(200, 1, 202, 3, false)
            first.scroller.startScroll(0, 200 * first.rowHeight, 0, 0, 0)
            first.scroller.abortAnimation()
        }
        restoration.emulateSavedInstanceStateRestore()
        val restored = awaitEditor { root }
        awaitLayout(restored) { restored.firstVisibleLine == 200 }
        compose.runOnIdle {
            assertEquals(200, restored.cursor.leftLine)
            assertEquals(202, restored.cursor.rightLine)
            identity.value = "blob-b"
        }
        val next = awaitEditor(restored) { root }
        compose.runOnIdle {
            assertEquals(0, next.firstVisibleLine)
            assertFalse(next.cursor.isSelected)
            assertTrue(restored.isReleased)
        }
    }

    @Test fun compactMenuToggleHasOneActionAndSnapshotHidesWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = RepoFileContent(
            repositoryId = "R", permission = RepoPermission.ADMIN, capabilities = RepoPermission.ADMIN.toCapabilities(),
            isArchived = false, ref = "main", headOid = "head", path = "doc.md", oid = "blob",
            byteSize = 4, isBinary = false, isTruncated = false, text = "text",
        )
        val state = mutableStateOf(RepoFileDetailUiState(file = file, previewSupported = true))
        var toggles = 0
        var edited = false
        compose.setContent {
            MaterialTheme {
                RepoFileMenu(
                    state.value,
                    { state.value = state.value.copy(previewMode = it) },
                    { toggles++; state.value = state.value.copy(wrapLines = it) },
                    {}, {}, { edited = true }, {},
                )
            }
        }
        fun open() = compose.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick()
        open()
        val toggle = compose.onNodeWithText(context.getString(R.string.repo_file_wrap_lines))
        toggle.assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, toggles) }
        val wrapBounds = toggle.fetchSemanticsNode().boundsInRoot
        val labelBounds = compose.onNodeWithText(context.getString(R.string.repo_file_wrap_lines), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        assertEquals(16f * density, labelBounds.left - wrapBounds.left, 1f)
        assertTrue(wrapBounds.height >= 48f * density)
        // Text + Material Switch (52dp), 8dp gap, and 16dp padding on each side.
        assertEquals(labelBounds.width + 92f * density, wrapBounds.width, 2f)
        compose.onNodeWithText(context.getString(R.string.common_edit)).performClick()
        compose.runOnIdle { assertTrue(edited); state.value = state.value.copy(file = file.copy(isCommitSnapshot = true), isDownloading = true) }
        open()
        compose.onNodeWithText(context.getString(R.string.common_edit)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_delete)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.repo_commits)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_download)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.conversation_preview_tab)).performClick()
        open()
        compose.onNodeWithText(context.getString(R.string.repo_file_wrap_lines)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.repo_file_source)).assertExists()
    }

    @Test fun translationRemainsAvailableInSourceModeAndOpensPreview() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = mockk<TranslationViewModel>(relaxed = true)
        val translation = TranslationUiState(configuration = TranslationConfiguration(TranslationPreferences(enabled = true)))
        val target = TranslationTarget("file:owner/repo/main/doc.md", "<p>text</p>", html = true)
        val file = RepoFileContent(
            repositoryId = "R", permission = RepoPermission.ADMIN, capabilities = RepoPermission.ADMIN.toCapabilities(),
            isArchived = false, ref = "main", headOid = "head", path = "doc.md", oid = "blob",
            byteSize = 4, isBinary = false, isTruncated = false, text = "text",
        )
        val state = mutableStateOf(RepoFileDetailUiState(file = file, previewSupported = true, previewHtml = target.content))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTranslationController provides TranslationController(translation, vm) {}) {
                    RepoFileMenu(
                        state.value,
                        { state.value = state.value.copy(previewMode = it) },
                        { state.value = state.value.copy(wrapLines = it) },
                        {}, {}, {}, {},
                        translationTarget = target,
                    )
                }
            }
        }
        fun open() = compose.onNodeWithContentDescription(context.getString(R.string.common_more)).performClick()
        open()
        val wrap = compose.onNodeWithText(context.getString(R.string.repo_file_wrap_lines)).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText(context.getString(R.string.repo_file_wrap_lines), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(label.width + 92f * context.resources.displayMetrics.density, wrap.width, 2f)
        compose.onNodeWithText(context.getString(R.string.translation_start)).performClick()
        compose.runOnIdle { assertTrue(state.value.previewMode); verify(exactly = 1) { vm.open(target) } }
        compose.onNodeWithText(context.getString(R.string.translation_start)).assertDoesNotExist()
        open()
        compose.onNodeWithText(context.getString(R.string.repo_file_wrap_lines)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.repo_file_source)).performClick()
        compose.runOnIdle { assertFalse(state.value.previewMode) }
    }


    private fun awaitEditor(previous: SoraViewerView? = null, root: () -> View): SoraViewerView {
        compose.waitUntil(10_000) {
            compose.runOnIdle { findEditor(root())?.let { it !== previous } == true }
        }
        return requireNotNull(findEditor(root())).also { awaitLayout(it) }
    }

    private fun awaitLayout(editor: SoraViewerView, condition: () -> Boolean = { true }) {
        try {
            compose.waitUntil(15_000) {
                compose.runOnIdle {
                    shadowOf(Looper.getMainLooper()).idle()
                    val busy = ReflectionHelpers.getField<Boolean>(editor, "layoutBusy")
                    if (!busy && editor.width > 0 && editor.height > 0) {
                        // Draw the AndroidView explicitly after Sora's own layout executor finishes.
                        val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
                        try { editor.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                        shadowOf(Looper.getMainLooper()).idle()
                        !ReflectionHelpers.getField<Boolean>(editor, "layoutBusy") &&
                            ReflectionHelpers.getField<Any?>(editor, "pendingPosition") == null && condition()
                    } else false
                }
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("busy=" + ReflectionHelpers.getField<Boolean>(editor, "layoutBusy") +
                ", wrap=" + editor.isWordwrap + ", rows=" + editor.layout.rowCount +
                ", size=" + editor.width + "x" + editor.height + ", line=" + editor.firstVisibleLine, e)
        }
    }

    private fun findEditor(view: View): SoraViewerView? {
        if (view is SoraViewerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
