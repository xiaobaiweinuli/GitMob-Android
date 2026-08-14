package com.gitmob.app.core.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量 TTL 内存缓存，供 @Singleton Repository 使用。
 *
 * 设计原则（与项目架构约束对齐）：
 * 1. 不持久化到磁盘——进程被杀/重启清零，避免展示过时数据；
 *    会话级缓存不需要 Room（Room 用于结构化持久数据，属于不同层职责）。
 * 2. 不使用 DataStore/SharedPreferences——这些是磁盘 IO，会话级缓存用内存足够。
 * 3. 线程安全——ConcurrentHashMap 原生支持多线程读-写并发。
 * 4. Repository 是 @Singleton 天然跨 ViewModel 存活，因此缓存实例放在 Repository 属性上，
 *    与 Repository 生命周期相同（App 会话期间一直存在）。
 *
 * 典型使用模式：
 *   // 声明缓存
 *   private val profileCache = MemoryCache<Unit, ViewerProfile>(ttlMs = 5 * 60_000L)
 *   // 命中直接返回；未命中走网络并写缓存
 *   profileCache.get(Unit)?.let { return ApiResult.Success(it) }
 *   return safeCall { api.graphQL(...).also { profileCache.set(Unit, it) } }
 *   // mutation 后主动失效（下次重新拉取）
 *   profileCache.invalidate(Unit)
 *
 * @param ttlMs 缓存有效期（毫秒）；到期后下次 get() 时惰性移除并返回 null
 */
class MemoryCache<K : Any, V : Any>(private val ttlMs: Long) {

    /** 内部条目：保存值 + 写入时间戳 */
    private data class Entry<V>(val value: V, val timestamp: Long)

    private val store = ConcurrentHashMap<K, Entry<V>>()

    /**
     * 命中且未过期时返回缓存值；否则返回 null。
     * 过期条目会在 get() 时被惰性移除，不需要单独的清扫线程。
     */
    fun get(key: K): V? {
        val entry = store[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            store.remove(key)
            return null
        }
        return entry.value
    }

    /**
     * 写入缓存，刷新时间戳。
     * 相同 key 会覆盖旧值（典型：下拉刷新成功后重新 set）。
     */
    fun set(key: K, value: V) {
        store[key] = Entry(value, System.currentTimeMillis())
    }

    /** 主动失效指定 key（mutation 后调用，保证下次读一定走网络）。 */
    fun invalidate(key: K) {
        store.remove(key)
    }

    /** 清空全部缓存（logout 时调用，避免账号切换后残留上一账号数据）。 */
    fun invalidateAll() {
        store.clear()
    }
}
