package com.gitmob.app.data.repository

import com.gitmob.app.data.model.IssueFormField
import com.gitmob.app.data.model.IssueTemplate

object IssueFormSubmissionBuilder {
    fun isComplete(
        template: IssueTemplate,
        textValues: Map<String, String>,
        selections: Map<String, Set<Int>>,
    ): Boolean = template.fields.all { field ->
        when (field) {
            is IssueFormField.Markdown -> true
            is IssueFormField.Input -> !field.required || textValues[field.id].orEmpty().isNotBlank()
            is IssueFormField.Textarea -> !field.required || textValues[field.id].orEmpty().isNotBlank()
            is IssueFormField.Dropdown -> !field.required || selections[field.id].orEmpty().isNotEmpty()
            is IssueFormField.Checkboxes -> field.options.withIndex().all { (index, option) ->
                !option.required || index in selections[field.id].orEmpty()
            }
        }
    }

    fun build(
        template: IssueTemplate,
        textValues: Map<String, String>,
        selections: Map<String, Set<Int>>,
    ): String = template.fields.mapNotNull { field ->
        when (field) {
            is IssueFormField.Markdown -> null
            is IssueFormField.Input -> section(field.label, textValues[field.id].orEmpty())
            is IssueFormField.Textarea -> {
                val value = textValues[field.id].orEmpty()
                val content = if (value.isBlank() || field.render.isNullOrBlank()) value else fenced(value, field.render)
                section(field.label, content)
            }
            is IssueFormField.Dropdown -> {
                val value = selections[field.id].orEmpty().sorted().mapNotNull(field.options::getOrNull).joinToString(", ")
                section(field.label, value)
            }
            is IssueFormField.Checkboxes -> section(
                field.label,
                field.options.mapIndexed { index, option -> "- [${if (index in selections[field.id].orEmpty()) "x" else " "}] ${option.label}" }.joinToString("\n"),
            )
        }
    }.joinToString("\n\n")

    private fun section(label: String, value: String): String =
        "### $label\n\n${value.trim().ifEmpty { "_No response_" }}"

    private fun fenced(value: String, language: String): String {
        val longestRun = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return "$fence$language\n$value\n$fence"
    }
}
