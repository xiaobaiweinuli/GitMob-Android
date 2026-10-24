package com.gitmob.app.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 同一 Gist 在独立 Nav3 页面完成写操作后，通知仍在返回栈中的详情页刷新。 */
@Singleton
class GistUpdateEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    suspend fun emit(gistId: String) = _events.emit(gistId)
}
