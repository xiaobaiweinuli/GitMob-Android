package com.gitmob.app.core.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯函数测试，不需要任何 Mock，五级权限全部覆盖，见 references/testing.md */
class RepoCapabilitiesTest {
    @Test
    fun `ADMIN 拥有全部能力`() {
        val cap = RepoPermission.ADMIN.toCapabilities()
        assertTrue(cap.canDeleteRepo)
        assertTrue(cap.canManageCollaborators)
        assertTrue(cap.canManageBranchProtection)
        assertTrue(cap.canDeleteIssues)
    }

    @Test
    fun `MAINTAIN 能推送到受保护分支但不能删仓库`() {
        val cap = RepoPermission.MAINTAIN.toCapabilities()
        assertTrue(cap.canPushToProtectedBranch)
        assertTrue(cap.canManageSomeSettings)
        assertFalse(cap.canDeleteRepo)
        assertFalse(cap.canManageCollaborators)
        assertFalse(cap.canDeleteIssues)
    }

    @Test
    fun `WRITE 能push但不能管理设置`() {
        val cap = RepoPermission.WRITE.toCapabilities()
        assertTrue(cap.canPush)
        assertFalse(cap.canManageSomeSettings)
        assertFalse(cap.canPushToProtectedBranch)
        assertFalse(cap.canDeleteIssues)
    }

    @Test
    fun `TRIAGE 不能push但能管理IssuePR`() {
        val cap = RepoPermission.TRIAGE.toCapabilities()
        assertFalse(cap.canPush)
        assertTrue(cap.canManageIssuesAndPRs)
        assertFalse(cap.canDeleteIssues)
    }

    @Test
    fun `READ 什么都不能做`() {
        val cap = RepoPermission.READ.toCapabilities()
        assertFalse(cap.canPush)
        assertFalse(cap.canManageIssuesAndPRs)
        assertFalse(cap.canDeleteIssues)
    }

    @Test
    fun `NONE has no repository capabilities`() {
        val cap = RepoPermission.NONE.toCapabilities()
        assertFalse(cap.canPush)
        assertFalse(cap.canManageIssuesAndPRs)
        assertFalse(cap.canDeleteIssues)
    }

    @Test
    fun `destructive repository actions require admin`() {
        assertTrue(RepoPermission.ADMIN.toCapabilities().canDeleteRepo)
        assertTrue(RepoPermission.ADMIN.toCapabilities().canTransferRepo)
        assertFalse(RepoPermission.MAINTAIN.toCapabilities().canDeleteRepo)
        assertFalse(RepoPermission.WRITE.toCapabilities().canTransferRepo)
    }

    @Test
    fun `stargazer entry requires an unambiguous collaborator role and stays available when archived`() {
        for (permission in listOf(RepoPermission.ADMIN, RepoPermission.MAINTAIN, RepoPermission.WRITE, RepoPermission.TRIAGE)) {
            assertTrue(permission.toCapabilities().canViewStargazers)
            assertTrue(permission.toCapabilities(isArchived = true).canViewStargazers)
        }
        for (permission in listOf(RepoPermission.READ, RepoPermission.NONE)) {
            assertFalse(permission.toCapabilities().canViewStargazers)
            assertFalse(permission.toCapabilities(isArchived = true).canViewStargazers)
        }
    }

    @Test
    fun `action run deletion follows push permission and archived lock`() {
        assertTrue(RepoPermission.ADMIN.toCapabilities().canDeleteActionRuns)
        assertTrue(RepoPermission.MAINTAIN.toCapabilities().canDeleteActionRuns)
        assertTrue(RepoPermission.WRITE.toCapabilities().canDeleteActionRuns)
        assertFalse(RepoPermission.TRIAGE.toCapabilities().canDeleteActionRuns)
        assertFalse(RepoPermission.READ.toCapabilities().canDeleteActionRuns)
        assertFalse(RepoPermission.ADMIN.toCapabilities(isArchived = true).canDeleteActionRuns)
    }
    @Test
    fun ` repository Actions administration is separate from execution `() {
        for (permission in RepoPermission.entries) {
            org.junit.Assert.assertEquals(permission == RepoPermission.ADMIN, permission.toCapabilities().canManageRepositoryActions)
            assertFalse(permission.toCapabilities(isArchived = true).canManageRepositoryActions)
        }
        assertTrue(RepoPermission.WRITE.toCapabilities().canPush)
        assertTrue(RepoPermission.MAINTAIN.toCapabilities().canPush)
    }
}
