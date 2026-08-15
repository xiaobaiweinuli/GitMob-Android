package com.gitmob.app.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitmob.app.R
import com.gitmob.app.ui.theme.ThemeMode

/** 底部 Tab「设置」。底部 NavigationBar 的高度由外层 NavDisplay 统一处理。 */
@Composable
fun SettingsScreen(
    onAppearanceClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onLogout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preference by viewModel.themePreference.collectAsStateWithLifecycle()
    val modeText = stringResource(
        when (preference.mode) {
            ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
            ThemeMode.LIGHT -> R.string.settings_theme_mode_light
            ThemeMode.DARK -> R.string.settings_theme_mode_dark
        }
    )
    val colorSourceText = stringResource(
        when {
            preference.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                R.string.settings_color_source_wallpaper
            preference.customSeedColor == null -> R.string.settings_color_source_brand
            else -> R.string.settings_color_source_custom
        }
    )
    val appearanceSummary = "$modeText · $colorSourceText"

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection(title = stringResource(R.string.settings_section_preferences)) {
                    SettingsNavigationRow(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.settings_appearance),
                        summary = appearanceSummary,
                        onClick = onAppearanceClick,
                    )
                }
            }

            item {
                // 语言切换走 AppCompatDelegate（见 AppLanguage）：选中后 ≤32 会重建 Activity、
                // 33+ 触发配置变更重组，两条路径都会让整个 UI 立即切到新语言。
                val haptic = LocalHapticFeedback.current
                var languageExpanded by rememberSaveable { mutableStateOf(false) }
                val currentLanguage = AppLanguage.current()
                CollapsibleSelectionSection(
                    title = stringResource(R.string.settings_language),
                    currentValue = stringResource(currentLanguage.labelRes),
                    expanded = languageExpanded,
                    onToggle = { languageExpanded = !languageExpanded },
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        AppLanguage.entries.forEach { language ->
                            SelectionRow(
                                title = stringResource(language.labelRes),
                                description = stringResource(language.descriptionRes),
                                selected = language == currentLanguage,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    languageExpanded = false
                                    AppLanguage.apply(language)
                                },
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_app)) {
                    SettingsNavigationRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.settings_about),
                        summary = stringResource(R.string.settings_about_summary),
                        onClick = onAboutClick,
                    )
                }
            }

            item {
                SettingsSection(title = stringResource(R.string.settings_section_account)) {
                    SettingsNavigationRow(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = stringResource(R.string.settings_logout),
                        summary = stringResource(R.string.settings_logout_summary),
                        onClick = onLogout,
                        contentColor = MaterialTheme.colorScheme.error,
                        showChevron = false,
                    )
                }
            }
        }
    }
}

/** 语言选项的显示名：语言名用其母语写法固定不译（translatable=false），"跟随系统"随界面语言。 */
private val AppLanguage.labelRes: Int
    @StringRes get() = when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.lang_name_zh_hans
        AppLanguage.TRADITIONAL_CHINESE -> R.string.lang_name_zh_hant
        AppLanguage.ENGLISH -> R.string.lang_name_en
    }

private val AppLanguage.descriptionRes: Int
    @StringRes get() = when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system_desc
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.lang_desc_zh_hans
        AppLanguage.TRADITIONAL_CHINESE -> R.string.lang_desc_zh_hant
        AppLanguage.ENGLISH -> R.string.lang_desc_en
    }

@Composable
private fun SettingsSection(
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
private fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    showChevron: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (contentColor == MaterialTheme.colorScheme.error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
