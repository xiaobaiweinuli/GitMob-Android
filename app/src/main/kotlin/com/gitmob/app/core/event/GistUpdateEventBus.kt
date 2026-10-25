package com.gitmob.app.core.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Gist 写操作后通知返回栈中的详情页或列表刷新。 */
@Singleton
class GistUpdateEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()
    private val _listEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val listEvents: SharedFlow<Unit> = _listEvents.asSharedFlow()

    suspend fun emit(gistId: String) = _events.emit(gistId)
    suspend fun emitListChanged() = _listEvents.emit(Unit)
}
