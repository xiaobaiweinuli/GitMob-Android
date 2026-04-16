package com.gitmob.android.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 仓库内更新事件类型
 */
sealed class RepoUpdateEvent {
    /**
     * PR 更新事件
     */
    data class PRUpdated(
        val owner: String,
        val repo: String,
        val number: Int,
        val title: String? = null,
        val state: String? = null
    ) : RepoUpdateEvent()

    /**
     * Issue 更新事件
     */
    data class IssueUpdated(
        val owner: String,
        val repo: String,
        val number: Int,
        val title: String? = null,
        val state: String? = null
    ) : RepoUpdateEvent()

    /**
     * Discussion 更新事件
     */
    data class DiscussionUpdated(
        val owner: String,
        val repo: String,
        val number: Int,
        val title: String? = null
    ) : RepoUpdateEvent()

    /**
     * 仓库本身更新事件（归档状态、可见性、名称等）
     */
    data class RepoUpdated(
        val owner: String,
        val repo: String
    ) : RepoUpdateEvent()
}

/**
 * 仓库更新事件总线
 * 用于在详情页和列表页之间传递更新事件，实现局部刷新
 */
object RepoUpdateEventBus {
    private val _events = MutableSharedFlow<RepoUpdateEvent>()
    val events: SharedFlow<RepoUpdateEvent> = _events.asSharedFlow()

    suspend fun send(event: RepoUpdateEvent) {
        _events.emit(event)
    }
}
