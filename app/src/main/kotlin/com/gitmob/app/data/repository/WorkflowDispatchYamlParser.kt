package com.gitmob.app.data.repository

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.gitmob.app.data.model.WorkflowDispatchInput
import com.gitmob.app.data.model.WorkflowDispatchInputType

/** Parses the public GitHub Actions workflow_dispatch input shape as YAML 1.2. */
object WorkflowDispatchYamlParser {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliasCount = 50u),
            codePointLimit = MAX_WORKFLOW_YAML_BYTES,
        ),
    )

    fun parse(source: String): List<WorkflowDispatchInput> {
        val root = yaml.parseToYamlNode(source) as? YamlMap
            ?: throw IllegalArgumentException("Workflow root must be a mapping")
        val on = root.node("on") ?: return emptyList()
        val dispatch = (on as? YamlMap)?.node("workflow_dispatch") ?: return emptyList()
        if (dispatch is YamlNull) return emptyList()
        val dispatchMap = dispatch as? YamlMap
            ?: throw IllegalArgumentException("workflow_dispatch must be a mapping")
        val inputsNode = dispatchMap.node("inputs") ?: return emptyList()
        if (inputsNode is YamlNull) return emptyList()
        val inputs = inputsNode as? YamlMap
            ?: throw IllegalArgumentException("workflow_dispatch.inputs must be a mapping")
        if (inputs.entries.size > MAX_DISPATCH_INPUTS) {
            throw IllegalArgumentException("workflow_dispatch supports at most $MAX_DISPATCH_INPUTS inputs")
        }
        return inputs.entries.map { (nameNode, definitionNode) ->
            val name = nameNode.content.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Workflow input name must not be blank")
            val definition = definitionNode as? YamlMap
                ?: throw IllegalArgumentException("Workflow input '$name' must be a mapping")
            val type = when (definition.string("type")?.lowercase() ?: "string") {
                "string" -> WorkflowDispatchInputType.STRING
                "boolean" -> WorkflowDispatchInputType.BOOLEAN
                "choice" -> WorkflowDispatchInputType.CHOICE
                "number" -> WorkflowDispatchInputType.NUMBER
                "environment" -> WorkflowDispatchInputType.ENVIRONMENT
                else -> throw IllegalArgumentException("Workflow input '$name' has an unsupported type")
            }
            val options = definition.scalarList("options")
            if (type == WorkflowDispatchInputType.CHOICE && options.isEmpty()) {
                throw IllegalArgumentException("Choice input '$name' must define options")
            }
            if (type != WorkflowDispatchInputType.CHOICE && options.isNotEmpty()) {
                throw IllegalArgumentException("Only choice input '$name' may define options")
            }
            val defaultValue = definition.string("default")
            if (type == WorkflowDispatchInputType.BOOLEAN && defaultValue != null && defaultValue.lowercase() !in setOf("true", "false")) {
                throw IllegalArgumentException("Boolean input '$name' has an invalid default")
            }
            if (type == WorkflowDispatchInputType.NUMBER && defaultValue != null && !defaultValue.isFiniteNumber()) {
                throw IllegalArgumentException("Number input '$name' has an invalid default")
            }
            if (type == WorkflowDispatchInputType.CHOICE && defaultValue != null && defaultValue !in options) {
                throw IllegalArgumentException("Choice input '$name' has a default outside its options")
            }
            WorkflowDispatchInput(
                name = name,
                description = definition.string("description"),
                required = definition.boolean("required") ?: false,
                type = type,
                defaultValue = defaultValue,
                options = options,
            )
        }
    }

    private fun YamlMap.node(key: String): YamlNode? = entries.entries.firstOrNull { it.key.content == key }?.value

    private fun YamlMap.string(key: String): String? = when (val value = node(key)) {
        null, is YamlNull -> null
        is YamlScalar -> value.content
        else -> throw IllegalArgumentException("Workflow property '$key' must be a scalar")
    }

    private fun YamlMap.boolean(key: String): Boolean? = string(key)?.let { value ->
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Workflow property '$key' must be a boolean")
        }
    }

    private fun YamlMap.scalarList(key: String): List<String> = when (val value = node(key)) {
        null, is YamlNull -> emptyList()
        is YamlList -> value.items.mapIndexed { index, item ->
            (item as? YamlScalar)?.content
                ?: throw IllegalArgumentException("Workflow property '$key[$index]' must be a scalar")
        }
        else -> throw IllegalArgumentException("Workflow property '$key' must be a list")
    }

    private fun String.isFiniteNumber(): Boolean = toDoubleOrNull()?.isFinite() == true

    private const val MAX_DISPATCH_INPUTS = 25
    private const val MAX_WORKFLOW_YAML_BYTES = 1024 * 1024
}
