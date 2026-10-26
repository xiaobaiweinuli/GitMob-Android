package com.gitmob.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gitmob.app.R
import com.gitmob.app.ui.theme.ThemeMode
import com.gitmob.app.ui.theme.ThemePreference

/** 底部 Tab「设置」。底部 NavigationBar 的高度由外层 NavDisplay 统一处理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preference: ThemePreference,
    onAppearanceClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onLogout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
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
    val haptic = LocalHapticFeedback.current
    val currentLanguage = AppLanguage.current()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pendingLanguage by remember { mutableStateOf<AppLanguage?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_tab_settings)) },
                windowInsets = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                SettingsSection(title = stringResource(R.string.settings_section_preferences)) {
                    SettingsNavigationRow(
                        icon = Icons.Default.Palette,
                        title = stringResource(R.string.settings_appearance),
                        summary = appearanceSummary,
                        onClick = onAppearanceClick,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    SettingsDropdownRow(
                        title = stringResource(R.string.settings_language),
                        currentValue = stringResource(currentLanguage.labelRes),
                        icon = Icons.Default.Language,
                        onClosed = {
                            val selected = pendingLanguage
                            pendingLanguage = null
                            // NavDisplay keeps outgoing scenes composed at STARTED during a
                            // transition. Do not change locale after leaving this settings page.
                            if (selected != null &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                selected != AppLanguage.current()
                            ) {
                                AppLanguage.apply(selected)
                            }
                        },
                    ) { dismiss ->
                        AppLanguage.entries.forEach { language ->
                            SelectionRow(
                                title = stringResource(language.labelRes),
                                description = stringResource(language.descriptionRes),
                                selected = language == currentLanguage,
                                onClick = {
                                    if (pendingLanguage == null) {
                                        pendingLanguage = language
                                        dismiss()
                                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    }
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
                        onClick = { viewModel.logout(onLogout) },
                        contentColor = MaterialTheme.colorScheme.error,
                        trailingIcon = null,
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
