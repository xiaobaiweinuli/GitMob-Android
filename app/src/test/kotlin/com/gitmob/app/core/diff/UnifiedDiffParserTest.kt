package com.gitmob.app.core.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnifiedDiffParserTest {
    @Test
    fun `解析新增删除和上下文行号`() {
        val diff = UnifiedDiffParser.parse("@@ -1,2 +1,3 @@\n line\n-old\n+new\n+added")
        requireNotNull(diff)
        assertEquals(1, diff.hunks.size)
        assertEquals(DiffLineType.CONTEXT, diff.hunks[0].lines[0].type)
        assertEquals(1, diff.hunks[0].lines[0].oldLine)
        assertEquals(1, diff.hunks[0].lines[0].newLine)
        assertEquals(DiffLineType.DELETION, diff.hunks[0].lines[1].type)
        assertEquals(DiffLineType.ADDITION, diff.hunks[0].lines[2].type)
    }

    @Test
    fun `空补丁返回空`() {
        assertNull(UnifiedDiffParser.parse(null))
        assertNull(UnifiedDiffParser.parse(""))
    }
}
