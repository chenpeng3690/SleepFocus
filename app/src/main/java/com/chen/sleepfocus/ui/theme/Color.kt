package com.chen.sleepfocus.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 深色优先配色。睡眠 / 专注工具在夜间使用，因此不做动态取色，
 * 保证任何机型上都是同一套低亮度、低饱和的观感。
 */
val NightBackground = Color(0xFF0E1116)
val NightSurface = Color(0xFF151A22)
val NightSurfaceVariant = Color(0xFF1E2530)
val NightSurfaceContainer = Color(0xFF1A212B)

val NightPrimary = Color(0xFF7C8CFF)
val NightOnPrimary = Color(0xFF0A0F1E)
val NightPrimaryContainer = Color(0xFF262E4F)
val NightOnPrimaryContainer = Color(0xFFDDE2FF)

val NightSecondary = Color(0xFF4FD1BE)
val NightOnSecondary = Color(0xFF04231E)
val NightSecondaryContainer = Color(0xFF163832)

val NightTertiary = Color(0xFFFFB870)
val NightOnTertiary = Color(0xFF2B1A05)

val NightError = Color(0xFFFF6B6B)
val NightOnError = Color(0xFF2B0A0A)
val NightErrorContainer = Color(0xFF3A1518)
val NightOnErrorContainer = Color(0xFFFFDAD6)

val NightOnBackground = Color(0xFFE7EBF3)
val NightOnSurface = Color(0xFFE7EBF3)
val NightOnSurfaceVariant = Color(0xFF98A4B8)
val NightOutline = Color(0xFF3B4553)
val NightOutlineVariant = Color(0xFF2A323D)

/**
 * 浅色配色。作为可选方案保留：白天使用时切换到浅色，观感与深色模式保持同一套色相。
 */
val DayBackground = Color(0xFFF5F6FA)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceVariant = Color(0xFFE4E7EF)
val DaySurfaceContainer = Color(0xFFEDEFF6)

val DayPrimary = Color(0xFF4655C9)
val DayOnPrimary = Color(0xFFFFFFFF)
val DayPrimaryContainer = Color(0xFFDDE2FF)
val DayOnPrimaryContainer = Color(0xFF11173F)

val DaySecondary = Color(0xFF157F70)
val DayOnSecondary = Color(0xFFFFFFFF)
val DaySecondaryContainer = Color(0xFFC6EFE7)

val DayTertiary = Color(0xFF9A5A12)
val DayOnTertiary = Color(0xFFFFFFFF)

val DayError = Color(0xFFB3261E)
val DayOnError = Color(0xFFFFFFFF)
val DayErrorContainer = Color(0xFFFFDAD6)
val DayOnErrorContainer = Color(0xFF410002)

val DayOnBackground = Color(0xFF1A1C22)
val DayOnSurface = Color(0xFF1A1C22)
val DayOnSurfaceVariant = Color(0xFF5B6272)
val DayOutline = Color(0xFFA9B1C0)
val DayOutlineVariant = Color(0xFFD9DDE6)

// 状态色：柱状图与状态标签共用。取中间明度，保证深色 / 浅色两种模式下都清晰可读
val StatRunning = Color(0xFF6577E8)
val StatCompleted = Color(0xFF2FAE9C)
val StatStopped = Color(0xFFE0962F)