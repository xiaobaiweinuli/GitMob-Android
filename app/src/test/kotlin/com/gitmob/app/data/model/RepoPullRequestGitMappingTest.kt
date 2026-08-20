package com.gitmob.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoPullRequestGitMappingTest {
    @Test
    fun `renamed pull request file maps to shared changed file`() {
        val file = RepoPullRequestFile(
            path = "new.md",
            previousPath = "old.md",
            status = "renamed",
            additions = 2,
            deletions = 1,
            changes = 3,
            patch = "@@ -1 +1 @@",
            blobUrl = "https://github.com/blob",
        )

        val mapped = file.toRepoChangedFile()

        assertEquals("new.md", mapped.filename)
        assertEquals("old.md", mapped.previousFilename)
        assertEquals(RepoChangedFileStatus.RENAMED, mapped.status)
        assertEquals(2, mapped.additions)
        assertEquals(1, mapped.deletions)
        assertEquals(file.patch, mapped.patch)
    }

    @Test
    fun `pull request commit maps to shared commit row without fabricated stats`() {
        val mapped = RepoPullRequestCommit(
            oid = "1234567890",
            headline = "Fix docs",
            committedAt = "2026-08-18T00:00:00Z",
            authorLogin = "octocat",
            authorAvatarUrl = "https://github.com/avatar",
        ).toRepoCommitSummary()

        assertEquals("1234567", mapped.abbreviatedOid)
        assertEquals("Fix docs", mapped.headline)
        assertEquals("octocat", mapped.author?.login)
        assertEquals(0, mapped.additions)
        assertEquals(0, mapped.deletions)
        assertNull(mapped.changedFiles)
    }
}
