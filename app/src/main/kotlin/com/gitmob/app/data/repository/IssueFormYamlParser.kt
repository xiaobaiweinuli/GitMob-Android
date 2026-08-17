package com.gitmob.app.data.repository

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.gitmob.app.data.model.IssueFormCheckboxOption
import com.gitmob.app.data.model.IssueFormField
import com.gitmob.app.data.model.IssueTemplate

/** Converts GitHub Issue Form YAML into GitMob's protocol-free domain model. */
object IssueFormYamlParser {
    fun parse(filename: String, source: String): IssueTemplate {
        val root = Yaml.default.parseToYamlNode(source) as? YamlMap
            ?: invalid(filename, "document root must be a mapping")
        val name = root.requiredString("name", filename)
        val description = root.requiredString("description", filename)
        val fields = root.requiredList("body", filename).items.mapIndexed { index, node ->
            parseField(filename, index, node as? YamlMap ?: invalid(filename, "body[$index] must be a mapping"))
        }
        if (fields.none { it !is IssueFormField.Markdown }) invalid(filename, "template has no input fields")
        val ids = fields.mapNotNull(IssueFormField::id)
        if (ids.size != ids.distinct().size) invalid(filename, "field ids must be unique")
        ids.firstOrNull { !FIELD_ID.matches(it) }?.let { invalid(filename, "field id '$it' is invalid") }
        return IssueTemplate(
            name = name,
            about = description,
            title = root.string("title"),
            filename = filename,
            labels = root.stringList("labels"),
            assignees = root.stringList("assignees"),
            fields = fields,
        )
    }

    fun parseBlankIssuesEnabled(source: String): Boolean {
        val root = Yaml.default.parseToYamlNode(source) as? YamlMap ?: return true
        return root.boolean("blank_issues_enabled") ?: true
    }

    private fun parseField(filename: String, index: Int, field: YamlMap): IssueFormField {
        val type = field.requiredString("type", filename)
        val id = field.string("id")
        val attributes = field.map("attributes") ?: YamlMap(emptyMap(), field.path)
        val validations = field.map("validations")
        return when (type) {
            "markdown" -> IssueFormField.Markdown(value = attributes.requiredString("value", filename))
            "input" -> IssueFormField.Input(
                id = id ?: invalid(filename, "body[$index].id is required"),
                label = attributes.requiredString("label", filename),
                description = attributes.string("description"),
                placeholder = attributes.string("placeholder"),
                value = attributes.string("value"),
                required = validations?.boolean("required") ?: false,
            )
            "textarea" -> IssueFormField.Textarea(
                id = id ?: invalid(filename, "body[$index].id is required"),
                label = attributes.requiredString("label", filename),
                description = attributes.string("description"),
                placeholder = attributes.string("placeholder"),
                value = attributes.string("value"),
                render = attributes.string("render"),
                required = validations?.boolean("required") ?: false,
            )
            "dropdown" -> {
                val options = attributes.requiredList("options", filename).items.mapIndexed { optionIndex, option ->
                    (option as? YamlScalar)?.content
                        ?: invalid(filename, "body[$index].attributes.options[$optionIndex] must be a string")
                }
                if (options.isEmpty()) invalid(filename, "body[$index].attributes.options must not be empty")
                val defaultIndex = attributes.int("default")
                if (defaultIndex != null && defaultIndex !in options.indices) invalid(filename, "body[$index].attributes.default is out of range")
                IssueFormField.Dropdown(
                    id = id ?: invalid(filename, "body[$index].id is required"),
                    label = attributes.requiredString("label", filename),
                    description = attributes.string("description"),
                    options = options,
                    multiple = attributes.boolean("multiple") ?: false,
                    defaultIndex = defaultIndex,
                    required = validations?.boolean("required") ?: false,
                )
            }
            "checkboxes" -> {
                val options = attributes.requiredList("options", filename).items.mapIndexed { optionIndex, option ->
                    val optionMap = option as? YamlMap
                        ?: invalid(filename, "body[$index].attributes.options[$optionIndex] must be a mapping")
                    IssueFormCheckboxOption(
                        label = optionMap.requiredString("label", filename),
                        required = optionMap.boolean("required") ?: false,
                    )
                }
                if (options.isEmpty()) invalid(filename, "body[$index].attributes.options must not be empty")
                IssueFormField.Checkboxes(
                    id = id ?: invalid(filename, "body[$index].id is required"),
                    label = attributes.requiredString("label", filename),
                    description = attributes.string("description"),
                    options = options,
                )
            }
            else -> invalid(filename, "unsupported field type '$type'")
        }
    }

    private fun YamlMap.node(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value
    private fun YamlMap.string(key: String): String? = when (val value = node(key)) {
        null, is YamlNull -> null
        is YamlScalar -> value.content
        else -> throw IllegalArgumentException("'$key' must be a scalar")
    }
    private fun YamlMap.requiredString(key: String, filename: String): String =
        string(key)?.takeIf(String::isNotBlank) ?: invalid(filename, "'$key' is required")
    private fun YamlMap.map(key: String): YamlMap? = when (val value = node(key)) {
        null, is YamlNull -> null
        is YamlMap -> value
        else -> throw IllegalArgumentException("'$key' must be a mapping")
    }
    private fun YamlMap.requiredList(key: String, filename: String): YamlList = when (val value = node(key)) {
        is YamlList -> value
        else -> invalid(filename, "'$key' must be a list")
    }
    private fun YamlMap.stringList(key: String): List<String> = when (val value = node(key)) {
        null, is YamlNull -> emptyList()
        is YamlList -> value.items.map { (it as? YamlScalar)?.content ?: throw IllegalArgumentException("'$key' values must be strings") }
        is YamlScalar -> value.content.split(',').map(String::trim).filter(String::isNotEmpty)
        else -> throw IllegalArgumentException("'$key' must be a list")
    }
    private fun YamlMap.boolean(key: String): Boolean? = string(key)?.let { value ->
        when (value.lowercase()) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("'$key' must be a boolean") }
    }
    private fun YamlMap.int(key: String): Int? = string(key)?.toIntOrNull()
        ?: if (node(key) == null || node(key) is YamlNull) null else throw IllegalArgumentException("'$key' must be an integer")

    private fun invalid(filename: String, reason: String): Nothing =
        throw IllegalArgumentException("Invalid Issue Form '$filename': $reason")

    private val FIELD_ID = Regex("[A-Za-z0-9_-]+")
}
