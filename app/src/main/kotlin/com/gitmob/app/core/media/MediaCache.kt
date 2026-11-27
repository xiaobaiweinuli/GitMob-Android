package com.gitmob.app.core.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.gitmob.app.core.auth.AccountEpoch
import com.gitmob.app.data.model.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One process-wide cache; account and content version determine which bytes may be reused. */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class MediaCache @Inject constructor(@ApplicationContext context: Context) {
    internal val diskCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        )
    }

    suspend fun dataSourceFactory(upstream: DataSource.Factory, source: MediaSource, epoch: AccountEpoch): DataSource.Factory {
        epoch.ensureCurrent()
        val cache = withContext(Dispatchers.IO) {
            try { diskCache.apply { checkInitialization() } }
            catch (_: Cache.CacheException) { null }
        }
        epoch.ensureCurrent()
        // Signed download URLs are transport addresses, not persistent cache identities.
        val identity = source.cacheKey ?: UUID.randomUUID().toString()
        val account = epoch.accountKey ?: "anonymous"
        val key = "${account.length}:$account$identity".encodeUtf8().sha256().hex()
        val factory = if (cache == null) upstream else CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { key }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { AccountDataSource(factory.createDataSource(), epoch) }
    }

    /** Cache hits bypass HTTP interceptors, so reads must also enforce the captured account. */
    private class AccountDataSource(private val delegate: DataSource, private val epoch: AccountEpoch) : DataSource by delegate {
        override fun open(dataSpec: DataSpec): Long = checked { delegate.open(dataSpec) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checked { delegate.read(buffer, offset, length) }
        private inline fun <T> checked(block: () -> T): T {
            if (!epoch.isCurrent) throw IOException("Account session changed")
            val result = block()
            if (!epoch.isCurrent) throw IOException("Account session changed")
            return result
        }
    }
}
