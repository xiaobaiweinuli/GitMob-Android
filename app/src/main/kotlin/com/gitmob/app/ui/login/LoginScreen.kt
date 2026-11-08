package com.gitmob.app.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R

@Composable
fun LoginScreen(
    sessionId: String,
    viewModel: LoginViewModel = hiltViewModel(key = sessionId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accountState.collectAsStateWithLifecycle()
    val returning = accounts.accounts.isNotEmpty()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelAuthorization() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .wrapContentWidth()
            .widthIn(max = 680.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (maxWidth <= 360.dp) 16.dp else 24.dp, vertical = 24.dp),
            // 多余高度按上 45% / 下 55% 分配；内容超出视口时从顶部正常滚动。
            verticalArrangement = Arrangement.spacedBy(0.dp, BiasAlignment.Vertical(-0.1f)),
        ) {
            item(key = "hero") {
                LoginHero(returning, Modifier.padding(bottom = if (returning) 16.dp else 48.dp))
            }
            if (returning) item(key = "accounts-heading") {
                Text(
                    stringResource(R.string.login_saved_accounts),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            items(accounts.accounts, key = { it.key }) { account ->
                Column(Modifier.padding(bottom = 12.dp)) {
                    AccountOption(account, selected = false, switching = state.switchingKey == account.key, enabled = !state.isLoading) {
                        viewModel.switchAccount(account.key)
                    }
                }
            }
            item(key = "auth-methods") {
                LoginAuthMethods(
                    enabled = !state.isLoading,
                    onDevice = viewModel::openDeviceFlow,
                    onPat = viewModel::openPat,
                    modifier = Modifier.padding(top = if (returning) 20.dp else 0.dp),
                )
            }
            if (state.operationErrorRes != null && state.authSheet == null) item(key = "error") {
                Text(stringResource(state.operationErrorRes!!), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
    AccountAuthSheet(viewModel)
}

@Composable
private fun LoginHero(returning: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(if (returning) 64.dp else 88.dp),
        )
        Text(
            stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(if (returning) R.string.accounts_choose_description else R.string.accounts_add_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).widthIn(max = 280.dp),
        )
    }
}

@Composable
private fun LoginAuthMethods(enabled: Boolean, onDevice: () -> Unit, onPat: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDevice, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.login_device_action), Modifier.padding(start = 8.dp), textAlign = TextAlign.Center)
            }
            Text(
                stringResource(R.string.login_device_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPat, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.login_pat_action), Modifier.padding(start = 8.dp), textAlign = TextAlign.Center)
            }
            Text(
                stringResource(R.string.login_pat_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
