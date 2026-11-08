package com.gitmob.app.data.repository

import com.gitmob.app.data.model.RepoFilters
import com.gitmob.app.data.model.RepoSort
import com.gitmob.app.data.model.RepoVisibility
import com.gitmob.app.data.model.RepositoryCreateOwner
import com.gitmob.app.data.model.RepositoryCreateOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RepoSearchQueryTest {
    @Test
    fun `viewer other user and organization always constrain the owner`() {
        assertEquals("user:viewer fork:true sort:updated-desc", repoSearchQuery(owner("viewer"), RepoFilters()))
        assertEquals("user:other fork:true sort:updated-desc", repoSearchQuery(owner("other"), RepoFilters()))
        assertEquals("org:team fork:true sort:updated-desc", repoSearchQuery(owner("team", organization = true), RepoFilters()))
    }

    @Test
    fun `keyword and all filters form one server intersection including false values`() {
        assertEquals(
            "org:team \"needle\" in:name,description is:private archived:false fork:false sort:stars-asc",
            repoSearchQuery(owner("team", organization = true), RepoFilters(
                query = "  NeeDle  ", visibility = RepoVisibility.PRIVATE,
                isArchived = false, isFork = false, sort = RepoSort.STARS_ASC,
            )),
        )
        assertEquals(
            "user:viewer is:public archived:true fork:only sort:name-desc",
            repoSearchQuery(owner("viewer"), RepoFilters(
                visibility = RepoVisibility.PUBLIC, isArchived = true, isFork = true, sort = RepoSort.NAME_DESC,
            )),
        )
    }

    @Test
    fun `six sort choices are expressed exclusively through search qualifiers`() {
        val expected = mapOf(
            RepoSort.UPDATED_DESC to "updated-desc", RepoSort.UPDATED_ASC to "updated-asc",
            RepoSort.STARS_ASC to "stars-asc", RepoSort.STARS_DESC to "stars-desc",
            RepoSort.NAME_ASC to "name-asc", RepoSort.NAME_DESC to "name-desc",
        )
        assertEquals(RepoSort.entries.toSet(), expected.keys)
        expected.forEach { (sort, qualifier) ->
            assertEquals("user:viewer fork:true sort:$qualifier", repoSearchQuery(owner("viewer"), RepoFilters(sort = sort)))
        }
    }

    @Test
    fun `internal visibility and blank keywords do not create unsupported qualifiers`() {
        val query = repoSearchQuery(owner("viewer"), RepoFilters(query = "  ", visibility = RepoVisibility.INTERNAL))
        assertEquals("user:viewer fork:true sort:updated-desc", query)
        assertFalse(query.contains("internal"))
    }

    @Test
    fun `quotes backslashes and apparent qualifiers remain literal text within the owner`() {
        assertEquals(
            """org:team "gh\" org:other \\path" in:name,description fork:true sort:updated-desc""",
            repoSearchQuery(owner("team", organization = true), RepoFilters(query = """GH" org:other \path""")),
        )
    }

    private fun owner(login: String, organization: Boolean = false) = RepositoryCreateOwner(
        id = login, login = login, name = null, avatarUrl = null,
        type = if (organization) RepositoryCreateOwnerType.ORGANIZATION else RepositoryCreateOwnerType.USER,
        canCreateRepository = false,
    )
}
