package com.gitmob.app.ui.repocode

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.core.permission.toCapabilities
import com.gitmob.app.data.model.RepoFileContent
import com.gitmob.app.ui.theme.GitMobTheme
import com.gitmob.app.data.translation.TranslationConfiguration
import com.gitmob.app.data.translation.TranslationPreferences
import com.gitmob.app.data.translation.TranslationTarget
import com.gitmob.app.ui.translation.LocalTranslationController
import com.gitmob.app.ui.translation.TranslationController
import com.gitmob.app.ui.translation.TranslationUiState
import com.gitmob.app.ui.translation.TranslationViewModel
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoFileSourceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lineOffsetsPreserveCrLfBlankLinesAndTrailingNewline() {
        assertArrayEquals(intArrayOf(0), sourceLineStarts(""))
        assertArrayEquals(intArrayOf(0, 3, 5, 8, 10), sourceLineStarts("a\r\n\r\nb\r\nc\n"))
        assertArrayEquals(intArrayOf(0), sourceLineStarts("a\r"))
    }

    @Test fun wrappingUsesActualLayoutWithoutChangingTheOriginalText() {
        val text = "  \t中文 👋 \rword " + "long_source_name ".repeat(8) + "\r\n\r\nlast\n"
        val wrapped = mutableStateOf(false)
        lateinit var horizontal: ScrollState
        lateinit var contentView: View
        compose.setContent {
            GitMobTheme {
                contentView = LocalView.current
                horizontal = rememberScrollState()
                Surface(Modifier.size(260.dp, 180.dp)) {
                    RepoFileSource(text, wrapped.value, rememberScrollState(), horizontal)
                }
            }
        }
        val original = layout(text)
        assertEquals(4, original.lineCount)
        screenshot(contentView, "source-no-wrap.png")
        compose.runOnIdle { assertTrue(horizontal.maxValue > 0); wrapped.value = true }
        val soft = layout(text)
        assertTrue(soft.lineCount > original.lineCount)
        assertEquals(text, soft.layoutInput.text.text)
        val offsets = sourceLineStarts(text)
        assertEquals(4, offsets.size)
        assertTrue(soft.getLineForOffset(offsets[1]) > 1)
        assertEquals(soft.lineCount - 1, soft.getLineForOffset(offsets.last()))
        assertTrue(soft.getLineBaseline(soft.getLineForOffset(offsets[2])) > soft.getLineBaseline(0))
        compose.onNodeWithText("1").assertDoesNotExist()
        screenshot(contentView, "source-wrap.png")
    }

    @Test
    @Config(shadows = [SourceMagnifierShadow::class])
    fun selectAllCopiesOffscreenSourceWithoutLineNumbersOrSoftBreaks() {
        val text = (1..80).joinToString("\r\n") { "  \t中文 👋 source row $it " + "value ".repeat(12) } + "\r\n"
        val toolbar = RecordingToolbar()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    Box(Modifier.size(260.dp, 180.dp)) {
                        RepoFileSource(text, true, rememberScrollState(), rememberScrollState())
                    }
                }
            }
        }
        val word = layout(text).getBoundingBox(text.indexOf("source") + 3)
        compose.onNodeWithText(text).performTouchInput { longClick(word.center) }
        compose.waitUntil { toolbar.isShown }
        compose.runOnIdle { toolbar.click(TextContextMenuKeys.SelectAllKey) }
        compose.waitForIdle()
        compose.runOnIdle { toolbar.click(TextContextMenuKeys.CopyKey) }
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun previewScrollRestoresAfterAsynchronousLayoutWithoutOverridingUserScroll() {
        val visible = mutableStateOf(true)
        val contentHeight = mutableStateOf(1000.dp)
        lateinit var scroll: ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            if (visible.value) {
                RepoFilePreviewViewport(scroll, Modifier.size(260.dp, 180.dp).testTag("preview")) {
                    Box(Modifier.height(contentHeight.value))
                }
            }
        }
        compose.runOnIdle { runBlocking { scroll.scrollTo(240) } }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { contentHeight.value = 0.dp; visible.value = true }
        compose.runOnIdle { assertEquals(0, scroll.value); contentHeight.value = 1000.dp }
        compose.runOnIdle { assertEquals(240, scroll.value); visible.value = false }
        compose.runOnIdle { contentHeight.value = 260.dp; visible.value = true }
        compose.onNodeWithTag("preview").performTouchInput { swipeDown() }
        val userOffset = compose.runOnIdle { scroll.value }
        assertTrue(userOffset < 240)
        compose.runOnIdle { contentHeight.value = 1000.dp }
        compose.runOnIdle { assertEquals(userOffset, scroll.value) }
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

    private fun screenshot(view: View, name: String) {
        val folder = File("build/reports/repo-file-ui").apply { mkdirs() }
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    // Robolectric has no real magnifier Surface. Selection, select-all and clipboard remain real.
    @Implements(android.widget.Magnifier::class)
    @Suppress("UNUSED_PARAMETER")
    class SourceMagnifierShadow {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
        @Implementation fun update() = Unit
        @Implementation fun dismiss() = Unit
    }

    private class RecordingToolbar : TextContextMenuProvider, TextContextMenuSession {
        private var dataProvider: TextContextMenuDataProvider? = null
        val isShown: Boolean get() = dataProvider != null
        private var closed = CompletableDeferred<Unit>()

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            this.dataProvider = dataProvider
            closed = CompletableDeferred()
            try {
                closed.await()
            } finally {
                this.dataProvider = null
            }
        }

        fun click(key: Any) {
            val item = requireNotNull(dataProvider).data().components
                .filterIsInstance<TextContextMenuItem>().single { it.key == key }
            item.onClick(this)
        }

        override fun close() { closed.complete(Unit) }
    }
}
