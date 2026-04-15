package com.gitmob.android

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import com.gitmob.android.api.ApiClient
import com.gitmob.android.auth.RootManager
import com.gitmob.android.auth.TokenStorage
import com.gitmob.android.util.CrashHandler
import com.gitmob.android.util.LogManager
import com.gitmob.android.util.LogLevel
import com.gitmob.android.util.NetworkMonitor
import com.gitmob.android.util.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GitMobApp : Application() {
    lateinit var tokenStorage: TokenStorage private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 问题7: Root 恢复的完成信号。
     * FilePickerScreen 等调用方可 await() 等待恢复完成，避免竞态（isGranted 为 false 但开关是亮的）。
     * 无论成功还是失败，恢复流程结束后都会 complete(isGranted)。
     */
    val rootReady: CompletableDeferred<Boolean> = CompletableDeferred()

    private var isFirstLaunch = true

    /** 更新检测结果 */
    private val _pendingUpdate = MutableStateFlow<UpdateManager.Release?>(null)
    val pendingUpdate: StateFlow<UpdateManager.Release?> = _pendingUpdate.asStateFlow()

    /** 是否已检测过更新 */
    private var updateChecked = false

    /** 标记已显示更新弹窗 */
    fun markUpdateShown() {
        _pendingUpdate.value = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 1. 最先安装崩溃捕获
        CrashHandler.install(this)
        tokenStorage = TokenStorage(this)
        // 2. 数据迁移：DataStore → EncryptedPreferences
        appScope.launch {
            tokenStorage.migrateFromDataStoreIfNeeded()
        }
        // 3. 从 EncryptedPreferences 恢复日志等级
        appScope.launch {
            val levelIdx = tokenStorage.logLevel.first()
            val level = LogLevel.entries.getOrElse(levelIdx) { LogLevel.DEBUG }
            LogManager.init(this@GitMobApp, level)
        }
        // 4. 初始化 NetworkMonitor
        NetworkMonitor.init(this, appScope) {
            ApiClient.rebuild()
            LogManager.i("App", "OkHttp 客户端已重建（网络恢复/切换）")
        }
        // 5. 初始化 ApiClient
        ApiClient.init(tokenStorage)
        // 6. 初始化 Coil3（OkHttp 网络 + SVG 解码支持）
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(this)
                .components {
                    add(OkHttpNetworkFetcherFactory())   // 使用 OkHttp 作为网络层
                    add(SvgDecoder.Factory())             // 显式启用 SVG 支持
                }
                .build()
        }
        // 7. Root 权限自动恢复（问题 6 + 7 + 8）
        appScope.launch {
            val rootEnabled = tokenStorage.rootEnabled.first()
            if (!rootEnabled) {
                rootReady.complete(false)
                return@launch
            }
            try {
                LogManager.i("App", "尝试自动恢复 root 权限")
                // 问题8: 注入上次探测的 su 执行模式缓存，跳过重复探测
                val cachedMode = tokenStorage.getSuExecModeCache()
                RootManager.injectSuExecModeCache(cachedMode)

                val granted = RootManager.requestRoot()

                // 问题8: 探测完成后，将最新模式写回 DataStore 缓存
                val newMode = RootManager.getSuExecModeForPersist()
                if (newMode >= 0) tokenStorage.setSuExecModeCache(newMode)

                if (!granted) {
                    // 问题6: 授权失败时同步将 DataStore.rootEnabled 置回 false，
                    // 避免开关显示"已启用"但功能静默失效
                    LogManager.w("App", "Root 权限恢复失败，同步关闭 rootEnabled")
                    tokenStorage.setRootEnabled(false)
                } else {
                    LogManager.i("App", "Root 权限恢复成功")
                }
                rootReady.complete(granted)
            } catch (e: Exception) {
                LogManager.e("App", "自动恢复 root 权限异常", e)
                // 同样同步关闭，避免状态不一致
                tokenStorage.setRootEnabled(false)
                rootReady.complete(false)
            }
        }
        // 8. 监听应用生命周期，从后台进入前台时重建 OkHttp 客户端
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (isFirstLaunch) {
                    isFirstLaunch = false
                    return
                }
                // 应用从后台进入前台，重建 OkHttp 客户端
                ApiClient.rebuild()
                LogManager.i("App", "OkHttp 客户端已重建（从后台进入前台）")
            }
        })
        // 9. 启动时检测更新
        appScope.launch {
            if (!updateChecked) {
                updateChecked = true
                LogManager.d("App", "APP 启动，检测更新...")
                UpdateManager.checkForUpdate().onSuccess { release ->
                    if (release != null) {
                        LogManager.i("App", "检测到新版本: ${release.tagName}")
                        _pendingUpdate.value = release
                    } else {
                        LogManager.i("App", "未检测到新版本")
                    }
                }.onFailure { e ->
                    LogManager.e("App", "检测更新失败", e)
                }
            }
        }
        LogManager.i("App", "GitMob 启动")
    }

    companion object {
        lateinit var instance: GitMobApp private set
    }
}
