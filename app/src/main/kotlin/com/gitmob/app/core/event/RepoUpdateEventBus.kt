package com.gitmob.app.core.event

import com.gitmob.app.core.auth.AccountEpoch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局单例事件总线，和 core/error/ErrorEventBus 同一个模式（Singleton + SharedFlow）。
 * 任何 ViewModel 需要"通知别的屏幕：这个仓库的某个状态变了"就注入这个类调 emit()，
 * 需要"关心某个仓库状态变化"就订阅自己会话的 eventsFor，再按 owner+name 过滤。
 */
@Singleton
class RepoUpdateEventBus @Inject constructor() {
    private val events = AccountEventBus<RepoUpdateEvent>(capacity = 16)
    fun eventsFor(epoch: AccountEpoch) = events.eventsFor(epoch)

    suspend fun emit(event: RepoUpdateEvent) {
        events.emit(event)
    }
}
