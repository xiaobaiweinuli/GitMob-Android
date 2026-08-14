package com.gitmob.app

import android.os.Bundle
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
     * 入口启动逻辑：
     * 1. 先调用 enableEdgeToEdge() 开启边缘到边缘显示（和 KernelSU 保持一致）：
     *    不再让系统默认给 DecorView 加 insets padding，Compose 的 WindowInsets 体系统一接管；
     * 2. 不使用 Splash Screen 框架 API，直接用 StartupViewModel 判断：
     *      - isReady == false：保持空白（启动期间的窗口背景就是 Theme.GitMob）
     *      - isReady == true：根据 isLoggedIn 决定把导航图起点设为登录页还是主页
     * 这样 StartupViewModel 可以在后台完成 TokenStorage 读取 / DataStore 初始化等工作，
     * 不需要框架级的启动页，也没有 Splash Screen 框架 install 回调的额外开销。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isLoggedIn by startupViewModel.isLoggedIn.collectAsStateWithLifecycle()
            val isReady by startupViewModel.isReady.collectAsStateWithLifecycle()
            val themePreference by startupViewModel.themePreference.collectAsStateWithLifecycle()

            if (isReady) {
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
}
