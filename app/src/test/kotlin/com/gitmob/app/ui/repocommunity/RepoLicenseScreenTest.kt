package com.gitmob.app.ui.repocommunity

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gitmob.app.R
import com.gitmob.app.data.model.RepoLicenseDocument
import com.gitmob.app.ui.common.awaitSoraViewer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoLicenseScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullLicenseWrapsWithoutLineNumbersAndCanReachItsLastLine() {
        val content = (1..6_000).joinToString("\r\n") {
            "License clause $it: Permission to use, copy, modify and distribute this software is granted."
        } + "\r\nEND"
        val root = showLicense(RepoLicenseDocument("LICENSE", "MIT", "LICENSE", null, content))
        compose.onNodeWithText("MIT").assertExists()
        val viewer = compose.awaitSoraViewer(root())
        compose.runOnIdle {
            assertFalse(viewer.isEditable)
            assertFalse(viewer.isLineNumberEnabled)
            assertTrue(viewer.isWordwrap)
            assertNull(viewer.embeddedState)
            assertTrue(viewer.height <= root().height)
            assertTrue(viewer.layout.rowCount > viewer.text.lineCount)
            assertEquals(content, viewer.text.toString())
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(R.string.license_copy)).performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals(content, clipboard.primaryClip!!.getItemAt(0).text.toString())
            viewer.setSelection(6_000, 3)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            viewer.scroller.abortAnimation()
            assertTrue(viewer.lastVisibleLine >= 6_000)
            assertTrue(viewer.firstVisibleLine > 5_900)
        }
    }

    @Test fun emptyLicenseWithoutSpdxStillOpensReadOnly() {
        val root = showLicense(RepoLicenseDocument("LICENSE", null, "LICENSE", null, ""))
        val viewer = compose.awaitSoraViewer(root())
        compose.runOnIdle {
            assertEquals("", viewer.text.toString())
            assertFalse(viewer.isEditable)
            assertFalse(viewer.isLineNumberEnabled)
            assertTrue(viewer.height > 0)
        }
    }

    private fun showLicense(document: RepoLicenseDocument): () -> View {
        val state = MutableStateFlow(RepoLicenseUiState(document = document))
        val viewModel = mockk<RepoLicenseViewModel>(relaxed = true)
        every { viewModel.state } returns state
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                RepoLicenseScreen("owner", "repo", "main", {}, {}, { _, _ -> }, viewModel)
            }
        }
        return { root }
    }
}
