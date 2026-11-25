package com.gitmob.app.ui.repoissues

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gitmob.app.ui.common.SoraEditorState
import com.gitmob.app.core.markdown.CommonMarkRenderer
import com.gitmob.app.data.model.IssueFormCheckboxOption
import com.gitmob.app.data.model.IssueFormField
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IssueFormContentTest {
    @get:Rule val composeRule = createComposeRule()
    private val renderer = CommonMarkRenderer()

    @Test
    fun `checkbox formats text and link click leaves checkbox unchanged`() {
        val source = "**Bold** _Italic_ [Guide](https://github.com/o/r/issues?q=state%3Aopen)"
        val field = IssueFormField.Checkboxes("check", "Checklist", options = listOf(IssueFormCheckboxOption(source, true)))
        val selected = mutableStateOf(emptySet<Int>())
        val primary = mutableStateOf(Color(0xFF123456))
        val links = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = primary.value)) {
                Column(Modifier.width(360.dp)) {
                    IssueFormFieldContent(field, mapOf(source to renderer.renderToHtml(source)), "", selected.value,
                        {}, { selected.value = it }, links::add)
                }
            }
        }
        val node = composeRule.onNodeWithText("Bold Italic Guide *", useUnmergedTree = true)
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "Bold" })
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic && text.text.substring(it.start, it.end) == "Italic" })
        assertLinkAndMarkerColors(text, primary.value)

        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(text.text.indexOf("Guide") + 1).center) }
        composeRule.runOnIdle { assertEquals(listOf("https://github.com/o/r/issues?q=state%3Aopen"), links) }
        composeRule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()

        composeRule.runOnIdle { primary.value = Color(0xFF876543) }
        assertLinkAndMarkerColors(node.fetchSemanticsNode().config[SemanticsProperties.Text].single(), primary.value)
    }

    @Test
    fun `plain labels placeholder and dropdown options keep markdown characters`() {
        val primary = Color(0xFF654321)
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = primary)) {
                Column {
                    IssueFormFieldContent(IssueFormField.Input("v", "**Version**", placeholder = "*Hint*", required = true),
                        emptyMap(), "", emptySet(), {}, {}, {})
                    IssueFormFieldContent(IssueFormField.Dropdown("os", "Platform", options = listOf("**Linux**"), required = true),
                        emptyMap(), "", emptySet(), {}, {}, {})
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.onNodeWithText("*Hint*").assertExists()
        composeRule.onNodeWithText("**Linux**").assertExists()
        listOf("**Version** *", "Platform *").forEach { label ->
            val text = composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals(primary, text.spanStyles.single().item.color)
            assertEquals(label.lastIndex, text.spanStyles.single().start)
        }
    }

    @Test
    fun `all descriptions use markdown while blank descriptions create no webview`() {
        val fields = listOf(
            IssueFormField.Input("v", "Version", "[Input](https://example.com/input)"),
            IssueFormField.Textarea("log", "Logs", "- **System**"),
            IssueFormField.Dropdown("os", "OS", "Choose _one_", listOf("Linux")),
            IssueFormField.Checkboxes("check", "Check", "Read **carefully**", listOf(IssueFormCheckboxOption("Done"))),
            IssueFormField.Input("optional", "Optional", "  "),
        )
        val html = listOf("[Input](https://example.com/input)", "- **System**", "Choose _one_", "Read **carefully**", "Done")
            .associateWith(renderer::renderToHtml)
        lateinit var root: View
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MaterialTheme {
                Column { fields.forEach { IssueFormFieldContent(it, html, "", emptySet(), {}, {}, {}, textareaState = androidx.compose.runtime.remember(it.id) { SoraEditorState() }) } }
            }
        }
        composeRule.runOnIdle {
            val documents = webViews(root).map { shadowOf(it).lastLoadDataWithBaseURL.data }
            assertEquals(4, documents.size)
            assertTrue(documents.any { it.contains("<a href=\"https://example.com/input\">Input</a>") })
            assertTrue(documents.any { it.contains("<ul>") && it.contains("<strong>System</strong>") })
            assertTrue(documents.any { it.contains("<em>one</em>") })
            assertTrue(documents.any { it.contains("<strong>carefully</strong>") })
        }
    }

    @Test
    fun `repeated markdown blocks and numeric field ids have distinct list keys`() {
        val fields = listOf(IssueFormField.Markdown(value = "Same"), IssueFormField.Markdown(value = "Same"),
            IssueFormField.Input("0", "Number"))
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    itemsIndexed(fields, key = { index, field -> issueFormFieldKey(index, field) }) { _, field ->
                        IssueFormFieldContent(field, mapOf("Same" to renderer.renderToHtml("Same")), "", emptySet(), {}, {}, {})
                    }
                }
            }
        }
        composeRule.onNodeWithText("Number").assertExists()
    }

    @Test
    fun `literal asterisks stay uncolored and unsafe inline links do not navigate`() {
        val source = "Literal \\* [Link](javascript:alert)"
        val field = IssueFormField.Checkboxes("check", "Checklist", options = listOf(IssueFormCheckboxOption(source)))
        val links = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                IssueFormFieldContent(field, mapOf(source to renderer.renderToHtml(source)), "", emptySet(), {}, {}, links::add)
            }
        }
        val text = composeRule.onNodeWithText("Literal * Link", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertFalse(text.spanStyles.any { text.text.indexOf('*') in it.start until it.end && it.item.color != Color.Unspecified })
        val link = text.getLinkAnnotations(0, text.length).single().item
        composeRule.runOnIdle { link.linkInteractionListener?.onClick(link) }
        assertTrue(links.isEmpty())
    }

    private fun assertLinkAndMarkerColors(text: AnnotatedString, primary: Color) {
        val annotation = text.getLinkAnnotations(0, text.length).single()
        val link = annotation.item as LinkAnnotation.Url
        assertEquals("Guide", text.text.substring(annotation.start, annotation.end))
        assertEquals(primary, link.styles?.style?.color)
        assertEquals(TextDecoration.Underline, link.styles?.style?.textDecoration)
        val marker = text.spanStyles.single { it.start == text.length - 1 }
        assertEquals(primary, marker.item.color)
        assertEquals(text.length, marker.end)
        assertTrue(annotation.end < marker.start)
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
