package com.gitmob.app.ui.login

import com.gitmob.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(
    sessionId: String,
    viewModel: LoginViewModel = hiltViewModel(key = sessionId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accountState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelAuthorization() } }

    // LoginScreen 不在 MainTabsScaffold 管辖范围内（没有内层 Scaffold 加 padding），
    // 启用 enableEdgeToEdge() 后必须自己处理 systemBars insets，
    // 否则输入框会被底部导航栏/顶部状态栏盖住。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .wrapContentWidth()
            .widthIn(max = 680.dp),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(if (accounts.accounts.isEmpty()) R.string.accounts_add_description else R.string.accounts_choose_description), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(accounts.accounts, key = { it.key }) { account ->
            AccountOption(account, selected = false, switching = state.switchingKey == account.key, enabled = !state.isLoading) {
                viewModel.switchAccount(account.key)
            }
        }
        item { AccountAuthActions(!state.isLoading, viewModel::openPat, viewModel::openDeviceFlow) }
        if (state.inlineErrorRes != null && state.authSheet == null) item {
            Text(stringResource(state.inlineErrorRes!!), color = MaterialTheme.colorScheme.error)
        }
    }
    AccountAuthSheet(viewModel)
}
