package com.gitmob.app.data.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RepoFiltersTest {
    @Test
    fun `规范化不随系统语言改变且保留有效筛选`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val filters = RepoFilters(query = "  ISSUE  ", visibility = RepoVisibility.PRIVATE,
                isArchived = false, isFork = true, sort = RepoSort.NAME_ASC)
            assertEquals(filters.copy(query = "issue"), filters.normalized())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `内部筛选恢复为全部但数据可见性仍可解析`() {
        assertEquals(RepoFilters(), RepoFilters(visibility = RepoVisibility.INTERNAL).normalized())
        assertEquals(RepoVisibility.INTERNAL, RepoVisibility.valueOf("INTERNAL"))
    }
}
