package com.gitmob.app.core.event

import com.gitmob.app.core.auth.AccountContext
import com.gitmob.app.core.auth.AccountEpoch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** 缓冲事件携带来源，旧会话不能影响新订阅者，也不借用当前活动账号。 */
internal class AccountEventBus<T>(capacity: Int) {
    private data class Event<T>(val epoch: AccountEpoch, val value: T)
    private val events = MutableSharedFlow<Event<T>>(extraBufferCapacity = capacity)

    fun eventsFor(epoch: AccountEpoch): Flow<T> = events
        .filter { it.epoch === epoch && epoch.isCurrent }
        .map { it.value }

    suspend fun emit(value: T, epoch: AccountEpoch = AccountContext.ensureCurrent()) {
        currentCoroutineContext().ensureActive()
        epoch.ensureCurrent()
        events.emit(Event(epoch, value))
        epoch.ensureCurrent()
    }
}
