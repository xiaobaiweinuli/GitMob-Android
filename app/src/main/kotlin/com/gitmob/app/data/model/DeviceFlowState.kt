package com.gitmob.app.data.model

import com.gitmob.app.core.error.ApiError

enum class DeviceFlowStatus { WAITING, SLOW_DOWN, NETWORK_RETRY }

sealed interface DeviceFlowState {
    data object Requesting : DeviceFlowState
    data class Waiting(
        val userCode: String,
        val verificationUri: String,
        val remainingSeconds: Long,
        val status: DeviceFlowStatus = DeviceFlowStatus.WAITING,
    ) : DeviceFlowState
    data class Complete(val user: AuthenticatedUser) : DeviceFlowState
    data class Failed(val error: ApiError) : DeviceFlowState
}
