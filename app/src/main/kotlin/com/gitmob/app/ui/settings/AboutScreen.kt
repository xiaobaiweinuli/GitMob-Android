package com.gitmob.app.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gitmob.app.BuildConfig
import com.gitmob.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_app_logo),
                            contentDescription = stringResource(R.string.about_logo_desc),
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
                        "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.about_section_developer)) {
                    AboutLinkRow(
                        icon = Icons.Default.Person,
                        label = "xiaobaiweinuli",
                        sub = stringResource(R.string.about_developer_sub),
                        url = "https://github.com/xiaobaiweinuli",
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.about_section_project)) {
                    AboutLinkRow(
                        icon = Icons.Default.Code,
                        label = stringResource(R.string.about_repo_label),
                        sub = "xiaobaiweinuli/GitMob-Android · Apache 2.0",
                        url = "https://github.com/xiaobaiweinuli/GitMob-Android",
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.about_section_permissions)) {
                    AboutLinkRow(
                        icon = Icons.Default.Security,
                        label = stringResource(R.string.about_auth_label),
                        sub = stringResource(R.string.about_auth_sub),
                        url = "https://github.com/settings/connections/applications/Ov23liP9mC2HXALHsFpk",
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.about_section_community)) {
                    AboutLinkRow(
                        icon = Icons.AutoMirrored.Default.Send,
                        label = stringResource(R.string.about_telegram_label),
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
private fun AboutLinkRow(icon: ImageVector?, label: String, sub: String, url: String) {
    val context = LocalContext.current
    SettingsNavigationRow(
        icon = icon,
        title = label,
        summary = sub,
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
    )
}
