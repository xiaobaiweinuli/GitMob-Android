package com.gitmob.app.core.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineDiffGeneratorTest {
    @Test
    fun `相同文本没有差异`() {
        assertNull(LineDiffGenerator.generate("same\n", "same\n"))
    }

    @Test
    fun `生成新增删除和上下文行号`() {
        val diff = requireNotNull(LineDiffGenerator.generate("a\nold\nz\n", "a\nnew\nadded\nz\n"))

        assertEquals(
            listOf(
                DiffLineType.CONTEXT,
                DiffLineType.DELETION,
                DiffLineType.ADDITION,
                DiffLineType.ADDITION,
                DiffLineType.CONTEXT,
            ),
            diff.hunks.single().lines.map { it.type },
        )
        assertEquals(listOf(1, 2, null, null, 3), diff.hunks.single().lines.map { it.oldLine })
        assertEquals(listOf(1, null, 2, 3, 4), diff.hunks.single().lines.map { it.newLine })
    }

    @Test
    fun `空文本与新增删除文件都能比较`() {
        val added = requireNotNull(LineDiffGenerator.generate("", "one\ntwo"))
        assertEquals(listOf(DiffLineType.ADDITION, DiffLineType.ADDITION), added.hunks.single().lines.map { it.type })

        val deleted = requireNotNull(LineDiffGenerator.generate("one\ntwo", ""))
        assertEquals(listOf(DiffLineType.DELETION, DiffLineType.DELETION), deleted.hunks.single().lines.map { it.type })
    }

    @Test
    fun `重复行选择最短编辑路径`() {
        val diff = requireNotNull(LineDiffGenerator.generate("a\na\nb", "a\nb\nb"))
        assertEquals(1, diff.hunks.single().lines.count { it.type == DiffLineType.DELETION })
        assertEquals(1, diff.hunks.single().lines.count { it.type == DiffLineType.ADDITION })
    }

    @Test
    fun `尾部换行不制造空白行`() {
        val diff = requireNotNull(LineDiffGenerator.generate("a\n", "a\nb\n"))
        assertEquals(listOf("a", "b"), diff.hunks.single().lines.map { it.text })
    }
}
