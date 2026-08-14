package com.gitmob.app.data.repository

import com.gitmob.app.core.cache.MemoryCache
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.network.PageSize
import com.gitmob.app.data.model.InboxNotification
import com.gitmob.app.data.model.NotificationThreadDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inbox 用——GitHub 的通知系统只有 REST，GraphQL 完全没有对应字段（已用两份独立
 * introspection 快照核实过），见 references/api-verification.md。
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val api: GHApiClient,
) {

    // ── 缓存实例 ──────────────────────────────────────────────
    /** 通知第一页（page=1, all=false 只看未读），TTL 1 min（通知是高频变化数据） */
    private val notificationsCache = MemoryCache<Unit, List<InboxNotification>>(ttlMs = 60_000L)

    /**
     * 公开：登出时由 AuthRepository 统一调用，清空当前 Repository 的全部内存缓存。
     */
    fun invalidateAllCaches() {
        notificationsCache.invalidateAll()
    }

    /**
     * 通知列表。
     * 仅对「第一页（page=1）+ 只看未读（all=false）」启用缓存（TTL 1 min）；
     * 其他页/已读全部不走缓存（通知是时间敏感数据，新鲜度优先）。
     */
    suspend fun getNotifications(page: Int = 1, all: Boolean = false): ApiResult<List<InboxNotification>> {
        if (page == 1 && !all) {
            notificationsCache.get(Unit)?.let { return ApiResult.Success(it) }
            val result = getNotificationsInternal(page, all)
            if (result is ApiResult.Success) notificationsCache.set(Unit, result.data)
            return result
        }
        return getNotificationsInternal(page, all)
    }

    /**
     * 下拉刷新专用：强制重新拉取通知第一页，不走缓存。
     */
    suspend fun getNotificationsFresh(): ApiResult<List<InboxNotification>> {
        val result = getNotificationsInternal(page = 1, all = false)
        if (result is ApiResult.Success) notificationsCache.set(Unit, result.data)
        return result
    }

    private suspend fun getNotificationsInternal(page: Int, all: Boolean): ApiResult<List<InboxNotification>> = safeCall {
        val path = "/notifications?all=$all&page=$page&per_page=${PageSize.NOTIFICATIONS}"
        api.get<List<NotificationThreadDto>>(path).map {
            InboxNotification(
                id = it.id,
                repoOwner = it.repository.owner.login,
                repoName = it.repository.name,
                title = it.subject.title,
                subjectType = it.subject.type,
                subjectApiUrl = it.subject.url.orEmpty(),
                reason = it.reason,
                isUnread = it.unread,
                updatedAt = it.updatedAt,
            )
        }
    }

    /**
     * 单条标已读：成功后失效全部通知缓存（未读计数变了、列表内容也变了）。
     * 成功时是 205 Reset Content 或 204 No Content（空响应体），GHApiClient.executeRest 已经处理了这种情况。
     */
    suspend fun markAsRead(threadId: String): ApiResult<Unit> = safeCall {
        api.patchNoBody<Unit>("/notifications/threads/$threadId")
        notificationsCache.invalidate(Unit)
    }

    /**
     * 全部标已读：成功后失效全部通知缓存。
     */
    suspend fun markAllAsRead(): ApiResult<Unit> = safeCall {
        api.putNoBody<Unit>("/notifications")
        notificationsCache.invalidateAll()
    }
}
