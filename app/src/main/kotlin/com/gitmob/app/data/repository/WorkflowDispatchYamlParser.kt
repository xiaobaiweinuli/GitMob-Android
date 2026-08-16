package com.gitmob.app.data.repository

import com.gitmob.app.data.model.WorkflowDispatchInput
import com.gitmob.app.data.model.WorkflowDispatchInputType

/** Parses the public workflow_dispatch input shape without interpreting arbitrary YAML. */
object WorkflowDispatchYamlParser {
    fun parse(source: String): List<WorkflowDispatchInput> {
        val lines = source.lineSequence().map(::stripComment).filter { it.isNotBlank() }.toList()
        val dispatchIndex = lines.indexOfFirst { key(it) == "workflow_dispatch" }
        if (dispatchIndex < 0) return emptyList()
        val dispatchIndent = indent(lines[dispatchIndex])
        val inputsIndex = (dispatchIndex + 1 until lines.size).firstOrNull { index ->
            val current = lines[index]
            indent(current) > dispatchIndent && key(current) == "inputs"
        } ?: return emptyList()
        val inputsIndent = indent(lines[inputsIndex])
        val inputEntryIndent = lines.drop(inputsIndex + 1)
            .takeWhile { indent(it) > inputsIndent }
            .minOfOrNull(::indent)
            ?: return emptyList()
        val result = mutableListOf<WorkflowDispatchInput>()
        var index = inputsIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            val lineIndent = indent(line)
            if (lineIndent <= inputsIndent) break
            if (lineIndent != inputEntryIndent || !line.trim().endsWith(':')) { index++; continue }
            val name = key(line)
            val fieldIndent = lineIndent
            var description: String? = null
            var required = false
            var type = WorkflowDispatchInputType.STRING
            var defaultValue: String? = null
            val options = mutableListOf<String>()
            index++
            while (index < lines.size && indent(lines[index]) > fieldIndent) {
                val current = lines[index]
                val trimmed = current.trim()
                when (key(current)) {
                    "description" -> description = value(current)
                    "required" -> required = value(current).equals("true", true)
                    "default" -> defaultValue = value(current)
                    "type" -> type = when (value(current).lowercase()) {
                        "boolean" -> WorkflowDispatchInputType.BOOLEAN
                        "choice" -> WorkflowDispatchInputType.CHOICE
                        "environment" -> WorkflowDispatchInputType.ENVIRONMENT
                        else -> WorkflowDispatchInputType.STRING
                    }
                    "options" -> {
                        val optionIndent = indent(current)
                        index++
                        while (index < lines.size && indent(lines[index]) > optionIndent) {
                            lines[index].trim().removePrefix("-").trim().takeIf { it.isNotBlank() }?.let { options += unquote(it) }
                            index++
                        }
                        continue
                    }
                }
                if (trimmed.startsWith("- ")) options += unquote(trimmed.removePrefix("- "))
                index++
            }
            result += WorkflowDispatchInput(name, description, required, type, defaultValue, options)
        }
        return result
    }

    private fun stripComment(line: String): String {
        var single = false; var double = false
        line.forEachIndexed { index, char ->
            when (char) { '\'' -> if (!double) single = !single; '"' -> if (!single) double = !double; '#' -> if (!single && !double) return line.substring(0, index).trimEnd() }
        }
        return line
    }
    private fun indent(line: String) = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    private fun key(line: String) = unquote(line.trim().substringBefore(':').trim())
    private fun value(line: String) = unquote(line.trim().substringAfter(':', "").trim())
    private fun unquote(value: String): String = value.removeSurrounding("\"").removeSurrounding("'")
}
