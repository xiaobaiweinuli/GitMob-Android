package com.gitmob.app.core.error

import androidx.annotation.StringRes
import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.core.event.AccountEventBus
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BannerEvent {
    data class Error(val error: ApiError) : BannerEvent
    data class Notice(@field:StringRes val messageRes: Int) : BannerEvent
}

/**
 * 全局错误事件总线。ViewModel 遇到 ApiResult.Failure 时 emit 到这里，
 * UI 层由唯一的 ErrorBannerHost 订阅并展示顶部提示，不需要每个 Screen
 * 各自实现 Toast/Snackbar 逻辑。
 */
@Singleton
class ErrorEventBus @Inject constructor() {
    private val events = AccountEventBus<BannerEvent>(capacity = 4)
    fun eventsFor(epoch: AccountEpoch) = events.eventsFor(epoch)

    suspend fun emit(error: ApiError, epoch: AccountEpoch = AccountContext.ensureCurrent()) {
        events.emit(BannerEvent.Error(error), epoch)
    }

    suspend fun emitNotice(@StringRes messageRes: Int, epoch: AccountEpoch = AccountContext.ensureCurrent()) {
        events.emit(BannerEvent.Notice(messageRes), epoch)
    }
}
