package com.gitmob.app.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.data.model.SimpleOrg

/**
 * "选择组织"底部弹窗——列出当前登录用户或指定用户所在的组织，点击进入对应组织的资料页
 * （走 ProfileScreen 统一的 repositoryOwner 查询，组织和用户共用同一套路由和请求）。
 *
 * 从 ui/home/OrganizationsBottomSheet 提升到 ui/common/，因为：
 *   - HomeScreen（当前登录用户，viewer.organizations）和 ProfileScreen（他人个人主页，user(login:).organizations）
 *     都要用到同一个弹窗组件，两者除了传入的 organizations 数据来源不同，视觉和交互完全一致。
 *   - UserRepository.getOrganizations(login:) 已经参数化，两种模式共用同一个 Repository 方法。
 *
 * @param organizations 组织列表（SimpleOrg = login + name + avatarUrl，轻量预览不分页）
 * @param isLoading 是否正在加载（首次打开弹窗时显示转圈占位）
 * @param onDismiss 关闭弹窗回调
 * @param onOrgClick 点击某个组织的回调，传入该组织的 login，调用方负责跳转 ProfileScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationsBottomSheet(
    organizations: List<SimpleOrg>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onOrgClick: (login: String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "选择组织",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                organizations.forEachIndexed { index, org ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOrgClick(org.login) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = org.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                        )
                        Text(
                            org.name ?: org.login,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .weight(1f),
                        )
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                    if (index != organizations.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}
