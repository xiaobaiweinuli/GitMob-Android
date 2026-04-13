package com.gitmob.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    private var lastNetworkType: Int? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var _cronetEngine: org.chromium.net.CronetEngine? = null
    private var isFirstLaunch = true

    val cronetEngine: org.chromium.net.CronetEngine
        get() = _cronetEngine ?: error("CronetEngine 未初始化")

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 1. 最先安装崩溃捕获
        CrashHandler.install(this)
        tokenStorage = TokenStorage(this)
        // 2. 从 DataStore 恢复日志等级
        appScope.launch {
            val levelIdx = tokenStorage.logLevel.first()
            val level = LogLevel.entries.getOrElse(levelIdx) { LogLevel.DEBUG }
            LogManager.init(this@GitMobApp, level)
        }
        // 3. 初始化网络状态监听
        initNetworkMonitor()
        // 4. 创建 Cronet 引擎
        _cronetEngine = org.chromium.net.CronetEngine.Builder(this).build()
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
        // 8. 监听应用生命周期，从后台进入前台时清理连接池
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (isFirstLaunch) {
                    isFirstLaunch = false
                    return
                }
                // 应用从后台进入前台，重启 Cronet 引擎
                restartCronetEngine()
            }
        })
        LogManager.i("App", "GitMob 启动")
    }

    /**
     * 重启 Cronet 引擎
     */
    fun restartCronetEngine() {
        _cronetEngine?.shutdown()
        _cronetEngine = org.chromium.net.CronetEngine.Builder(this).build()
        ApiClient.rebuild()
        LogManager.i("App", "Cronet 引擎已重启")
    }

    /**
     * 网络监听优化策略：
     * 1. NetworkRequest 要求 NET_CAPABILITY_VALIDATED（系统确认真正能上网）
     * 2. 防抖：500ms 内多次回调只处理最后一次，避免 Android 短暂 onLost/onAvailable 循环
     * 3. 只在"从无网恢复到有网"时清理连接池，其他场景（切换 WiFi/蜂窝）也清理但不过度反应
     * 4. lastNetworkType 只在 VALIDATED 确认后才更新，避免 Captive Portal 等误判
     */
    private fun initNetworkMonitor() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        lastNetworkType = getCurrentValidatedNetworkType()

        // 要求 INTERNET + VALIDATED（系统已确认能真正上网，不仅仅是连了 WiFi）
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        var debounceJob: kotlinx.coroutines.Job? = null

        val networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                // onAvailable 不代表真正可用（还没 VALIDATED），等 onCapabilitiesChanged 处理
            }

            override fun onLost(network: Network) {
                // 防抖：延迟 500ms，让系统有机会立即重连
                debounceJob?.cancel()
                debounceJob = appScope.launch {
                    kotlinx.coroutines.delay(500)
                    val currentType = getCurrentValidatedNetworkType()
                    if (currentType == 0 && lastNetworkType != 0) {
                        LogManager.i("App", "网络丢失: ${getNetworkTypeName(lastNetworkType)} → 无网络")
                        lastNetworkType = 0
                        // 丢失网络时不清理连接池，等恢复时再清理（避免误清理）
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                // 防抖：500ms 内多次变化只处理最后一次
                debounceJob?.cancel()
                debounceJob = appScope.launch {
                    kotlinx.coroutines.delay(500)
                    // 必须 VALIDATED 才算真正有网
                    val isValidated = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    if (!isValidated) return@launch

                    val currentType = when {
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> 1
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                        else -> 0
                    }

                    if (currentType == lastNetworkType) return@launch  // 无变化，忽略

                    val from = getNetworkTypeName(lastNetworkType)
                    val to   = getNetworkTypeName(currentType)
                    LogManager.i("App", "网络变化（已验证）: $from → $to")

                    val wasOffline   = lastNetworkType == 0
                    val typeChanged  = lastNetworkType != currentType
                    lastNetworkType  = currentType

                    if (wasOffline || typeChanged) {
                        // 从无网恢复，或真实切换类型（WiFi ↔ 蜂窝）
                        restartCronetEngine()
                        LogManager.i("App", "Cronet 引擎已重启（网络恢复/切换）")
                    }
                }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        LogManager.i("App", "网络状态监听已初始化（VALIDATED 模式）")
    }

    /**
     * 获取当前经过系统验证（VALIDATED）的网络类型
     * 0=无有效网络, 1=Wi-Fi, 2=蜂窝数据, 3=以太网
     */
    private fun getCurrentValidatedNetworkType(): Int {
        val network = connectivityManager.activeNetwork ?: return 0
        val cap = connectivityManager.getNetworkCapabilities(network) ?: return 0
        // 必须通过 VALIDATED 才认为是真正有效的网络
        if (!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return 0
        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> 1
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            else -> 0
        }
    }

    private fun getNetworkTypeName(type: Int?): String = when (type) {
        0    -> "无网络"
        1    -> "Wi-Fi"
        2    -> "蜂窝数据"
        3    -> "以太网"
        else -> "未知"
    }

    companion object {
        lateinit var instance: GitMobApp private set
    }
}
