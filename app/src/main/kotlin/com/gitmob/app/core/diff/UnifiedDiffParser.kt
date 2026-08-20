package com.gitmob.app.core.diff

enum class DiffLineType { ADDITION, DELETION, CONTEXT, META }

data class UnifiedDiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int?,
    val newLine: Int?,
)

data class UnifiedDiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<UnifiedDiffLine>,
)

data class UnifiedDiff(val hunks: List<UnifiedDiffHunk>, val metadata: List<String> = emptyList())

/** 解析 GitHub REST files.patch 返回的 unified diff。无法识别的行保留为 META，不丢信息。 */
object UnifiedDiffParser {
    private val hunkPattern = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    fun parse(patch: String?): UnifiedDiff? {
        if (patch.isNullOrBlank()) return null
        val hunks = mutableListOf<UnifiedDiffHunk>()
        val metadata = mutableListOf<String>()
        var current: MutableHunk? = null
        patch.lineSequence().forEach { line ->
            val match = hunkPattern.matchEntire(line)
            if (match != null) {
                current?.let { hunks += it.build() }
                current = MutableHunk(
                    header = line,
                    oldStart = match.groupValues[1].toInt(),
                    oldCount = match.groupValues[2].ifBlank { "1" }.toInt(),
                    newStart = match.groupValues[3].toInt(),
                    newCount = match.groupValues[4].ifBlank { "1" }.toInt(),
                )
                return@forEach
            }
            val target = current
            if (target == null) {
                metadata += line
                return@forEach
            }
            when {
                line.startsWith("+") && !line.startsWith("+++") -> target.add(UnifiedDiffLine(DiffLineType.ADDITION, line.drop(1), null, target.nextNew++))
                line.startsWith("-") && !line.startsWith("---") -> target.add(UnifiedDiffLine(DiffLineType.DELETION, line.drop(1), target.nextOld++, null))
                line.startsWith(" ") -> target.add(UnifiedDiffLine(DiffLineType.CONTEXT, line.drop(1), target.nextOld++, target.nextNew++))
                line == "\\\\ No newline at end of file" -> target.add(UnifiedDiffLine(DiffLineType.META, line, null, null))
                else -> metadata += line
            }
        }
        current?.let { hunks += it.build() }
        return UnifiedDiff(hunks, metadata)
    }

    private class MutableHunk(
        val header: String,
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
    ) {
        var nextOld = oldStart
        var nextNew = newStart
        private val lines = mutableListOf<UnifiedDiffLine>()
        fun add(line: UnifiedDiffLine) { lines += line }
        fun build() = UnifiedDiffHunk(header, oldStart, oldCount, newStart, newCount, lines.toList())
    }
}
