package com.gitmob.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 网络状态监听工具类
 *
 * 功能：
 * - 监听网络状态变化（WiFi / 蜂窝数据 / 无网络）
 * - 提供网络状态 Flow 供 UI 监听
 * - 要求 NET_CAPABILITY_VALIDATED（系统确认真正能上网）
 * - 防抖处理，避免频繁回调
 * - 网络恢复/切换时触发回调（用于重启 Cronet 引擎）
 */
class NetworkMonitor private constructor(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val DEBOUNCE_DELAY_MS = 2000L

        @Volatile
        private var instance: NetworkMonitor? = null

        /**
         * 初始化 NetworkMonitor（应用启动时调用一次）
         */
        fun init(context: Context, scope: CoroutineScope, onNetworkChanged: (() -> Unit)? = null): NetworkMonitor {
            return instance ?: synchronized(this) {
                instance ?: NetworkMonitor(context, scope).also {
                    if (onNetworkChanged != null) {
                        it.onNetworkChangedCallback = onNetworkChanged
                    }
                    instance = it
                    it.start()
                }
            }
        }

        /**
         * 获取 NetworkMonitor 单例
         */
        fun getInstance(): NetworkMonitor {
            return instance ?: error("NetworkMonitor not initialized, call init() first")
        }
    }

    /**
     * 网络类型枚举
     */
    enum class NetworkType {
        NONE,        // 无网络
        WIFI,        // Wi-Fi
        CELLULAR,    // 蜂窝数据
        ETHERNET     // 以太网
    }

    private val _networkType = MutableStateFlow(NetworkType.NONE)
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private lateinit var connectivityManager: ConnectivityManager
    private var debounceJob: kotlinx.coroutines.Job? = null
    private var onNetworkChangedCallback: (() -> Unit)? = null
    private var previousNetworkType: NetworkType = NetworkType.NONE

    /**
     * 开始监听网络状态
     */
    private fun start() {
        connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        // 初始状态
        val initialType = getCurrentValidatedNetworkType()
        _networkType.value = initialType
        _isOnline.value = initialType != NetworkType.NONE
        previousNetworkType = initialType

        LogManager.i(TAG, "初始网络状态: ${getNetworkTypeName(initialType)}")

        val networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
            }

            override fun onLost(network: Network) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_DELAY_MS)
                    val currentType = getCurrentValidatedNetworkType()
                    if (currentType == NetworkType.NONE && _networkType.value != NetworkType.NONE) {
                        LogManager.i(TAG, "网络丢失: ${getNetworkTypeName(_networkType.value)} → 无网络")
                        _networkType.value = NetworkType.NONE
                        _isOnline.value = false
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_DELAY_MS)

                    val isValidated = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    if (!isValidated) return@launch

                    val currentType = when {
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                        else -> NetworkType.NONE
                    }

                    if (currentType == _networkType.value) return@launch

                    val from = getNetworkTypeName(_networkType.value)
                    val to = getNetworkTypeName(currentType)
                    LogManager.i(TAG, "网络变化（已验证）: $from → $to")

                    val wasOffline = previousNetworkType == NetworkType.NONE
                    val typeChanged = previousNetworkType != currentType
                    previousNetworkType = currentType

                    _networkType.value = currentType
                    _isOnline.value = currentType != NetworkType.NONE

                    if (wasOffline || typeChanged) {
                        onNetworkChangedCallback?.invoke()
                        LogManager.i(TAG, "网络恢复/切换，触发回调")
                    }
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        LogManager.i(TAG, "网络状态监听已启动（默认网络模式）")
    }

    /**
     * 获取当前经过系统验证（VALIDATED）的网络类型
     */
    private fun getCurrentValidatedNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val cap = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE

        if (!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return NetworkType.NONE
        }

        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.NONE
        }
    }

    /**
     * 获取网络类型名称（用于日志）
     */
    private fun getNetworkTypeName(type: NetworkType): String = when (type) {
        NetworkType.NONE     -> "无网络"
        NetworkType.WIFI     -> "Wi-Fi"
        NetworkType.CELLULAR -> "蜂窝数据"
        NetworkType.ETHERNET -> "以太网"
    }

    /**
     * 是否是 Wi-Fi
     */
    val isWifi: Boolean get() = _networkType.value == NetworkType.WIFI

    /**
     * 是否是蜂窝数据
     */
    val isCellular: Boolean get() = _networkType.value == NetworkType.CELLULAR

    /**
     * 是否是弱网（简单判断：蜂窝数据 = 弱网）
     */
    val isWeakNetwork: Boolean get() = _networkType.value == NetworkType.CELLULAR
}
