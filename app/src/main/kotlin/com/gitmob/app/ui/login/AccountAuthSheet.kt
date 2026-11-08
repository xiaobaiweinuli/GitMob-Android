package com.gitmob.app.ui.login

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.DeviceFlowStatus
import kotlinx.coroutines.delay

/** 选择页和设置页共用；PAT 只在 ViewModel 内存中，不写入保存状态或日志。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountAuthSheet(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val kind = state.authSheet ?: return
    DisposableEffect(viewModel, kind) {
        onDispose { viewModel.cancelAuthorization() }
    }
    val context = LocalContext.current
    fun openInBrowser(url: String, @StringRes errorRes: Int) {
        if (!viewModel.accountEpoch.isCurrent) return
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            // 按浏览器入口解析，避免授权链接再次打开 GitMob。
            selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).selector
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.browserUnavailable(errorRes)
        } catch (_: SecurityException) {
            viewModel.browserUnavailable(errorRes)
        }
    }
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelAuthorization,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        sheetMaxWidth = 640.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        properties = ModalBottomSheetProperties(securePolicy = if (kind == AuthSheet.PAT) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (kind == AuthSheet.PAT) R.string.auth_pat_title else R.string.accounts_device_flow),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::cancelAuthorization) { Text(stringResource(R.string.common_cancel)) }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (kind == AuthSheet.PAT) {
                Text(stringResource(R.string.auth_pat_description))
                OutlinedTextField(
                    value = state.tokenInput,
                    onValueChange = viewModel::onTokenInputChange,
                    label = { Text(stringResource(R.string.auth_pat_label)) },
                    supportingText = { Text(stringResource(state.inlineErrorRes ?: R.string.auth_pat_help)) },
                    isError = state.inlineErrorRes != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.operationErrorRes?.let { messageRes ->
                    Text(
                        stringResource(messageRes),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(onClick = viewModel::login, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.auth_add_account))
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auth_no_token), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                openInBrowser(CLASSIC_PAT_URL, R.string.deep_link_browser_unavailable)
                            },
                            enabled = !state.isLoading,
                        ) { Text(stringResource(R.string.auth_create_classic_pat)) }
                    }
                    Text(stringResource(R.string.auth_create_classic_pat_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.auth_profile_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.auth_device_description))
                when (val device = state.deviceFlow) {
                    is DeviceFlowState.Waiting -> {
                        val deadline = device.expiresAtElapsedRealtimeMillis
                        val lifecycleOwner = LocalLifecycleOwner.current
                        var remainingSeconds by remember(deadline) {
                            mutableLongStateOf(((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000)
                        }
                        // 界面倒计时独立于网络轮询；返回前台后根据同一个到期时间重新计算。
                        LaunchedEffect(deadline, lifecycleOwner) {
                            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                while (true) {
                                    val remainingMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                                    remainingSeconds = (remainingMillis + 999) / 1000
                                    if (remainingMillis == 0L) break
                                    delay((remainingMillis - 1) % 1000 + 1)
                                }
                            }
                        }
                        var copied by remember(device.userCode, deadline) { mutableStateOf(false) }
                        LaunchedEffect(copied) {
                            if (copied) {
                                delay(2000)
                                copied = false
                            }
                        }
                        val codeLabel = stringResource(R.string.auth_device_code)
                        val copyLabel = stringResource(R.string.auth_device_copy)
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = if (copied) stringResource(R.string.auth_device_copied) else codeLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                Text(
                                    text = device.userCode,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.fillMaxWidth().clickable(
                                        enabled = remainingSeconds > 0,
                                        role = Role.Button,
                                        onClickLabel = copyLabel,
                                    ) {
                                        if (viewModel.accountEpoch.isCurrent && SystemClock.elapsedRealtime() < deadline) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText(codeLabel, device.userCode))
                                            copied = true
                                        }
                                    }.padding(vertical = 8.dp),
                                )
                                Text("github.com/login/device", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { openInBrowser(device.verificationUri, R.string.auth_browser_unavailable) },
                                    enabled = remainingSeconds > 0,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.auth_open_browser)) }
                            }
                        }
                        Text(
                            stringResource(when (device.status) {
                                DeviceFlowStatus.WAITING -> R.string.auth_device_waiting
                                DeviceFlowStatus.SLOW_DOWN -> R.string.auth_device_slow_down
                                DeviceFlowStatus.NETWORK_RETRY -> R.string.auth_device_network_retry
                            }),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text(stringResource(R.string.auth_device_remaining, remainingSeconds), style = MaterialTheme.typography.bodySmall)
                    }
                    is DeviceFlowState.Failed -> {
                        Text(stringResource(state.operationErrorRes ?: R.string.error_request_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = viewModel::retryDeviceFlow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.auth_device_restart)) }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                text = stringResource(R.string.auth_device_requesting),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                Text(stringResource(R.string.auth_device_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val CLASSIC_PAT_URL = "https://github.com/settings/tokens/new?description=GitMob&" +
    "scopes=repo,user,gist,read:org,notifications,workflow,delete_repo,write:discussion"
