package com.gitmob.android.ui.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gitmob.android.api.GHCodeResult
import com.gitmob.android.api.GHIssue
import com.gitmob.android.api.GHRepo
import com.gitmob.android.api.GHSearchUser
import com.gitmob.android.ui.common.GmBadge
import com.gitmob.android.ui.theme.*
import com.gitmob.android.util.EmojiManager
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 格式化日期
 */
private fun repoFormatDate(iso: String): String = try {
    val odt = OffsetDateTime.parse(iso)
    val beijing = odt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    beijing.format(formatter)
} catch (_: Exception) {
    iso
}

/**
 * 判断颜色是否为浅色
 */
private fun isColorLight(color: Color): Boolean {
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return luminance > 0.5
}

/**
 * 仓库搜索结果卡片，参考远程tab的样式
 */
@Composable
fun SearchRepoCard(
    repo: GHRepo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val c = LocalGmColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        repo.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = c.textPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    AsyncImage(
                        model = repo.owner.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape),
                    )
                    Text(
                        text = repo.owner.login,
                        fontSize = 12.sp,
                        color = c.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (repo.private) GmBadge("私有", RedDim, RedColor)
                if (repo.archived == true) GmBadge("已归档", CoralDim, Coral)
            }
        }

        if (!repo.description.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = repo.description,
                fontSize = 13.sp,
                color = c.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!repo.homepage.isNullOrBlank() && repo.homepage != "null") {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repo.homepage))
                    ctx.startActivity(intent)
                }
            ) {
                Icon(Icons.Default.Link, null, tint = BlueColor, modifier = Modifier.size(14.dp))
                Text(
                    text = repo.homepage,
                    fontSize = 12.sp,
                    color = BlueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!repo.language.isNullOrBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8FAAFF))
                        )
                        Text(
                            text = repo.language,
                            fontSize = 12.sp,
                            color = c.textSecondary
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.StarBorder, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Text(
                        text = repo.stars.toString(),
                        fontSize = 12.sp,
                        color = c.textSecondary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Text(
                        text = repo.forks.toString(),
                        fontSize = 12.sp,
                        color = c.textSecondary
                    )
                }
                if (repo.updatedAt != null) {
                    Text(
                        text = repoFormatDate(repo.updatedAt),
                        fontSize = 12.sp,
                        color = c.textTertiary
                    )
                }
            }
            if (repo.topics.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    repo.topics.forEach { topic ->
                        Text(
                            text = topic,
                            fontSize = 9.sp,
                            color = c.textTertiary,
                            modifier = Modifier
                                .background(BlueDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 用户/组织搜索结果卡片
 */
@Composable
fun SearchUserCard(
    user: GHSearchUser,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = c.bgCard),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = user.name ?: user.login,
                    style = MaterialTheme.typography.titleMedium,
                    color = Coral,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (user.name != null) {
                    Text(
                        text = user.login,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (user.type == "Organization") "组织" else "用户",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary
                )
            }
        }
    }
}

/**
 * 代码搜索结果卡片
 */
@Composable
fun SearchCodeCard(
    code: GHCodeResult,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current
    val fragment = code.textMatches.firstOrNull()?.fragment

    /**
     * 创建高亮文本，将查询词用 Coral 颜色高亮显示
     */
    fun buildHighlightedText(text: String, query: String) = buildAnnotatedString {
        if (query.isBlank()) {
            append(text)
            return@buildAnnotatedString
        }

        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var lastIndex = 0
        var index = lowerText.indexOf(lowerQuery, lastIndex)

        while (index != -1) {
            // 添加查询词之前的文本
            append(text.substring(lastIndex, index))
            // 高亮查询词
            withStyle(
                style = SpanStyle(
                    color = Coral,
                    fontWeight = FontWeight.Bold,
                    background = Color.Unspecified
                )
            ) {
                append(text.substring(index, index + lowerQuery.length))
            }
            lastIndex = index + lowerQuery.length
            index = lowerText.indexOf(lowerQuery, lastIndex)
        }
        // 添加剩余文本
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = code.repository.owner.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape),
            )
            Text(
                text = code.repository.fullName,
                fontSize = 13.sp,
                color = Coral,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = code.path,
            fontSize = 12.sp,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
        )

        if (fragment != null) {
            Spacer(Modifier.height(8.dp))

            val lines = remember(fragment) { fragment.lines().take(8) }
            val previewBg = c.bgItem
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(previewBg, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                if (lines.isNotEmpty()) {
                    lines.forEachIndexed { index, line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 0.5.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 10.sp,
                                color = c.textTertiary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(24.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = buildHighlightedText(line.ifBlank { " " }, query),
                                fontSize = 10.sp,
                                color = c.textSecondary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                softWrap = true,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/**
 * Issue/PR 搜索结果卡片，参考详情页样式
 */
@Composable
fun SearchIssueCard(
    issue: GHIssue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalGmColors.current
    val ctx = LocalContext.current

    if (issue.isPR) {
        // PR 样式
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(c.bgCard, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态徽章
                Row(
                    modifier = Modifier
                        .background(if (issue.state == "open") GreenDim else c.bgItem, RoundedCornerShape(20.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.CallMerge,
                        null,
                        tint = if (issue.state == "open") Green else c.textTertiary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        if (issue.state == "open") "打开" else "已关闭",
                        fontSize = 11.sp,
                        color = if (issue.state == "open") Green else c.textTertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    issue.title,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    color = c.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (issue.labels.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    issue.labels.forEach { label ->
                        val bg = try { Color(android.graphics.Color.parseColor("#${label.color}")) } catch (_: Exception) { c.bgItem }
                        val fg = if (isColorLight(bg)) Color(0xFF24292F) else Color.White
                        Text(
                            EmojiManager.replaceEmojiMarkdown(label.name, ctx),
                            fontSize = 10.sp, color = fg, fontWeight = FontWeight.Medium,
                            modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "#${issue.number}",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "·",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    issue.user.login,
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    "·",
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                Text(
                    repoFormatDate(issue.createdAt),
                    fontSize = 11.sp,
                    color = c.textTertiary,
                )
                val commentCount = issue.comments ?: 0
                if (commentCount > 0) {
                    Text(
                        "·",
                        fontSize = 11.sp,
                        color = c.textTertiary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Comment,
                            contentDescription = "评论数",
                            tint = c.textTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = commentCount.toString(),
                            fontSize = 11.sp,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }
    } else {
        // Issue 样式
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(c.bgCard, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Circle,
                    null,
                    tint = if (issue.state == "open") Green else RedColor,
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    issue.title,
                    fontSize = 13.sp,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (issue.labels.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    issue.labels.forEach { label ->
                        val labelColor = try { Color(android.graphics.Color.parseColor("#${label.color}")) } catch (_: Exception) { Coral }
                        val textColor = if (isColorLight(labelColor)) Color.Black else Color.White
                        Surface(
                            color = labelColor,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                EmojiManager.replaceEmojiMarkdown(label.name, ctx),
                                fontSize = 10.sp,
                                color = textColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "#${issue.number} · ${issue.user.login}",
                        fontSize = 11.sp,
                        color = c.textTertiary,
                    )
                    Text(
                        "·",
                        fontSize = 11.sp,
                        color = c.textTertiary,
                    )
                    Text(
                        repoFormatDate(issue.createdAt),
                        fontSize = 11.sp,
                        color = c.textTertiary,
                    )
                }
                val commentCount = issue.comments ?: 0
                if (commentCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Comment,
                            contentDescription = "评论数",
                            tint = c.textTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = commentCount.toString(),
                            fontSize = 11.sp,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }
    }
}
