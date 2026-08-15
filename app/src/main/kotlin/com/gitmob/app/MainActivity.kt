package com.gitmob.app

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.core.error.ErrorEventBus
import com.gitmob.app.navigation.GitMobNavGraph
import com.gitmob.app.ui.theme.GitMobTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var errorEventBus: ErrorEventBus

    private val startupViewModel: StartupViewModel by viewModels()

    /**
     * 启动链路（KernelSU 同款零依赖配方，分析见 文档/splash-screen-deep-analysis.md §10）：
     * 1. 系统启动窗背景来自 themes.xml 的 windowBackground（values / values-night 双份，
     *    深浅色自动正确），API 31+ 中央是系统默认的 launcher 图标——不引 core-splashscreen、
     *    不声明任何 windowSplashScreen* 属性；
     * 2. OnPreDrawListener 挂起首帧（官方文档标准手法，等价于库的 setKeepOnScreenCondition）：
     *    isReady 之前不允许绘制任何一帧 → 系统启动窗一直盖着"登录态解析 + 主题加载 +
     *    全 App 首次组合"整段，就绪后系统淡出直接揭开完整主页（否则空首帧会提前放走
     *    启动窗，变成"白淡入白"+ 裸露等待——当年的问题正是这么来的）；
     * 3. setContent 里的 return@setContent 是第二道门：就绪前不构造主题/导航树，
     *    避免用默认主题白建一遍再整树重建（这一帧反正被挂起，用户看不见）。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val content: View = findViewById(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean =
                    if (startupViewModel.isReady.value) {
                        content.viewTreeObserver.removeOnPreDrawListener(this)
                        true
                    } else {
                        false
                    }
            },
        )

        setContent {
            val isLoggedIn by startupViewModel.isLoggedIn.collectAsStateWithLifecycle()
            val isReady by startupViewModel.isReady.collectAsStateWithLifecycle()
            val themePreference by startupViewModel.themePreference.collectAsStateWithLifecycle()

            if (!isReady) return@setContent

            GitMobTheme(preference = themePreference) {
                Surface(modifier = Modifier) {
                    GitMobNavGraph(
                        startLoggedIn = isLoggedIn,
                        errorEventBus = errorEventBus,
                        enablePredictiveBack = themePreference.enablePredictiveBack,
                    )
                }
            }
        }
    }
}
