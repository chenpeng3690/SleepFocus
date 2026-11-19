package com.chen.sleepfocus.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.chen.sleepfocus.R
import com.chen.sleepfocus.SleepFocusApp
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.data.model.SleepPlan
import com.chen.sleepfocus.service.FocusService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 睡眠计划与提前唤醒：
 * - ACTION_SCHEDULE_START：冻结该计划所选应用并启动倒计时
 * - ACTION_SCHEDULE_STOP ：到解冻时间，若仍在进行则按「已完成」结束
 * - ACTION_PRE_WAKE      ：解冻前提前唤醒，申请 WakeLock 让 CPU 保持清醒
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 提前唤醒：只负责把设备从深度睡眠中拉起来，不改变任何业务状态
        if (action == Constants.ACTION_PRE_WAKE) {
            acquirePreWakeLock(context)
            return
        }

        if (action != Constants.ACTION_SCHEDULE_START && action != Constants.ACTION_SCHEDULE_STOP) return

        val planId = intent.getLongExtra(Constants.EXTRA_PLAN_ID, 0L)
        if (planId <= 0L) return

        val app = SleepFocusApp.get()
        val pendingResult = goAsync()

        app.appScope.launch {
            try {
                val plan = app.settings.plansOnce().firstOrNull { it.id == planId }
                if (plan == null || !plan.enabled) {
                    // 计划已被删除或关闭，清掉它的残留闹钟
                    app.alarmScheduler.cancelPlan(planId)
                    return@launch
                }

                when (action) {
                    Constants.ACTION_SCHEDULE_START -> {
                        val startRequested = handleStart(app, context, plan)
                        // 冻结在服务协程里异步执行，必须等它落库为 RUNNING 之后再释放
                        // 广播的临时 WakeLock。否则这段没有唤醒锁保护的窗口里设备可能重新
                        // 挂起，冻结会被推迟到下次唤醒，表现为「进入冻结延后」。
                        if (startRequested) awaitSessionRunning(app, plan.id)
                    }

                    Constants.ACTION_SCHEDULE_STOP -> handleStop(app, context, plan)
                }

                // 安排下一次（每天循环）
                app.alarmScheduler.reschedule(app.settings.plansOnce())
            } catch (t: Throwable) {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "睡眠计划执行异常：${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 触发某条计划的开始。
     *
     * @return 是否真的向 [FocusService] 发出了启动请求（未选应用 / 已有专注时返回 false，
     *         调用方据此决定是否还需要等待冻结完成）
     */
    private suspend fun handleStart(
        app: SleepFocusApp,
        context: Context,
        plan: SleepPlan
    ): Boolean {
        val storedPackages = plan.packages
        // 剔掉已被卸载的应用，避免对不存在的包执行冻结命令而误报失败
        val packages = storedPackages.filter { app.appRepository.isInstalled(it) }
        if (packages.size != storedPackages.size) {
            app.settings.setPlans(
                app.settings.plansOnce().map { current ->
                    if (current.id == plan.id) {
                        current.copy(packages = packages.toSet())
                    } else {
                        current
                    }
                }
            )
            app.focusRepository.log(
                action = Constants.LOG_ACTION_SESSION,
                packageName = null,
                success = true,
                message = "计划 #${plan.id} 触发前清理了 ${storedPackages.size - packages.size} 个已卸载应用"
            )
        }
        val running = app.focusRepository.getRunningSession()

        return when {
            packages.isEmpty() -> {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "计划 #${plan.id} 触发，但没有已选应用，已跳过"
                )
                false
            }

            // 另一条计划正在执行：由后触发的这条计划接管，先结束旧专注再启动本次计划
            running != null && running.sourcePlanId != null && running.sourcePlanId != plan.id -> {
                val started = FocusService.start(
                    context.applicationContext,
                    plan.durationMinutes,
                    packages,
                    planId = plan.id,
                    replaceRunning = true
                )
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = started,
                    message = if (started) {
                        "计划 #${plan.id} 接管计划 #${running.sourcePlanId}，" +
                            "冻结 ${packages.size} 个应用，时长 ${plan.durationMinutes} 分钟"
                    } else {
                        "计划 #${plan.id} 接管失败，无法启动前台服务"
                    }
                )
                if (!started) {
                    app.notifications.notifyAlert(
                        app.getString(R.string.schedule_fail_title),
                        app.getString(R.string.schedule_fail_text)
                    )
                }
                started
            }

            running != null -> {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "计划 #${plan.id} 触发，但已有手动专注在进行中，已跳过"
                )
                false
            }

            else -> {
                val started = FocusService.start(
                    context.applicationContext,
                    plan.durationMinutes,
                    packages,
                    planId = plan.id
                )
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = started,
                    message = if (started) {
                        "计划 #${plan.id} 触发，冻结 ${packages.size} 个应用，时长 ${plan.durationMinutes} 分钟"
                    } else {
                        "计划 #${plan.id} 启动前台服务失败"
                    }
                )
                if (!started) {
                    app.notifications.notifyAlert(
                        app.getString(R.string.schedule_fail_title),
                        app.getString(R.string.schedule_fail_text)
                    )
                }
                started
            }
        }
    }

    /**
     * 等待本次计划的冻结真正完成（对应来源的会话落库为 RUNNING），超时后放弃。
     *
     * 冻结在 [FocusService] 的协程里异步执行，而本方法运行在广播的 [goAsync] 生命周期内，
     * 此时系统为广播持有的临时 WakeLock 尚未释放 —— 正好用它护住「响铃到冻结落地」这段窗口。
     * 接管场景下旧专注仍处于 RUNNING，必须等来源变成这条计划后才算完成。
     */
    private suspend fun awaitSessionRunning(app: SleepFocusApp, planId: Long) {
        val deadline = System.currentTimeMillis() + Constants.SCHEDULE_AWAIT_RUNNING_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val running = app.focusRepository.getRunningSession()
            if (running != null && running.sourcePlanId == planId) return
            delay(Constants.SCHEDULE_AWAIT_RUNNING_POLL_MS)
        }
        app.focusRepository.log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = false,
            message = "等待冻结完成超时（${Constants.SCHEDULE_AWAIT_RUNNING_TIMEOUT_MS / 1000} 秒），已释放唤醒锁"
        )
    }

    /**
     * 计划到点解冻。
     *
     * 多条计划可能时间重叠，这里只结束「由这条计划启动」的专注（按 sourcePlanId 判断），
     * 避免把后来接管的其他计划或手动专注提前结束掉。旧数据或手动顺延的专注
     * 没有来源信息，退回「时长一致 + 结束时刻接近」的兜底判断。
     */
    private suspend fun handleStop(app: SleepFocusApp, context: Context, plan: SleepPlan) {
        val running = app.focusRepository.getRunningSession() ?: return
        val endDiff = kotlin.math.abs(running.plannedEndTime - System.currentTimeMillis())
        // 有计划来源时按 id 精确匹配；手动专注（sourcePlanId 为空）沿用时长 + 结束时刻的兜底判断
        val belongsToPlan = if (running.sourcePlanId != null) {
            running.sourcePlanId == plan.id
        } else {
            running.plannedMinutes == plan.durationMinutes &&
                endDiff <= Constants.SCHEDULE_STOP_MATCH_TOLERANCE_MS
        }
        // 若另一条启用的计划恰好在同一时刻开始，则交由它接管，避免结束请求与接管流程互相打断
        val nextTakesOver = belongsToPlan && app.settings.plansOnce().any { other ->
            other.enabled &&
                other.id != plan.id &&
                other.startMinuteOfDay == plan.endMinuteOfDay
        }

        when {
            nextTakesOver -> {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = true,
                    message = "计划 #${plan.id} 到点，后一条计划同时开始，交由其接管"
                )
            }

            belongsToPlan -> {
                FocusService.requestFinish(context.applicationContext)
            }

            else -> {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "计划 #${plan.id} 到点解冻，但进行中的专注不属于该计划，已跳过"
                )
            }
        }
    }

    /**
     * 申请一个带超时的 PARTIAL WakeLock。
     *
     * 设备进入深度睡眠后 CPU 会挂起，倒计时的协程定时器与到点闹钟都无法准时执行，
     * 导致解冻被推迟。提前几分钟唤醒并让 CPU 保持清醒直到解冻时刻之后，
     * 定时器与闹钟即可正常触发。
     */
    private fun acquirePreWakeLock(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PRE_WAKE_LOCK_TAG)
        // 非引用计数：重复触发时直接续期，无需配对释放，超时后由系统自动释放
        lock.setReferenceCounted(false)
        lock.acquire(Constants.PRE_WAKE_WAKELOCK_TIMEOUT_MS)

        val app = SleepFocusApp.get()
        app.appScope.launch {
            app.focusRepository.log(
                action = Constants.LOG_ACTION_SESSION,
                packageName = null,
                success = true,
                message = "解冻前提前唤醒，保持清醒 ${Constants.PRE_WAKE_WAKELOCK_TIMEOUT_MS / 1000} 秒"
            )
        }
    }

    private companion object {
        const val PRE_WAKE_LOCK_TAG = "SleepFocus:preWake"
    }
}