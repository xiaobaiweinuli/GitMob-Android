package com.gitmob.app.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gitmob.app.R

/**
 * "关于"页面——整页导航（不是弹窗），布局结构照抄 KernelSU 的 About 页面
 * （返回箭头 + 标题，居中的图标+名称+版本，往下是分组卡片的链接列表），
 * 每行保留图标+副标题（这一点和 KernelSU 纯文字扁平版不同，是刻意的折中，
 * 见方案讨论），具体内容目前和参照的 GitMob 项目保持一致，后续由用户自行调整
 * 各卡片的标题和链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.size(80.dp),
                    ) {
                        AsyncImage(
                            model = R.drawable.ic_app_logo,
                            contentDescription = "GitMob Logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        "GitMob",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "v0.1.0", // TODO: 从 BuildConfig.VERSION_NAME 动态读取，而不是写死
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                AboutSection(title = "开发者") {
                    AboutLinkRow(
                        icon = null,
                        label = "xiaobaiweinuli",
                        sub = "点击查看 GitHub 主页",
                        url = "https://github.com/xiaobaiweinuli",
                    )
                }
            }

            item {
                AboutSection(title = "项目") {
                    AboutLinkRow(
                        icon = Icons.Default.Code,
                        label = "GitHub 仓库",
                        sub = "xiaobaiweinuli/GitMob-Android · Apache 2.0",
                        url = "https://github.com/xiaobaiweinuli/GitMob-Android",
                    )
                }
            }

            item {
                AboutSection(title = "权限") {
                    AboutLinkRow(
                        icon = Icons.Default.Security,
                        label = "GitHub 授权管理",
                        sub = "查看、撤销 GitMob 对你账号的授权",
                        url = "https://github.com/settings/connections/applications/Ov23liP9mC2HXALHsFpk",
                    )
                }
            }

            item {
                AboutSection(title = "社区") {
                    AboutLinkRow(
                        icon = Icons.AutoMirrored.Default.Send,
                        label = "Telegram 群组",
                        sub = "t.me/MyResNav",
                        url = "https://t.me/MyResNav",
                    )
                }
            }

            // Push route 底部：navigationBars + captionBar 高度
            item(key = "bottom_spacer") {
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues()
                                .calculateBottomPadding(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(shape = RoundedCornerShape(16.dp)) {
            content()
        }
    }
}

@Composable
private fun AboutLinkRow(icon: ImageVector?, label: String, sub: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.padding(start = if (icon != null) 16.dp else 0.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
