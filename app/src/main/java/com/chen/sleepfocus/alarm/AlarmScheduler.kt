package com.chen.sleepfocus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.data.model.ScheduleConfig
import com.chen.sleepfocus.data.prefs.SettingsStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 定时睡眠闹钟。
 *
 * Android 12+ 需要 SCHEDULE_EXACT_ALARM 才能使用 setExactAndAllowWhileIdle；
 * 未授权时降级为 setAndAllowWhileIdle（可能被系统延后几分钟），不影响倒计时核心功能。
 */
class AlarmScheduler(
    private val context: Context,
    private val settings: SettingsStore
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    /** 按当前设置重新注册（默认参数里不能调用 suspend 函数，所以拆成重载） */
    suspend fun reschedule() = reschedule(settings.scheduleOnce())

    /** 重新注册（会先取消旧的），[config] 为关闭状态时只做取消 */
    suspend fun reschedule(config: ScheduleConfig) {
        cancelAll()
        if (!config.enabled) return

        scheduleAt(
            action = Constants.ACTION_SCHEDULE_START,
            requestCode = Constants.REQUEST_CODE_SCHEDULE_START,
            triggerAtMillis = nextTriggerMillis(config.startMinuteOfDay)
        )
        val stopAtMillis = nextTriggerMillis(config.endMinuteOfDay)
        scheduleAt(
            action = Constants.ACTION_SCHEDULE_STOP,
            requestCode = Constants.REQUEST_CODE_SCHEDULE_STOP,
            triggerAtMillis = stopAtMillis
        )
        // 解冻前提前唤醒：让设备在解冻前离开深度睡眠，否则到点解冻会被大幅延后
        scheduleAt(
            action = Constants.ACTION_PRE_WAKE,
            requestCode = Constants.REQUEST_CODE_PRE_WAKE_SCHEDULE,
            triggerAtMillis = stopAtMillis - Constants.PRE_WAKE_LEAD_MS
        )
    }

    /**
     * 为正在进行中的专注（手动或定时）注册提前唤醒闹钟。
     * [endTimeMillis] 为计划解冻时刻；剩余时间已不足提前量时不再注册。
     */
    fun schedulePreWakeAt(endTimeMillis: Long) {
        val triggerAtMillis = endTimeMillis - Constants.PRE_WAKE_LEAD_MS
        if (triggerAtMillis <= System.currentTimeMillis()) return
        scheduleAt(
            action = Constants.ACTION_PRE_WAKE,
            requestCode = Constants.REQUEST_CODE_PRE_WAKE_SESSION,
            triggerAtMillis = triggerAtMillis
        )
    }

    /** 取消进行中专注的提前唤醒闹钟（不影响定时睡眠配置的提前唤醒） */
    fun cancelPreWake() {
        cancel(Constants.ACTION_PRE_WAKE, Constants.REQUEST_CODE_PRE_WAKE_SESSION)
    }

    fun cancelAll() {
        cancel(Constants.ACTION_SCHEDULE_START, Constants.REQUEST_CODE_SCHEDULE_START)
        cancel(Constants.ACTION_SCHEDULE_STOP, Constants.REQUEST_CODE_SCHEDULE_STOP)
        cancel(Constants.ACTION_PRE_WAKE, Constants.REQUEST_CODE_PRE_WAKE_SCHEDULE)
    }

    private fun scheduleAt(action: String, requestCode: Int, triggerAtMillis: Long) {
        val pendingIntent = pendingIntent(action, requestCode)
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (t: SecurityException) {
            // 精确闹钟权限被撤销时降级，不中断主流程
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        }
    }

    private fun cancel(action: String, requestCode: Int) {
        runCatching {
            alarmManager.cancel(pendingIntent(action, requestCode))
        }
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 当天 [minuteOfDay] 的下一次触发时间；已过则顺延到明天 */
    private fun nextTriggerMillis(minuteOfDay: Int): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }
}