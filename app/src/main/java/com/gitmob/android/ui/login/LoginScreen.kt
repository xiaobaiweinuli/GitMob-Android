package com.gitmob.android.ui.login

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.gitmob.android.R
import com.gitmob.android.auth.AccountInfo
import com.gitmob.android.auth.OAuthManager
import com.gitmob.android.ui.theme.*

@Composable
fun LoginScreen(
    pendingToken: String?,
    onSuccess: () -> Unit,
    onTokenConsumed: () -> Unit = {},
    isReauth: Boolean = false,
    vm: LoginViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state   by vm.state.collectAsState()
    val savedAccounts by vm.savedAccounts.collectAsState()

    // 处理 OAuth 回调：token 用完即焚，防止重入
    LaunchedEffect(pendingToken) {
        when {
            pendingToken == null                  -> Unit
            pendingToken.startsWith("ERROR:")     -> {
                vm.onOAuthError(pendingToken.removePrefix("ERROR:"))
                onTokenConsumed()   // 错误也要消费，避免反复弹错误
            }
            else                                  -> {
                vm.onTokenReceived(pendingToken)
                onTokenConsumed()   // 立即置空，防止二次触发
            }
        }
    }
    // 成功后跳转
    LaunchedEffect(state) {
        if (state is LoginUiState.Success) onSuccess()
    }

    // 有已保存账号且是重新登录场景 → 显示账号选择页
    val showAccountPicker = savedAccounts.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        if (showAccountPicker) {
            // ── 账号选择页 ──────────────────────────────────────────
            AccountPickerContent(
                accounts      = savedAccounts,
                state         = state,
                isReauth      = isReauth,
                onSelectAccount = { vm.switchToAccount(it) },
                onAddAccount  = { OAuthManager.launchOAuth(context, forceReauth = false) },
            )
        } else {
            // ── 全新登录页 ──────────────────────────────────────────
            FreshLoginContent(
                state    = state,
                context  = context,
                isReauth = isReauth,
            )
        }
    }
}

// ── 账号选择器（有已保存账号时）──────────────────────────────────────

@Composable
private fun AccountPickerContent(
    accounts: List<AccountInfo>,
    state: LoginUiState,
    isReauth: Boolean,
    onSelectAccount: (AccountInfo) -> Unit,
    onAddAccount: () -> Unit,
) {
    val c = LocalGmColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(Modifier.height(32.dp))

        // Logo + 标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(
                    "GitMob",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "选择登录账号",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 状态提示（加载/错误）
        AnimatedVisibility(visible = state is LoginUiState.Loading || state is LoginUiState.Error) {
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                when (state) {
                    is LoginUiState.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.bgCard, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Coral,
                        )
                        Text("正在切换账号…", fontSize = 13.sp, color = c.textSecondary)
                    }
                    is LoginUiState.Error -> Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.msg,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    else -> Unit
                }
            }
        }

        // 账号列表
        Text(
            "已授权账号",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = c.textTertiary,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .background(c.bgCard, RoundedCornerShape(16.dp)),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(accounts, key = { it.login }) { account ->
                AccountItemRow(
                    account    = account,
                    isLoading  = state is LoginUiState.Loading,
                    onClick    = { onSelectAccount(account) },
                    c          = c,
                )
                if (account != accounts.last()) {
                    HorizontalDivider(
                        color = c.border, thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 添加账号按钮
        OutlinedButton(
            onClick  = onAddAccount,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(14.dp),
            border   = androidx.compose.foundation.BorderStroke(1.dp, Coral.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.Default.Add, null, tint = Coral, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加账号", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Coral)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "使用 GitHub OAuth 授权登录",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AccountItemRow(
    account: AccountInfo,
    isLoading: Boolean,
    onClick: () -> Unit,
    c: GmColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 头像
        AsyncImage(
            model = account.avatarUrl.let { if (it.contains("?")) it else "$it?s=80" },
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(c.bgItem),
        )
        // 用户信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                account.displayName,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = c.textPrimary,
            )
            Text(
                "@${account.login}",
                fontSize = 12.sp, color = c.textSecondary,
            )
        }
        // 箭头
        Icon(
            Icons.Default.ChevronRight, null,
            tint = c.textTertiary, modifier = Modifier.size(20.dp),
        )
    }
}

// ── 全新登录页（无已保存账号）────────────────────────────────────────

@Composable
private fun FreshLoginContent(
    state: LoginUiState,
    context: android.content.Context,
    isReauth: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "GitMob", fontSize = 36.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = (-1).sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "手机端 GitHub 管理工具", fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))

        when (val s = state) {
            is LoginUiState.Loading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("正在验证…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            is LoginUiState.Error -> {
                val is401 = s.msg.contains("401") || s.msg.contains("token") || s.msg.contains("失效")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (is401) "授权已失效" else "登录失败",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (is401) "你的访问令牌已被撤销或过期，请重新授权登录。" else s.msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp, lineHeight = 18.sp,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                LoginButton(label = "重新授权登录") {
                    OAuthManager.launchOAuth(context, forceReauth = true)
                }
            }
            else -> LoginButton(
                label = if (isReauth) "重新授权登录" else "使用 GitHub 登录",
            ) {
                OAuthManager.launchOAuth(context, forceReauth = isReauth)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "使用 GitHub OAuth 授权登录\n权限：repo · workflow · user · notifications · admin:public_key · delete_repo",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center, lineHeight = 17.sp,
        )
    }
}

@Composable
private fun LoginButton(label: String = "使用 GitHub 登录", onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
