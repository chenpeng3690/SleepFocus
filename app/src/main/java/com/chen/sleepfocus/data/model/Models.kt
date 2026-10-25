package com.chen.sleepfocus.data.model

import android.graphics.drawable.Drawable
import java.time.Instant
import java.time.ZoneId

/**
 * 已安装应用条目（用于应用选择页）。
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: Drawable?
)

/**
 * Root 状态。UNKNOWN 表示尚未检测。
 */
enum class RootStatus {
    UNKNOWN, GRANTED, DENIED;

    companion object {
        fun fromName(name: String?): RootStatus =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/**
 * 界面配色模式。默认跟随系统，可显式指定浅色或深色。
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 中断专注模式，控制专注进行中能否在主页提前停止并解冻。
 * - ALLOW：随时可中断（默认）
 * - DELAY：需等待倒计时结束后才能中断
 * - DISALLOW：专注期间禁止中断
 */
enum class InterruptMode {
    ALLOW, DELAY, DISALLOW;

    companion object {
        fun fromName(name: String?): InterruptMode =
            entries.firstOrNull { it.name == name } ?: ALLOW
    }
}

/**
 * Root 检测结果。
 */
data class RootCheckResult(
    val granted: Boolean,
    val detail: String
)

/**
 * 统计汇总。
 * - [todayMinutes] / [weekMinutes] / [totalMinutes]：按“提前停止是否计入”开关计算
 * - 完成次数只统计 COMPLETED；中断次数只统计 STOPPED
 */
data class StatsSummary(
    val todayMinutes: Int = 0,
    val weekMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val todayCompleted: Int = 0,
    val todayStopped: Int = 0,
    val totalCompleted: Int = 0,
    val totalStopped: Int = 0
)

/**
 * 柱状图的一天。
 */
data class DailyFocus(
    val dayStart: Long,
    val minutes: Int
)

/**
 * 某个月份的专注汇总。
 * [minutes] 遵循「提前停止是否计入」开关；[count] 只统计已完成的专注（中断不计入）。
 */
data class MonthFocus(
    val monthStart: Long,
    val minutes: Int,
    val count: Int
)

/**
 * 定时睡眠配置。[startMinuteOfDay] / [endMinuteOfDay] 为当天 0 点起的分钟数。
 * 当 end <= start 时视为跨天（例如 23:00 -> 07:00）。
 */
data class ScheduleConfig(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 23 * 60,
    val endMinuteOfDay: Int = 7 * 60
) {
    /** 冻结时长（分钟），跨天时自动 +24h */
    val durationMinutes: Int
        get() {
            val diff = endMinuteOfDay - startMinuteOfDay
            return if (diff > 0) diff else diff + 24 * 60
        }

    /**
     * 若 [timeMillis] 落在本配置的睡眠时间段内（每日重复、可跨天），
     * 返回该时间段对应的结束时刻；不在时间段内则返回 null。
     * 手动专注的计划结束时刻落在时间段内时，据此顺延到结束时刻解冻。
     */
    fun windowEndContaining(timeMillis: Long): Long? {
        val durationMillis = durationMinutes * 60_000L
        if (durationMillis <= 0L) return null
        val zone = ZoneId.systemDefault()
        val dayStart = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        var windowStart = dayStart + startMinuteOfDay * 60_000L
        if (timeMillis < windowStart) windowStart -= DAY_MILLIS
        val windowEnd = windowStart + durationMillis
        return if (timeMillis < windowEnd) windowEnd else null
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}