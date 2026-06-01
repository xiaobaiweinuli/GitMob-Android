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
import com.gitmob.android.auth.AuthType
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
    var showTokenLoginDialog by remember { mutableStateOf(false) }

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

    // 只有在 Idle 或 Loading 状态且有已保存账号时才显示账号选择页
    // 避免登录成功前 savedAccounts 变化导致闪一下
    val showAccountPicker = savedAccounts.isNotEmpty() && 
        (state is LoginUiState.Idle || state is LoginUiState.Loading)

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
                onTokenLogin  = { showTokenLoginDialog = true },
            )
        } else {
            // ── 全新登录页 ──────────────────────────────────────────
            FreshLoginContent(
                state          = state,
                context        = context,
                isReauth       = isReauth,
                onTokenLogin   = { showTokenLoginDialog = true },
            )
        }

        // Token 登录弹窗
        if (showTokenLoginDialog) {
            TokenLoginDialog(
                onDismiss     = { showTokenLoginDialog = false },
                onTokenSubmit = { token ->
                    vm.onManualTokenReceived(token)
                    showTokenLoginDialog = false
                },
                state         = state,
            )
        }
        
        // 确认替换 OAuth 账号弹窗
        if (state is LoginUiState.ConfirmReplaceOAuth) {
            val confirmState = state as LoginUiState.ConfirmReplaceOAuth
            ConfirmReplaceOAuthDialog(
                login              = confirmState.login,
                onConfirm          = {
                    vm.confirmReplaceOAuth(
                        token               = confirmState.token,
                        login               = confirmState.login,
                        name                = confirmState.name,
                        email               = confirmState.email,
                        avatarUrl           = confirmState.avatarUrl,
                        existingOAuthToken  = confirmState.existingOAuthToken
                    )
                },
                onSkip             = {
                    vm.skipRevokeAndLogin(
                        token     = confirmState.token,
                        login     = confirmState.login,
                        name      = confirmState.name,
                        email     = confirmState.email,
                        avatarUrl = confirmState.avatarUrl
                    )
                },
                onCancel           = {
                    vm.cancelTokenLogin()
                },
                onDismiss          = {
                    vm.cancelTokenLogin()
                },
            )
        }
        
        // 确认替换 Token 账号弹窗（OAuth 登录时）
        if (state is LoginUiState.ConfirmReplaceToken) {
            val confirmState = state as LoginUiState.ConfirmReplaceToken
            ConfirmReplaceTokenDialog(
                login              = confirmState.login,
                onUseOAuth         = {
                    vm.confirmUseOAuthKeepToken(
                        oauthToken = confirmState.oauthToken,
                        login      = confirmState.login,
                        name       = confirmState.name,
                        email      = confirmState.email,
                        avatarUrl  = confirmState.avatarUrl
                    )
                },
                onKeepToken        = {
                    vm.keepTokenAndRevokeOAuth(
                        oauthToken = confirmState.oauthToken
                    )
                },
                onDismiss          = { /* 不处理，必须选择一个选项 */ },
            )
        }
        
        // 确认替换旧 OAuth 账号弹窗（OAuth 登录时）
        if (state is LoginUiState.ConfirmReplaceOldOAuth) {
            val confirmState = state as LoginUiState.ConfirmReplaceOldOAuth
            ConfirmReplaceOldOAuthDialog(
                login              = confirmState.login,
                onUseNewOAuth      = {
                    vm.confirmUseNewOAuth(
                        newOAuthToken = confirmState.newOAuthToken,
                        login         = confirmState.login,
                        name          = confirmState.name,
                        email         = confirmState.email,
                        avatarUrl     = confirmState.avatarUrl,
                        oldOAuthToken = confirmState.oldOAuthToken
                    )
                },
                onKeepOldOAuth     = {
                    vm.keepOldOAuthAndRevokeNew(
                        newOAuthToken = confirmState.newOAuthToken
                    )
                },
                onDismiss          = { /* 不处理，必须选择一个选项 */ },
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
    onTokenLogin: () -> Unit,
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
            Text("OAuth 授权登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Coral)
        }

        Spacer(Modifier.height(8.dp))

        // Token 登录按钮
        OutlinedButton(
            onClick  = onTokenLogin,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(14.dp),
            border   = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("使用 Token 登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    account.displayName,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = c.textPrimary,
                )
                if (account.authType == AuthType.TOKEN) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "Token",
                            fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
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
    onTokenLogin: () -> Unit,
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
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTokenLogin,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    ),
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("使用 Token 登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            else -> {
                LoginButton(
                    label = if (isReauth) "重新授权登录" else "使用 GitHub 登录",
                ) {
                    OAuthManager.launchOAuth(context, forceReauth = isReauth)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTokenLogin,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary
                    ),
                ) {
                    Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("使用 Token 登录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "使用 GitHub OAuth 授权登录\n权限：repo · workflow · user · notifications · delete_repo · admin.org",
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

// ── Token 登录弹窗 ─────────────────────────────────────────
@Composable
private fun TokenLoginDialog(
    onDismiss: () -> Unit,
    onTokenSubmit: (String) -> Unit,
    state: LoginUiState,
) {
    var tokenInput by remember { mutableStateOf("") }
    val isLoading = state is LoginUiState.Loading

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        confirmButton = {
            Button(
                onClick = { onTokenSubmit(tokenInput) },
                enabled = tokenInput.isNotBlank() && !isLoading,
            ) {
                Text("登录")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text("取消")
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Key, contentDescription = null)
                Text("使用 Token 登录")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "请输入 GitHub Personal Access Token (PAT)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token") },
                    placeholder = { Text("ghp_...") },
                    singleLine = true,
                    enabled = !isLoading,
                )
                if (state is LoginUiState.Error) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            state.msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                Text(
                    "所需权限：repo, workflow, user, notifications, delete_repo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// ── 确认替换 OAuth 账号弹窗 ──────────────────────────────────────
@Composable
private fun ConfirmReplaceOAuthDialog(
    login: String,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("撤销并使用 Token")
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
                TextButton(onClick = onSkip) {
                    Text("保留 OAuth 继续")
                }
            }
        },
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("检测到同一账号已通过 OAuth 登录")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "你当前账号 @$login 已通过 OAuth 授权登录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "你要撤销原 OAuth Token 并切换到 Token 登录吗？",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "撤销并使用 Token",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "撤销原 OAuth Token，更安全（推荐）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "保留 OAuth 继续",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "保留原 OAuth Token，直接使用 Token 登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "取消",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "取消操作，继续使用当前 OAuth 登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                }
            }
        },
    )
}

// ── 确认替换 Token 账号弹窗（OAuth 登录时）─────────────────────────────────────
@Composable
private fun ConfirmReplaceTokenDialog(
    login: String,
    onUseOAuth: () -> Unit,
    onKeepToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onUseOAuth) {
                Text("使用 OAuth 登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepToken) {
                Text("保持 Token 登录")
            }
        },
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("检测到同一账号已使用 Token 登录")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "你当前账号 @$login 已通过 Token 登录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Token 无法自动撤销，请选择你的操作：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "使用 OAuth 登录",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "保留 Token 账号，切换到 OAuth 登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "保持 Token 登录",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "撤销新产生的 OAuth Token，保持当前 Token 登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

// ── 确认替换旧 OAuth 账号弹窗（OAuth 登录时）─────────────────────────────────────
@Composable
private fun ConfirmReplaceOldOAuthDialog(
    login: String,
    onUseNewOAuth: () -> Unit,
    onKeepOldOAuth: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onUseNewOAuth) {
                Text("使用新 OAuth")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepOldOAuth) {
                Text("保持旧 OAuth")
            }
        },
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text("检测到同一账号已通过 OAuth 登录")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "你当前账号 @$login 已通过 OAuth 授权登录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "你要使用新的 OAuth Token 替换旧的吗？",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "使用新 OAuth",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "撤销旧 OAuth Token，使用新 OAuth 登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "保持旧 OAuth",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "撤销新产生的 OAuth Token，保持当前旧 OAuth 登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}
