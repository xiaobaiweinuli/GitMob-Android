package com.gitmob.android.ui.repo

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gitmob.android.api.GHOrg
import com.gitmob.android.api.GHRepo

/**
 * 仓库权限信息
 *
 * @property isOwner     仓库属于登录用户本人
 * @property isOrgMember 仓库属于登录用户所在的组织（isOwner 为 false 时可能为 true）
 * @property canWrite    有写入权限（isOwner 或 isOrgMember）
 */
data class RepoPermission(
    val isOwner: Boolean = false,
    val isOrgMember: Boolean = false,
    val canWrite: Boolean = false,
)

/**
 * 计算仓库权限（Composable 版本，带 remember 缓存）
 *
 * @param repo      仓库对象（可为 null，权限全为 false）
 * @param userLogin 当前登录用户名
 * @param userOrgs  当前登录用户所属的组织列表
 */
@Composable
fun rememberRepoPermission(
    repo: GHRepo?,
    userLogin: String,
    userOrgs: List<GHOrg> = emptyList(),
): RepoPermission {
    return remember(repo, userLogin, userOrgs) {
        calcRepoPermission(repo, userLogin, userOrgs)
    }
}

/**
 * 非 Composable 版本，供 VM 或列表卡片使用
 */
fun calcRepoPermission(
    repo: GHRepo?,
    userLogin: String,
    userOrgs: List<GHOrg> = emptyList(),
): RepoPermission {
    if (repo == null || userLogin.isBlank()) return RepoPermission()
    val isOwner     = repo.owner.login.equals(userLogin, ignoreCase = true)
    val isOrgMember = !isOwner && userOrgs.any { org ->
        org.login.equals(repo.owner.login, ignoreCase = true)
    }
    return RepoPermission(
        isOwner     = isOwner,
        isOrgMember = isOrgMember,
        canWrite    = isOwner || isOrgMember,
    )
}

/**
 * 仓库列表级别权限：控制卡片操作按钮 + TopBar 添加按钮
 *
 * @property canManageRepos 可以新建/删除/重命名仓库（自己的列表或所属组织列表）
 * @property isOwnList      当前展示的是登录用户自己的仓库（currentContext == null）
 * @property isOrgList      当前展示的是登录用户所属组织的仓库
 * @property isOtherUser    当前展示的是其他用户/组织的仓库（只读）
 */
data class RepoListPermission(
    val canManageRepos: Boolean = false,
    val isOwnList: Boolean = false,
    val isOrgList: Boolean = false,
    val isOtherUser: Boolean = false,
)

/**
 * 计算仓库列表权限
 *
 * @param currentContextLogin  当前展示的用户/组织 login（null = 登录用户自己）
 * @param userLogin            当前登录用户名
 * @param userOrgs             当前登录用户所属组织列表
 */
fun calcRepoListPermission(
    currentContextLogin: String?,
    userLogin: String,
    userOrgs: List<GHOrg> = emptyList(),
): RepoListPermission {
    if (currentContextLogin == null) {
        return RepoListPermission(canManageRepos = true, isOwnList = true)
    }
    val isOrgMember = userOrgs.any { it.login.equals(currentContextLogin, ignoreCase = true) }
    val isOtherUser = !isOrgMember && !currentContextLogin.equals(userLogin, ignoreCase = true)
    return RepoListPermission(
        canManageRepos = isOrgMember,
        isOrgList      = isOrgMember,
        isOtherUser    = isOtherUser,
    )
}

/**
 * 仅在满足权限条件时渲染内容，并支持检查仓库是否已归档
 *
 * @param permission         仓库权限
 * @param requireOwner       是否需要所有者权限
 * @param requireWrite       是否需要写入权限
 * @param isArchived         仓库是否已归档
 * @param allowWhenArchived  即使仓库已归档也允许操作（例如取消归档）
 * @param content            要渲染的内容
 */
@Composable
fun PermissionRequired(
    permission: RepoPermission,
    requireOwner: Boolean = false,
    requireWrite: Boolean = false,
    isArchived: Boolean = false,
    allowWhenArchived: Boolean = false,
    content: @Composable () -> Unit,
) {
    val hasPermission = when {
        requireOwner -> permission.isOwner
        requireWrite -> permission.canWrite
        else -> false
    }
    val isAllowed = hasPermission && (!isArchived || allowWhenArchived)
    if (isAllowed) content()
}

/**
 * 支持归档状态的 DropdownMenuItem，归档时自动禁用并置灰
 *
 * @param text               菜单项文本
 * @param isArchived         仓库是否已归档
 * @param allowWhenArchived  即使仓库已归档也允许操作
 * @param leadingIcon        前置图标
 * @param onClick            点击回调
 */
@Composable
fun ArchivedAwareDropdownMenuItem(
    text: @Composable () -> Unit,
    isArchived: Boolean = false,
    allowWhenArchived: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val enabled = !isArchived || allowWhenArchived
    val contentColor = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
    
    DropdownMenuItem(
        text = {
            androidx.compose.material3.ProvideTextStyle(
                value = androidx.compose.material3.LocalTextStyle.current.copy(color = contentColor)
            ) {
                text()
            }
        },
        leadingIcon = leadingIcon?.let { icon ->
            {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalContentColor provides contentColor
                ) {
                    icon()
                }
            }
        },
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        enabled = enabled
    )
}
