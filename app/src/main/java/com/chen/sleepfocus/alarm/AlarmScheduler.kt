package com.chen.sleepfocus.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.data.model.SleepPlan
import com.chen.sleepfocus.data.prefs.SettingsStore
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 睡眠计划闹钟。
 *
 * 每条计划各自注册「开始冻结 / 到点解冻 / 解冻前预唤醒」三个闹钟，
 * 用计划 id 生成不同的 RequestCode 与广播参数，互不干扰。
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
    suspend fun reschedule() = reschedule(settings.plansOnce())

    /**
     * 重新注册全部计划：先取消旧版遗留闹钟与各计划的现有闹钟，再为启用的计划排下一次。
     * 已删除计划由其删除入口调用 [cancelPlan] 清理，不在这里处理。
     */
    fun reschedule(plans: List<SleepPlan>) {
        cancelLegacy()
        plans.forEach { plan ->
            cancelPlan(plan.id)
            if (plan.enabled) schedulePlan(plan)
        }
    }

    /** 取消某条计划的全部闹钟（禁用或删除计划时调用） */
    fun cancelPlan(planId: Long) {
        cancel(Constants.ACTION_SCHEDULE_START, planStartCode(planId))
        cancel(Constants.ACTION_SCHEDULE_STOP, planStopCode(planId))
        cancel(Constants.ACTION_PRE_WAKE, planPreWakeCode(planId))
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
            triggerAtMillis = triggerAtMillis,
            planId = null
        )
    }

    /** 取消进行中专注的提前唤醒闹钟（不影响各计划的提前唤醒） */
    fun cancelPreWake() {
        cancel(Constants.ACTION_PRE_WAKE, Constants.REQUEST_CODE_PRE_WAKE_SESSION)
    }

    private fun schedulePlan(plan: SleepPlan) {
        scheduleAt(
            action = Constants.ACTION_SCHEDULE_START,
            requestCode = planStartCode(plan.id),
            triggerAtMillis = nextTriggerMillis(plan.startMinuteOfDay),
            planId = plan.id
        )
        val stopAtMillis = nextTriggerMillis(plan.endMinuteOfDay)
        scheduleAt(
            action = Constants.ACTION_SCHEDULE_STOP,
            requestCode = planStopCode(plan.id),
            triggerAtMillis = stopAtMillis,
            planId = plan.id
        )
        // 解冻前提前唤醒：让设备在解冻前离开深度睡眠，否则到点解冻会被大幅延后
        scheduleAt(
            action = Constants.ACTION_PRE_WAKE,
            requestCode = planPreWakeCode(plan.id),
            triggerAtMillis = stopAtMillis - Constants.PRE_WAKE_LEAD_MS,
            planId = plan.id
        )
    }

    /** 升级前版本注册的固定 RequestCode 闹钟，重新排程时一并清掉 */
    private fun cancelLegacy() {
        cancel(Constants.ACTION_SCHEDULE_START, Constants.REQUEST_CODE_SCHEDULE_START)
        cancel(Constants.ACTION_SCHEDULE_STOP, Constants.REQUEST_CODE_SCHEDULE_STOP)
        cancel(Constants.ACTION_PRE_WAKE, Constants.REQUEST_CODE_PRE_WAKE_SCHEDULE)
    }

    private fun scheduleAt(
        action: String,
        requestCode: Int,
        triggerAtMillis: Long,
        planId: Long?
    ) {
        val pendingIntent = pendingIntent(action, requestCode, planId)
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
            alarmManager.cancel(pendingIntent(action, requestCode, planId = null))
        }
    }

    private fun pendingIntent(action: String, requestCode: Int, planId: Long?): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(action)
        if (planId != null) intent.putExtra(Constants.EXTRA_PLAN_ID, planId)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun planStartCode(planId: Long): Int =
        Constants.REQUEST_CODE_PLAN_START_BASE + planId.toInt()

    private fun planStopCode(planId: Long): Int =
        Constants.REQUEST_CODE_PLAN_STOP_BASE + planId.toInt()

    private fun planPreWakeCode(planId: Long): Int =
        Constants.REQUEST_CODE_PLAN_PRE_WAKE_BASE + planId.toInt()

    /** 当天 [minuteOfDay] 的下一次触发时间；已过则顺延到明天 */
    private fun nextTriggerMillis(minuteOfDay: Int): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }
}
