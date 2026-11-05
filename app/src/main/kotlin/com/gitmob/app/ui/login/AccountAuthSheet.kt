package com.gitmob.app.ui.login

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.data.model.DeviceFlowState
import com.gitmob.app.data.model.DeviceFlowStatus

/** 选择页和设置页共用；PAT 只在 ViewModel 内存中，不写入保存状态或日志。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountAuthSheet(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val kind = state.authSheet ?: return
    DisposableEffect(viewModel, kind) {
        onDispose { viewModel.cancelAuthorization() }
    }
    val uriHandler = LocalUriHandler.current
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
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
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
                Button(onClick = viewModel::login, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.auth_add_account))
                }
                Text(stringResource(R.string.auth_profile_permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.auth_device_description))
                when (val device = state.deviceFlow) {
                    is DeviceFlowState.Waiting -> {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.auth_device_code), style = MaterialTheme.typography.labelLarge)
                                Text(device.userCode, style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace))
                                Text("github.com/login/device", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    if (viewModel.accountEpoch.isCurrent) {
                                        runCatching { uriHandler.openUri(device.verificationUri) }.onFailure { viewModel.browserUnavailable() }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.auth_open_browser)) }
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
                        Text(stringResource(R.string.auth_device_remaining, device.remainingSeconds), style = MaterialTheme.typography.bodySmall)
                    }
                    is DeviceFlowState.Failed -> {
                        Text(stringResource(state.inlineErrorRes ?: R.string.error_request_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = viewModel::retryDeviceFlow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.auth_device_restart)) }
                    }
                    else -> {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.auth_device_requesting))
                    }
                }
                Text(stringResource(R.string.auth_device_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
