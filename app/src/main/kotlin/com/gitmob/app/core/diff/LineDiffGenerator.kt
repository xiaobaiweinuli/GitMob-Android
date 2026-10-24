package com.gitmob.app.core.diff

/** Exact Myers line diff used for adjacent Gist text snapshots. */
object LineDiffGenerator {
    private enum class Kind { EQUAL, INSERT, DELETE }
    private data class Operation(val kind: Kind, val text: String)

    fun generate(oldText: String, newText: String): UnifiedDiff? {
        if (oldText == newText) return null
        val oldLines = oldText.toLines()
        val newLines = newText.toLines()
        val operations = myers(oldLines, newLines)
        var oldLine = 1
        var newLine = 1
        val lines = operations.map { operation ->
            when (operation.kind) {
                Kind.EQUAL -> UnifiedDiffLine(DiffLineType.CONTEXT, operation.text, oldLine++, newLine++)
                Kind.DELETE -> UnifiedDiffLine(DiffLineType.DELETION, operation.text, oldLine++, null)
                Kind.INSERT -> UnifiedDiffLine(DiffLineType.ADDITION, operation.text, null, newLine++)
            }
        }
        val oldStart = if (oldLines.isEmpty()) 0 else 1
        val newStart = if (newLines.isEmpty()) 0 else 1
        return UnifiedDiff(
            hunks = listOf(
                UnifiedDiffHunk(
                    header = "@@ -$oldStart,${oldLines.size} +$newStart,${newLines.size} @@",
                    oldStart = oldStart,
                    oldCount = oldLines.size,
                    newStart = newStart,
                    newCount = newLines.size,
                    lines = lines,
                ),
            ),
        )
    }

    private fun String.toLines(): List<String> = when {
        isEmpty() -> emptyList()
        endsWith('\n') -> split('\n').dropLast(1)
        else -> split('\n')
    }

    private fun myers(old: List<String>, new: List<String>): List<Operation> {
        val max = old.size + new.size
        if (max == 0) return emptyList()
        val offset = max
        val frontier = IntArray(max * 2 + 1)
        val trace = mutableListOf<IntArray>()

        for (distance in 0..max) {
            for (diagonal in -distance..distance step 2) {
                val index = offset + diagonal
                var x = if (
                    diagonal == -distance ||
                    (diagonal != distance && frontier[index - 1] < frontier[index + 1])
                ) {
                    frontier[index + 1]
                } else {
                    frontier[index - 1] + 1
                }
                var y = x - diagonal
                while (x < old.size && y < new.size && old[x] == new[y]) {
                    x++
                    y++
                }
                frontier[index] = x
                if (x >= old.size && y >= new.size) {
                    trace += frontier.copyOf()
                    return backtrack(trace, old, new, offset)
                }
            }
            trace += frontier.copyOf()
        }
        error("Line diff did not converge")
    }

    private fun backtrack(
        trace: List<IntArray>,
        old: List<String>,
        new: List<String>,
        offset: Int,
    ): List<Operation> {
        var x = old.size
        var y = new.size
        val reversed = mutableListOf<Operation>()

        for (distance in trace.lastIndex downTo 1) {
            val previous = trace[distance - 1]
            val diagonal = x - y
            val previousDiagonal = if (
                diagonal == -distance ||
                (diagonal != distance && previous[offset + diagonal - 1] < previous[offset + diagonal + 1])
            ) {
                diagonal + 1
            } else {
                diagonal - 1
            }
            val previousX = previous[offset + previousDiagonal]
            val previousY = previousX - previousDiagonal

            while (x > previousX && y > previousY) {
                reversed += Operation(Kind.EQUAL, old[x - 1])
                x--
                y--
            }
            if (x == previousX) {
                reversed += Operation(Kind.INSERT, new[y - 1])
                y--
            } else {
                reversed += Operation(Kind.DELETE, old[x - 1])
                x--
            }
        }
        while (x > 0 && y > 0) {
            reversed += Operation(Kind.EQUAL, old[x - 1])
            x--
            y--
        }
        while (x > 0) reversed += Operation(Kind.DELETE, old[--x])
        while (y > 0) reversed += Operation(Kind.INSERT, new[--y])
        return reversed.asReversed()
    }
}
