package com.gitmob.app.data.repository

import com.gitmob.app.data.model.WorkflowDispatchInputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDispatchYamlParserTest {
    @Test
    fun `parses typed workflow dispatch inputs`() {
        val inputs = WorkflowDispatchYamlParser.parse(
            """
            name: Deploy
            on:
              workflow_dispatch:
                inputs:
                  environment:
                    description: "Target environment"
                    required: true
                    type: environment
                  dry_run:
                    description: Skip deployment
                    type: boolean
                    default: false
                  level:
                    type: choice
                    default: warning
                    options:
                      - info
                      - warning
                      - error
            """.trimIndent(),
        )

        assertEquals(3, inputs.size)
        assertEquals(WorkflowDispatchInputType.ENVIRONMENT, inputs[0].type)
        assertTrue(inputs[0].required)
        assertEquals(WorkflowDispatchInputType.BOOLEAN, inputs[1].type)
        assertEquals("false", inputs[1].defaultValue)
        assertEquals(listOf("info", "warning", "error"), inputs[2].options)
    }

    @Test
    fun `ignores comments while preserving hash inside quotes`() {
        val input = WorkflowDispatchYamlParser.parse(
            """
            on:
              workflow_dispatch:
                inputs:
                  message:
                    description: "Issue #123"
                    required: false # optional
            """.trimIndent(),
        ).single()

        assertEquals("Issue #123", input.description)
        assertFalse(input.required)
        assertEquals(WorkflowDispatchInputType.STRING, input.type)
    }

    @Test
    fun `returns empty list without workflow dispatch`() {
        assertTrue(WorkflowDispatchYamlParser.parse("on: [push]").isEmpty())
    }
}
