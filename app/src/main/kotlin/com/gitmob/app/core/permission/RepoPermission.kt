package com.gitmob.app.core.permission

import kotlinx.serialization.Serializable

/**
 * 对应 GraphQL Repository.viewerPermission，五级枚举。
 * 唯一权威数据源：GraphQL 的 viewerPermission 字段。
 * 禁止使用 REST 的 permission 字段做精确判断——该字段是遗留兼容字段，
 * 会把 MAINTAIN 压缩显示成 WRITE、把 TRIAGE 压缩显示成 READ。
 * 详见技能 references/permission-model.md。
 */
@Serializable
enum class RepoPermission { ADMIN, MAINTAIN, WRITE, TRIAGE, READ, NONE }

data class RepoCapabilities(
    val canPush: Boolean,
    val canPushToProtectedBranch: Boolean,
    val canManageIssuesAndPRs: Boolean,
    val canDeleteIssues: Boolean,
    val canManageSomeSettings: Boolean,
    val canDeleteRepo: Boolean,
    val canManageCollaborators: Boolean,
    val canManageBranchProtection: Boolean,
    val canRenameRepo: Boolean = canDeleteRepo,
    val canChangeVisibility: Boolean = canDeleteRepo,
    val canTransferRepo: Boolean = canDeleteRepo,
    val canArchiveRepo: Boolean = canDeleteRepo,
    val canToggleIssues: Boolean = canDeleteRepo,
    val canToggleDiscussions: Boolean = canManageSomeSettings,
    val canDeleteActionRuns: Boolean = canPush,
    /** 入口资格；读取名单仍取决于当前凭据授权。公开仓库的 READ 不能证明协作者身份。 */
    val canViewStargazers: Boolean = false,
) {
    companion object {
        val NONE = RepoCapabilities(
            canPush = false, canPushToProtectedBranch = false, canManageIssuesAndPRs = false,
            canDeleteIssues = false,
            canManageSomeSettings = false, canDeleteRepo = false,
            canManageCollaborators = false, canManageBranchProtection = false,
        )
    }
}

fun RepoPermission.toCapabilities(isArchived: Boolean = false): RepoCapabilities {
    val capabilities = when (this) {
    RepoPermission.ADMIN -> RepoCapabilities(
        canPush = true, canPushToProtectedBranch = true, canManageIssuesAndPRs = true,
        canDeleteIssues = true,
        canManageSomeSettings = true, canDeleteRepo = true,
        canManageCollaborators = true, canManageBranchProtection = true,
        canViewStargazers = true,
    )
    RepoPermission.MAINTAIN -> RepoCapabilities(
        canPush = true, canPushToProtectedBranch = true, canManageIssuesAndPRs = true,
        canDeleteIssues = false,
        canManageSomeSettings = true, canDeleteRepo = false,
        canManageCollaborators = false, canManageBranchProtection = false,
        canViewStargazers = true,
    )
    RepoPermission.WRITE -> RepoCapabilities(
        canPush = true, canPushToProtectedBranch = false, canManageIssuesAndPRs = true,
        canDeleteIssues = false,
        canManageSomeSettings = false, canDeleteRepo = false,
        canManageCollaborators = false, canManageBranchProtection = false,
        canViewStargazers = true,
    )
    RepoPermission.TRIAGE -> RepoCapabilities(
        canPush = false, canPushToProtectedBranch = false, canManageIssuesAndPRs = true,
        canDeleteIssues = false,
        canManageSomeSettings = false, canDeleteRepo = false,
        canManageCollaborators = false, canManageBranchProtection = false,
        canViewStargazers = true,
    )
    RepoPermission.READ, RepoPermission.NONE -> RepoCapabilities.NONE
    }
    return if (!isArchived) capabilities else capabilities.copy(
        canPush = false,
        canPushToProtectedBranch = false,
        canManageIssuesAndPRs = false,
        canDeleteIssues = false,
        canManageSomeSettings = false,
        canRenameRepo = false,
        canChangeVisibility = false,
        canTransferRepo = false,
        canToggleIssues = false,
        canToggleDiscussions = false,
        canDeleteActionRuns = false,
    )
}
