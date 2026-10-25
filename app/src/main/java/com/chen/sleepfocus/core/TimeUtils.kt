package com.chen.sleepfocus.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * 时间格式化与统计区间计算工具。minSdk 26 可直接使用 java.time。
 */
object TimeUtils {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val dateTimeSecondsFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val dateSlashFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private val monthLabelFormatter = DateTimeFormatter.ofPattern("yyyy年M月")
    private val fileStampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /** 倒计时显示：不足 1 小时显示 mm:ss，否则 h:mm:ss */
    fun formatCountdown(millisLeft: Long): String {
        val totalSeconds = (millisLeft.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /** 通知栏用的简短文案：如 "1 小时 5 分" / "25 分钟" */
    fun formatDurationText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return if (m >= 60) {
            val h = m / 60
            val rest = m % 60
            if (rest == 0) "${h} 小时" else "${h} 小时 ${rest} 分"
        } else {
            "$m 分钟"
        }
    }

    /**
     * 实际专注分钟数：(endTime - startTime) / 60000，四舍五入。
     */
    fun actualMinutes(startTime: Long, endTime: Long): Int {
        if (endTime <= startTime) return 0
        return ((endTime - startTime) / 60_000.0).roundToInt()
    }

    fun formatDateTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dateTimeFormatter)

    /** 带秒的完整时间，日志页用 */
    fun formatDateTimeSeconds(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dateTimeSecondsFormatter)

    fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(timeFormatter)

    fun formatDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dateFormatter)

    /** 柱状图坐标轴用的日期，如 2026/10/08 */
    fun formatDateSlash(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dateSlashFormatter)

    /** 中文月份标签，如 2026年10月 */
    fun formatMonth(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(monthLabelFormatter)

    /** 备份文件名用的紧凑时间戳，如 20261011_153012 */
    fun formatFileStamp(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(fileStampFormatter)

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun startOfToday(): Long = startOfDay(LocalDate.now(zone))

    /** 本周起始（周一 00:00） */
    fun startOfWeek(): Long {
        val monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return startOfDay(monday)
    }

    /** 最近 [days] 天的 00:00 时间戳，按时间升序，最后一个是今天 */
    fun lastDayStarts(days: Int): List<Long> {
        val today = LocalDate.now(zone)
        return (days - 1 downTo 0).map { offset -> startOfDay(today.minusDays(offset.toLong())) }
    }

    /** 某时间戳所在日期的 00:00 */
    fun dayStartOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().let { startOfDay(it) }

    /** 某时间戳所在月份第一天的 00:00 */
    fun monthStartOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            .withDayOfMonth(1).let { startOfDay(it) }

    /** [monthStart] 所在月份的下个月第一天的 00:00 */
    fun nextMonthStartOf(monthStart: Long): Long =
        Instant.ofEpochMilli(monthStart).atZone(zone).toLocalDate()
            .plusMonths(1).withDayOfMonth(1).let { startOfDay(it) }

    /** 是否同一天 */
    fun isSameDay(a: Long, b: Long): Boolean = dayStartOf(a) == dayStartOf(b)
}