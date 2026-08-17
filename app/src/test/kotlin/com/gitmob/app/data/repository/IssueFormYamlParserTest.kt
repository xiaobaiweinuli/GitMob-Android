package com.gitmob.app.data.repository

import com.gitmob.app.data.model.IssueFormField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueFormYamlParserTest {
    @Test
    fun `parses all supported issue form controls`() {
        val template = IssueFormYamlParser.parse(
            "bug.yml",
            """
                name: Bug report
                description: Tell us what went wrong
                title: "[Bug] "
                labels: [bug, triage]
                assignees:
                  - octocat
                body:
                  - type: markdown
                    attributes:
                      value: Thanks for helping.
                  - type: input
                    id: version
                    attributes:
                      label: Version
                      placeholder: 1.0.0
                    validations:
                      required: true
                  - type: textarea
                    id: logs
                    attributes:
                      label: Logs
                      render: shell
                  - type: dropdown
                    id: severity
                    attributes:
                      label: Severity
                      multiple: false
                      default: 1
                      options: [low, high]
                    validations:
                      required: true
                  - type: checkboxes
                    id: terms
                    attributes:
                      label: Checks
                      options:
                        - label: I searched existing issues
                          required: true
            """.trimIndent(),
        )

        assertEquals("Bug report", template.name)
        assertEquals(listOf("bug", "triage"), template.labels)
        assertEquals(listOf("octocat"), template.assignees)
        assertEquals(5, template.fields.size)
        assertTrue((template.fields[1] as IssueFormField.Input).required)
        assertEquals("shell", (template.fields[2] as IssueFormField.Textarea).render)
        assertEquals(1, (template.fields[3] as IssueFormField.Dropdown).defaultIndex)
        assertTrue((template.fields[4] as IssueFormField.Checkboxes).options.single().required)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects upload and unknown controls`() {
        IssueFormYamlParser.parse(
            "unsupported.yml",
            """
                name: Unsupported
                description: Contains upload
                body:
                  - type: upload
                    id: attachment
                    attributes:
                      label: Attachment
            """.trimIndent(),
        )
    }

    @Test
    fun `parses blank issue setting`() {
        assertFalse(IssueFormYamlParser.parseBlankIssuesEnabled("blank_issues_enabled: false"))
        assertTrue(IssueFormYamlParser.parseBlankIssuesEnabled("contact_links: []"))
    }

    @Test
    fun `validates required values and builds markdown`() {
        val template = IssueFormYamlParser.parse(
            "bug.yml",
            """
                name: Bug
                description: Bug form
                body:
                  - type: input
                    id: version
                    attributes:
                      label: Version
                    validations:
                      required: true
                  - type: textarea
                    id: logs
                    attributes:
                      label: Logs
                      render: shell
                  - type: checkboxes
                    id: terms
                    attributes:
                      label: Checklist
                      options:
                        - label: Searched existing issues
                          required: true
            """.trimIndent(),
        )
        assertFalse(IssueFormSubmissionBuilder.isComplete(template, emptyMap(), emptyMap()))

        val text = mapOf("version" to "1.0", "logs" to "echo ok")
        val selections = mapOf("terms" to setOf(0))
        assertTrue(IssueFormSubmissionBuilder.isComplete(template, text, selections))
        val body = IssueFormSubmissionBuilder.build(template, text, selections)
        assertTrue(body.contains("### Version\n\n1.0"))
        assertTrue(body.contains("```shell\necho ok\n```"))
        assertTrue(body.contains("- [x] Searched existing issues"))
    }
}

