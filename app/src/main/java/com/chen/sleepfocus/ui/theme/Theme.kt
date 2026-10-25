package com.chen.sleepfocus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.chen.sleepfocus.data.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = NightOnPrimaryContainer,
    secondary = NightSecondary,
    onSecondary = NightOnSecondary,
    secondaryContainer = NightSecondaryContainer,
    tertiary = NightTertiary,
    onTertiary = NightOnTertiary,
    error = NightError,
    onError = NightOnError,
    errorContainer = NightErrorContainer,
    onErrorContainer = NightOnErrorContainer,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceContainer = NightSurfaceContainer,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = DayPrimary,
    onPrimary = DayOnPrimary,
    primaryContainer = DayPrimaryContainer,
    onPrimaryContainer = DayOnPrimaryContainer,
    secondary = DaySecondary,
    onSecondary = DayOnSecondary,
    secondaryContainer = DaySecondaryContainer,
    tertiary = DayTertiary,
    onTertiary = DayOnTertiary,
    error = DayError,
    onError = DayOnError,
    errorContainer = DayErrorContainer,
    onErrorContainer = DayOnErrorContainer,
    background = DayBackground,
    onBackground = DayOnBackground,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceContainer = DaySurfaceContainer,
    outline = DayOutline,
    outlineVariant = DayOutlineVariant
)

/**
 * 全局主题。
 *
 * 默认跟随系统深浅色，用户可在设置的「颜色切换」里显式指定浅色或深色。
 * [ThemeMode.SYSTEM] 下读取系统配置，系统切换深浅色时自动跟随。
 */
@Composable
fun SleepFocusTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = SleepFocusTypography,
        content = content
    )
}