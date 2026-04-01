package com.gitmob.android.ui.repo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gitmob.android.api.GHRepo


/**
 * 仓库权限信息
 *
 * @property isOwner 是否是仓库所有者
 * @property canWrite 是否有写入权限（所有者或协作者）
 */
data class RepoPermission(
    val isOwner: Boolean = false,
    val canWrite: Boolean = false,
)


/**
 * 计算仓库权限信息
 *
 * @param repo 仓库对象
 * @param userLogin 当前登录用户名
 * @return 仓库权限信息
 */
@Composable
fun rememberRepoPermission(repo: GHRepo?, userLogin: String): RepoPermission {
    return remember(repo, userLogin) {
        val isOwner = repo?.owner?.login == userLogin
        RepoPermission(
            isOwner = isOwner,
            canWrite = isOwner,
        )
    }
}


/**
 * 仅在有权限时才显示的组件
 *
 * @param permission 仓库权限
 * @param requireOwner 是否要求必须是所有者
 * @param requireWrite 是否要求必须有写入权限
 * @param content 要显示的内容
 */
@Composable
fun PermissionRequired(
    permission: RepoPermission,
    requireOwner: Boolean = false,
    requireWrite: Boolean = false,
    content: @Composable () -> Unit,
) {
    val hasPermission = when {
        requireOwner -> permission.isOwner
        requireWrite -> permission.canWrite
        else -> false
    }
    
    if (hasPermission) {
        content()
    }
}
