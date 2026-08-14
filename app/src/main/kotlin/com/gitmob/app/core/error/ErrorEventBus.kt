package com.gitmob.app.core.error

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局错误事件总线。ViewModel 遇到 ApiResult.Failure 时 emit 到这里，
 * UI 层由唯一的 ErrorBannerHost 订阅并展示顶部提示，不需要每个 Screen
 * 各自实现 Toast/Snackbar 逻辑。
 */
@Singleton
class ErrorEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<ApiError>(extraBufferCapacity = 4)
    val events: SharedFlow<ApiError> = _events.asSharedFlow()

    suspend fun emit(error: ApiError) {
        _events.emit(error)
    }
}
