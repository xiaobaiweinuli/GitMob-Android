package com.gitmob.app.ui.common

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.R
import com.gitmob.app.data.model.FollowState
import com.gitmob.app.data.model.SocialAccount
import com.gitmob.app.data.model.UserStatus
import com.gitmob.app.ui.home.socialProviderIconRes
import com.gitmob.app.ui.icons.OcticonBadge
import com.gitmob.app.ui.icons.OcticonName

/**
 * 个人资料头部（头像 + 姓名/@login + 代词 + 关注者/关注 + 关注按钮 + 状态气泡 + 简介 + 信息行 + 徽章行）。
 *
 * 抽自 HomeScreen.kt 的 ViewerProfile 渲染区和 ProfileScreen.kt 的 PersonProfileContent 渲染区，
 * 两者使用同一组公开个人资料字段，并用可空参数统一建模：非 null 才渲染对应区块，
 * 为 null 时跳过（不留空白高度，不造假占位）。
 *
 * 为什么不直接把 ViewerProfile / ProfileOwner.Person 作为参数？
 *   - 两个模型字段名不完全对齐（user.followers vs followersCount、user.organizationsCount vs organizationsCount 等），
 *     硬塞 into 一个 sealed class 反而增加调用方适配成本；
 *   - "可空参数 + 非空才渲染"是 Compose 处理"相似但字段不完全一致"场景的惯用写法（参考 Material3 TopAppBar 各参数）。
 *
 * @param avatarUrl 头像 URL（GitHub 的 avatarUrl 一般非 null，但仍保持可空避免 NPE）
 * @param name 显示名（姓名 / 组织名）；为 null 时 fallback 显示 @login
 * @param login GitHub login（@ 后缀）
 * @param pronouns 代词（she/her / he/him 等）；ViewerProfile 有此字段，ProfileOwner.Person 目前也有
 * @param followersCount 关注者数量（用于"X 个关注者"文案 + 点击回调）
 * @param followingCount 关注数量（用于"Y 关注"文案 + 点击回调）
 * @param onFollowersClick 点击"关注者"行的回调；null 表示不可点击
 * @param onFollowingClick 点击"关注"行的回调；null 表示不可点击
 * @param followState 关注按钮状态机（isViewer 时不显示按钮；其余按 viewerCanFollow / viewerIsFollowing 驱动）
 * @param onFollowClick 点击"关注/已关注"按钮的回调（内部 toggle 逻辑由 ViewModel 负责）
 * @param status 状态气泡（emoji + message）；为 null 时整块不渲染
 * @param bio 简介文本；为 null 时不渲染
 * @param location 位置；为 null 时不渲染
 * @param company 公司；为 null 时不渲染
 * @param websiteUrl 个人网站；为 null 时不渲染，点击时自动加 https:// 前缀
 * @param email 邮箱；为 null 时不渲染，点击时发 ACTION_SENDTO mailto: Intent
 * @param socialAccounts 社交账号列表；空列表 / null 时整块不渲染
 * @param isDeveloperProgramMember 徽章-开发者计划成员（公开 Schema isDeveloperProgramMember）
 * @param isBountyHunter 徽章-安全赏金猎人
 * @param isCampusExpert 徽章-校园专家
 * @param isGitHubStar 徽章-GitHub Star
 * @param modifier Modifier
 */
@Composable
fun ProfilePersonHeader(
    avatarUrl: String?,
    name: String?,
    login: String,
    pronouns: String?,
    followersCount: Int,
    followingCount: Int,
    onFollowersClick: (() -> Unit)?,
    onFollowingClick: (() -> Unit)?,
    followState: FollowState,
    onFollowClick: () -> Unit,
    status: UserStatus?,
    bio: String?,
    location: String?,
    company: String?,
    websiteUrl: String?,
    email: String?,
    socialAccounts: List<SocialAccount>?,
    isDeveloperProgramMember: Boolean,
    isBountyHunter: Boolean,
    isCampusExpert: Boolean,
    isGitHubStar: Boolean,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    Column(modifier = modifier) {
        // ---- 1. 头像 + 姓名/@login + 代词 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(name ?: login, style = MaterialTheme.typography.titleLarge)
                Row {
                    Text("@$login", style = MaterialTheme.typography.bodyMedium)
                    pronouns?.let {
                        Text(
                            " · $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- 2. 关注者 / 关注：紧跟 @login 下面，可点击进入对应列表 ----
        Row(modifier = Modifier.padding(top = 12.dp)) {
            if (onFollowersClick != null) {
                Text(
                    stringResource(R.string.common_followers_count, followersCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .clickable(onClick = onFollowersClick),
                )
            } else {
                Text(
                    stringResource(R.string.common_followers_count, followersCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
            if (onFollowingClick != null) {
                Text(
                    stringResource(R.string.common_following_count, followingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onFollowingClick),
                )
            } else {
                Text(
                    stringResource(R.string.common_following_count, followingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 3. 关注按钮：isViewer=true（自己主页）不显示；viewerCanFollow=false 也不显示 ----
        if (!followState.isViewer && followState.viewerCanFollow) {
            Box(modifier = Modifier.padding(top = 12.dp)) {
                if (followState.viewerIsFollowing) {
                    OutlinedButton(onClick = onFollowClick) { Text(stringResource(R.string.common_following)) }
                } else {
                    Button(onClick = onFollowClick) { Text(stringResource(R.string.common_follow)) }
                }
            }
        }

        // ---- 4. 状态气泡（emoji + message）；status.message 非空才渲染整块 ----
        status?.let { s ->
            if (!s.message.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    GitHubEmojiLabel(
                        emoji = s.emoji,
                        text = s.message,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        iconSize = 20.dp,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // ---- 5. 简介 ----
        bio?.takeIf { it.isNotBlank() }?.let {
            Text(
                emojizeGitHubText(it),
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // ---- 6. 位置 / 公司：纯展示，不可点击 ----
        val hasInfoRows = location != null || company != null
        if (hasInfoRows) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                location?.let { InfoRow(Icons.Default.LocationOn, it) }
                company?.let { InfoRow(Icons.Default.Business, it) }
            }
        }

        // ---- 7. 网站 / 邮箱 / 社交账号：图标 + 可点击 ----
        val hasClickableRows = websiteUrl?.isNotBlank() == true ||
            email?.isNotBlank() == true ||
            !socialAccounts.isNullOrEmpty()
        if (hasClickableRows) {
            Column {
                websiteUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    ClickableInfoRow(
                        icon = Icons.Default.Link,
                        text = url,
                        onClick = {
                            val target = if (url.startsWith("http")) url else "https://$url"
                            runCatching { uriHandler.openUri(target) }
                        },
                    )
                }
                email?.takeIf { it.isNotBlank() }?.let { mail ->
                    ClickableInfoRow(
                        icon = Icons.Default.Email,
                        text = mail,
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO, "mailto:$mail".toUri())
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
                socialAccounts?.forEach { account ->
                    val iconRes = socialProviderIconRes(account.provider)
                    if (iconRes != null) {
                        ClickableInfoRow(
                            iconRes = iconRes,
                            text = account.displayName,
                            onClick = { runCatching { uriHandler.openUri(account.url) } },
                        )
                    } else {
                        ClickableInfoRow(
                            icon = Icons.Default.Link,
                            text = account.displayName,
                            onClick = { runCatching { uriHandler.openUri(account.url) } },
                        )
                    }
                }
            }
        }

        // ---- 8. 徽章行：只显示真实存在的 4 个，buildList 过滤后非空才渲染 Row ----
        val badges = buildList {
            if (isDeveloperProgramMember) add(OcticonName.BADGE_DEVELOPER_PROGRAM to stringResource(R.string.common_badge_developer_program))
            if (isBountyHunter) add(OcticonName.BADGE_SECURITY_BOUNTY_HUNTER to stringResource(R.string.common_badge_bounty_hunter))
            if (isCampusExpert) add(OcticonName.BADGE_CAMPUS_EXPERT to stringResource(R.string.common_badge_campus_expert))
            if (isGitHubStar) add(OcticonName.BADGE_GITHUB_STAR to "GitHub Star")
        }
        if (badges.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                badges.forEach { (name, tip) ->
                    OcticonBadge(name = name, tooltipText = tip)
                }
            }
        }
    }
}

/**
 * 位置/公司等纯信息行（不可点击）。
 * 抽成内部 helper 避免 HomeScreen/ProfileScreen 各写一份完全相同的实现。
 */
@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 可点击的信息行（网站/邮箱/社交账号）——Material Icons 重载。
 * 链接统一蓝色 primary 色，图标也用 primary 色与 GitHub 官方视觉对齐。
 */
@Composable
private fun ClickableInfoRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text, modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 可点击的信息行——品牌图标的 drawable 重载（Twitter/X、LinkedIn 等非 Material Icons）。
 * 与上面 ImageVector 重载对称，避免在调用方做 if/else 分派。
 */
@Composable
private fun ClickableInfoRow(
    @androidx.annotation.DrawableRes iconRes: Int,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(text, modifier = Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
    }
}
