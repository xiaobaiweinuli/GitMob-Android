package com.gitmob.app.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.ui.theme.Coral
import com.gitmob.app.ui.theme.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private data class SeedColorOption(
    val name: String,
    val color: Color?,
)

private val seedColorOptions = listOf(
    SeedColorOption("GitMob 珊瑚色", null),
    SeedColorOption("红色", Color(0xFFF44336)),
    SeedColorOption("粉色", Color(0xFFE91E63)),
    SeedColorOption("紫色", Color(0xFF9C27B0)),
    SeedColorOption("深紫色", Color(0xFF673AB7)),
    SeedColorOption("靛蓝色", Color(0xFF3F51B5)),
    SeedColorOption("蓝色", Color(0xFF2196F3)),
    SeedColorOption("青色", Color(0xFF00BCD4)),
    SeedColorOption("蓝绿色", Color(0xFF009688)),
    SeedColorOption("绿色", Color(0xFF4CAF50)),
    SeedColorOption("黄色", Color(0xFFFFEB3B)),
    SeedColorOption("琥珀色", Color(0xFFFFC107)),
    SeedColorOption("橙色", Color(0xFFFF9800)),
    SeedColorOption("棕色", Color(0xFF795548)),
    SeedColorOption("蓝灰色", Color(0xFF607D8F)),
    SeedColorOption("柔珊瑚色", Color(0xFFFF9CA8)),
)

private data class PaletteStyleOption(
    val style: PaletteStyle,
    val label: String,
    val description: String,
)

private val paletteStyleOptions = listOf(
    PaletteStyleOption(PaletteStyle.TonalSpot, "柔和", "均衡、沉稳的默认 Material 配色"),
    PaletteStyleOption(PaletteStyle.Neutral, "中性", "降低彩度，更接近灰阶界面"),
    PaletteStyleOption(PaletteStyle.Vibrant, "鲜明", "提高主色与辅助色的鲜艳程度"),
    PaletteStyleOption(PaletteStyle.Expressive, "表现力", "使用更活泼、对比更明显的色相组合"),
    PaletteStyleOption(PaletteStyle.Rainbow, "彩虹", "生成更丰富的多色调色板"),
    PaletteStyleOption(PaletteStyle.FruitSalad, "果味", "偏轻快的邻近色组合"),
    PaletteStyleOption(PaletteStyle.Monochrome, "单色", "仅使用黑、白、灰色阶"),
    PaletteStyleOption(PaletteStyle.Fidelity, "忠实", "尽量保留种子色的原始观感"),
    PaletteStyleOption(PaletteStyle.Content, "内容", "围绕内容色生成相邻与互补色"),
)

/** Settings Tab 内的外观 push 页面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preference by viewModel.themePreference.collectAsStateWithLifecycle()
    val dynamicColorActive = preference.useDynamicColor &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val haptic = LocalHapticFeedback.current
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                CollapsibleSelectionSection(
                    title = "主题模式",
                    currentValue = themeModeLabel(preference.mode),
                    expanded = expandedSection == "theme_mode",
                    onToggle = {
                        expandedSection = if (expandedSection == "theme_mode") null else "theme_mode"
                    },
                ) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        ThemeModeRow(
                            mode = mode,
                            selected = preference.mode == mode,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                viewModel.setThemeMode(mode)
                                expandedSection = null
                            },
                        )
                        if (index != ThemeMode.entries.lastIndex) HorizontalDivider()
                    }
                }
            }

            item {
                AppearanceSection(title = "深色显示") {
                    PreferenceSwitchRow(
                        title = "AMOLED 纯黑",
                        description = "仅在深色主题下使用纯黑背景",
                        checked = preference.useAmoled,
                        onCheckedChange = viewModel::setUseAmoled,
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    AppearanceSection(title = "颜色来源") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = preference.useDynamicColor,
                                    role = Role.Switch,
                                    onValueChange = viewModel::setUseDynamicColor,
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("跟随系统壁纸", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "使用当前壁纸的 Material You 颜色",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = dynamicColorActive,
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            }

            item {
                AppearanceSection(title = "主题色") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (dynamicColorActive) {
                                "关闭壁纸取色后可使用预设颜色"
                            } else {
                                seedColorOptions.firstOrNull { it.color == preference.customSeedColor }?.name
                                    ?: "自定义主题色"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(
                                count = seedColorOptions.size,
                                key = { seedColorOptions[it].name },
                            ) { index ->
                                val option = seedColorOptions[index]
                                val selected = !dynamicColorActive &&
                                    preference.customSeedColor == option.color
                                SeedColorSwatch(
                                    option = option,
                                    selected = selected,
                                    isDark = preference.mode == ThemeMode.DARK ||
                                        (preference.mode == ThemeMode.SYSTEM &&
                                            androidx.compose.foundation.isSystemInDarkTheme()),
                                    paletteStyle = preference.paletteStyle,
                                    colorSpec = preference.colorSpec,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        viewModel.selectCustomSeedColor(option.color)
                                    },
                                )
                            }
                        }
                    }
                }
            }


            item {
                val currentStyle = paletteStyleOptions.first { it.style == preference.paletteStyle }
                CollapsibleSelectionSection(
                    title = "调色板风格",
                    currentValue = currentStyle.label,
                    expanded = expandedSection == "palette_style",
                    onToggle = {
                        expandedSection = if (expandedSection == "palette_style") null else "palette_style"
                    },
                ) {
                    paletteStyleOptions.forEachIndexed { index, option ->
                        SelectionRow(
                            title = option.label,
                            description = option.description,
                            selected = preference.paletteStyle == option.style,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                viewModel.setPaletteStyle(option.style)
                                expandedSection = null
                            },
                        )
                        if (index != paletteStyleOptions.lastIndex) HorizontalDivider()
                    }
                }
            }

            item {
                CollapsibleSelectionSection(
                    title = "颜色规格",
                    currentValue = colorSpecLabel(preference.colorSpec),
                    expanded = expandedSection == "color_spec",
                    onToggle = {
                        expandedSection = if (expandedSection == "color_spec") null else "color_spec"
                    },
                ) {
                    SelectionRow(
                        title = "Material 2021",
                        description = "成熟稳定，保持当前默认配色表现",
                        selected = preference.colorSpec == ColorSpec.SpecVersion.SPEC_2021,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.setColorSpec(ColorSpec.SpecVersion.SPEC_2021)
                            expandedSection = null
                        },
                    )
                    HorizontalDivider()
                    SelectionRow(
                        title = "Material 2025",
                        description = "使用新版动态颜色算法与色调关系",
                        selected = preference.colorSpec == ColorSpec.SpecVersion.SPEC_2025,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.setColorSpec(ColorSpec.SpecVersion.SPEC_2025)
                            expandedSection = null
                        },
                    )
                }
            }

            item {
                AppearanceSection(title = "交互") {
                    Column {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PreferenceSwitchRow(
                                title = "预测性返回",
                                description = "返回手势时预览即将显示的页面",
                                checked = preference.enablePredictiveBack,
                                onCheckedChange = viewModel::setEnablePredictiveBack,
                            )
                            HorizontalDivider()
                        }
                        PageScaleRow(
                            scale = preference.pageScale,
                            onScaleChangeFinished = viewModel::setPageScale,
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding(),
                    )
                )
            }
        }
    }
}

@Composable
private fun PageScaleRow(
    scale: Float,
    onScaleChangeFinished: (Float) -> Unit,
) {
    var sliderValue by rememberSaveable(scale) { mutableStateOf(scale.coerceIn(0.8f, 1.1f)) }
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("页面缩放", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "仅缩放 App 内容，不改变系统字体和系统栏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(sliderValue * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                onScaleChangeFinished(sliderValue)
            },
            valueRange = 0.8f..1.1f,
            steps = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                    onCheckedChange(it)
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SelectionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun AppearanceSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(shape = RoundedCornerShape(8.dp)) {
            content()
        }
    }
}

@Composable
private fun CollapsibleSelectionSection(
    title: String,
    currentValue: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(shape = RoundedCornerShape(8.dp)) {
            Column {
                Surface(
                    onClick = onToggle,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = currentValue,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column {
                        HorizontalDivider()
                        content()
                    }
                }
            }
        }
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun colorSpecLabel(spec: ColorSpec.SpecVersion): String = when (spec) {
    ColorSpec.SpecVersion.SPEC_2021 -> "Material 2021"
    ColorSpec.SpecVersion.SPEC_2025 -> "Material 2025"
}

@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val (icon, label) = when (mode) {
        ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness to "跟随系统"
        ThemeMode.LIGHT -> Icons.Default.LightMode to "浅色"
        ThemeMode.DARK -> Icons.Default.DarkMode to "深色"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        )
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun SeedColorSwatch(
    option: SeedColorOption,
    selected: Boolean,
    isDark: Boolean,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
    onClick: () -> Unit,
) {
    val swatchColor = option.color ?: Coral
    val previewScheme = rememberDynamicColorScheme(
        seedColor = swatchColor,
        isDark = isDark,
        style = paletteStyle,
        specVersion = colorSpec,
    )
    val primaryContainer by animateColorAsState(
        targetValue = previewScheme.primaryContainer,
        label = "seed-primary-container",
    )
    val tertiaryContainer by animateColorAsState(
        targetValue = previewScheme.tertiaryContainer,
        label = "seed-tertiary-container",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        label = "seed-scale",
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = option.name
                this.selected = selected
            }
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                }
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(primaryContainer),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(tertiaryContainer),
                )
            }
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + scaleIn(initialScale = 0.7f),
                exit = fadeOut() + scaleOut(targetScale = 0.7f),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = option.name,
                    tint = previewScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
