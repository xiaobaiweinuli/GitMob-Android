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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.theme.Coral
import com.gitmob.app.ui.theme.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private data class SeedColorOption(
    @StringRes val nameRes: Int,
    val color: Color?,
)

private val seedColorOptions = listOf(
    SeedColorOption(R.string.settings_seed_color_coral, null),
    SeedColorOption(R.string.settings_seed_color_red, Color(0xFFF44336)),
    SeedColorOption(R.string.settings_seed_color_pink, Color(0xFFE91E63)),
    SeedColorOption(R.string.settings_seed_color_purple, Color(0xFF9C27B0)),
    SeedColorOption(R.string.settings_seed_color_deep_purple, Color(0xFF673AB7)),
    SeedColorOption(R.string.settings_seed_color_indigo, Color(0xFF3F51B5)),
    SeedColorOption(R.string.settings_seed_color_blue, Color(0xFF2196F3)),
    SeedColorOption(R.string.settings_seed_color_cyan, Color(0xFF00BCD4)),
    SeedColorOption(R.string.settings_seed_color_teal, Color(0xFF009688)),
    SeedColorOption(R.string.settings_seed_color_green, Color(0xFF4CAF50)),
    SeedColorOption(R.string.settings_seed_color_yellow, Color(0xFFFFEB3B)),
    SeedColorOption(R.string.settings_seed_color_amber, Color(0xFFFFC107)),
    SeedColorOption(R.string.settings_seed_color_orange, Color(0xFFFF9800)),
    SeedColorOption(R.string.settings_seed_color_brown, Color(0xFF795548)),
    SeedColorOption(R.string.settings_seed_color_blue_grey, Color(0xFF607D8F)),
    SeedColorOption(R.string.settings_seed_color_soft_coral, Color(0xFFFF9CA8)),
)

private data class PaletteStyleOption(
    val style: PaletteStyle,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
)

private val paletteStyleOptions = listOf(
    PaletteStyleOption(PaletteStyle.TonalSpot, R.string.settings_palette_tonal_spot, R.string.settings_palette_tonal_spot_desc),
    PaletteStyleOption(PaletteStyle.Neutral, R.string.settings_palette_neutral, R.string.settings_palette_neutral_desc),
    PaletteStyleOption(PaletteStyle.Vibrant, R.string.settings_palette_vibrant, R.string.settings_palette_vibrant_desc),
    PaletteStyleOption(PaletteStyle.Expressive, R.string.settings_palette_expressive, R.string.settings_palette_expressive_desc),
    PaletteStyleOption(PaletteStyle.Rainbow, R.string.settings_palette_rainbow, R.string.settings_palette_rainbow_desc),
    PaletteStyleOption(PaletteStyle.FruitSalad, R.string.settings_palette_fruit_salad, R.string.settings_palette_fruit_salad_desc),
    PaletteStyleOption(PaletteStyle.Monochrome, R.string.settings_palette_monochrome, R.string.settings_palette_monochrome_desc),
    PaletteStyleOption(PaletteStyle.Fidelity, R.string.settings_palette_fidelity, R.string.settings_palette_fidelity_desc),
    PaletteStyleOption(PaletteStyle.Content, R.string.settings_palette_content, R.string.settings_palette_content_desc),
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
                title = { Text(stringResource(R.string.settings_appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
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
                    title = stringResource(R.string.settings_theme_mode),
                    currentValue = stringResource(themeModeLabel(preference.mode)),
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
                AppearanceSection(title = stringResource(R.string.settings_dark_display)) {
                    PreferenceSwitchRow(
                        title = stringResource(R.string.settings_amoled_title),
                        description = stringResource(R.string.settings_amoled_desc),
                        checked = preference.useAmoled,
                        onCheckedChange = viewModel::setUseAmoled,
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    AppearanceSection(title = stringResource(R.string.settings_color_source)) {
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
                                Text(
                                    stringResource(R.string.settings_dynamic_color_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    stringResource(R.string.settings_dynamic_color_desc),
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
                AppearanceSection(title = stringResource(R.string.settings_theme_color)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (dynamicColorActive) {
                                stringResource(R.string.settings_theme_color_disabled_hint)
                            } else {
                                stringResource(
                                    seedColorOptions.firstOrNull { it.color == preference.customSeedColor }?.nameRes
                                        ?: R.string.settings_color_source_custom
                                )
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
                                key = { seedColorOptions[it].nameRes },
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
                    title = stringResource(R.string.settings_palette_style),
                    currentValue = stringResource(currentStyle.labelRes),
                    expanded = expandedSection == "palette_style",
                    onToggle = {
                        expandedSection = if (expandedSection == "palette_style") null else "palette_style"
                    },
                ) {
                    paletteStyleOptions.forEachIndexed { index, option ->
                        SelectionRow(
                            title = stringResource(option.labelRes),
                            description = stringResource(option.descriptionRes),
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
                    title = stringResource(R.string.settings_color_spec),
                    currentValue = colorSpecLabel(preference.colorSpec),
                    expanded = expandedSection == "color_spec",
                    onToggle = {
                        expandedSection = if (expandedSection == "color_spec") null else "color_spec"
                    },
                ) {
                    SelectionRow(
                        title = "Material 2021",
                        description = stringResource(R.string.settings_color_spec_2021_desc),
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
                        description = stringResource(R.string.settings_color_spec_2025_desc),
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
                AppearanceSection(title = stringResource(R.string.settings_section_interaction)) {
                    Column {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PreferenceSwitchRow(
                                title = stringResource(R.string.settings_predictive_back_title),
                                description = stringResource(R.string.settings_predictive_back_desc),
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
                Text(
                    stringResource(R.string.settings_page_scale_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_page_scale_desc),
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

@StringRes
private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
    ThemeMode.LIGHT -> R.string.settings_theme_mode_light
    ThemeMode.DARK -> R.string.settings_theme_mode_dark
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
    val (icon, labelRes) = when (mode) {
        ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness to R.string.settings_theme_mode_system
        ThemeMode.LIGHT -> Icons.Default.LightMode to R.string.settings_theme_mode_light
        ThemeMode.DARK -> Icons.Default.DarkMode to R.string.settings_theme_mode_dark
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
            text = stringResource(labelRes),
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
    val optionName = stringResource(option.nameRes)
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
                contentDescription = optionName
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
                    contentDescription = optionName,
                    tint = previewScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
