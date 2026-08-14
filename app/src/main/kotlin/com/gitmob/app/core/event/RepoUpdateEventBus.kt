package com.gitmob.app.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局单例事件总线，和 core/error/ErrorEventBus 同一个模式（Singleton + SharedFlow）。
 * 任何 ViewModel 需要"通知别的屏幕：这个仓库的某个状态变了"就注入这个类调 emit()，
 * 需要"关心某个仓库状态变化"就注入这个类订阅 events、自己按 owner+name 过滤。
 */
@Singleton
class RepoUpdateEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<RepoUpdateEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RepoUpdateEvent> = _events.asSharedFlow()

    suspend fun emit(event: RepoUpdateEvent) {
        _events.emit(event)
    }
}
