package com.gitmob.app.core.event

import com.gitmob.app.core.auth.AccountEpoch
import javax.inject.Inject
import javax.inject.Singleton

/** Gist 写操作后通知返回栈中的详情页或列表刷新。 */
@Singleton
class GistUpdateEventBus @Inject constructor() {
    private val events = AccountEventBus<String>(capacity = 8)
    private val listEvents = AccountEventBus<Unit>(capacity = 1)
    fun eventsFor(epoch: AccountEpoch) = events.eventsFor(epoch)
    fun listEventsFor(epoch: AccountEpoch) = listEvents.eventsFor(epoch)

    suspend fun emit(gistId: String) = events.emit(gistId)
    suspend fun emitListChanged() = listEvents.emit(Unit)
}
