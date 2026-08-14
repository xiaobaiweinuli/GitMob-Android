package com.gitmob.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/** 深浅模式：跟随系统 / 强制浅色 / 强制深色，和取色来源是两个正交维度 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 主题偏好设置。设计参照 KernelSU 管理器真实实现（ui/theme/Theme.kt + ThemeExt.kt）：
 * 取色来源只有一条代码路径（统一走 MaterialKolor 的 rememberDynamicColorScheme），
 * 区别只在种子色从哪来——不是"系统取色用一套 API、品牌色用另一套 API"分别处理。
 */
data class ThemePreference(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** 是否用系统壁纸取色（Android 12+ Material You）当种子色来源，低于 API 31 自动降级为品牌色 */
    val useDynamicColor: Boolean = false,
    /** 用户自定义种子色；为 null 时用 Color.kt 里的默认品牌色 Coral */
    val customSeedColor: Color? = null,
    /** 深色主题下使用纯黑背景；浅色主题不受影响。 */
    val useAmoled: Boolean = false,
    /** MaterialKolor 生成调色板时使用的风格。 */
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    /** Material Design 颜色算法规格，默认保持 MaterialKolor 的 2021 兼容行为。 */
    val colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2021,
    /** 是否显示 Navigation3 的预测性返回目的页预览。 */
    val enablePredictiveBack: Boolean = true,
    /** App 内容缩放比例；限制在 80%～110%，不改变系统窗口与系统栏。 */
    val pageScale: Float = 1f,
)
