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
    }

    @Test
    fun `MAINTAIN 能推送到受保护分支但不能删仓库`() {
        val cap = RepoPermission.MAINTAIN.toCapabilities()
        assertTrue(cap.canPushToProtectedBranch)
        assertTrue(cap.canManageSomeSettings)
        assertFalse(cap.canDeleteRepo)
        assertFalse(cap.canManageCollaborators)
    }

    @Test
    fun `WRITE 能push但不能管理设置`() {
        val cap = RepoPermission.WRITE.toCapabilities()
        assertTrue(cap.canPush)
        assertFalse(cap.canManageSomeSettings)
        assertFalse(cap.canPushToProtectedBranch)
    }

    @Test
    fun `TRIAGE 不能push但能管理IssuePR`() {
        val cap = RepoPermission.TRIAGE.toCapabilities()
        assertFalse(cap.canPush)
        assertTrue(cap.canManageIssuesAndPRs)
    }

    @Test
    fun `READ 什么都不能做`() {
        val cap = RepoPermission.READ.toCapabilities()
        assertFalse(cap.canPush)
        assertFalse(cap.canManageIssuesAndPRs)
    }
}
