package com.chen.sleepfocus.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.chen.sleepfocus.R
import com.chen.sleepfocus.SleepFocusApp
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.service.FocusService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 定时睡眠与提前唤醒：
 * - ACTION_SCHEDULE_START：冻结已选应用并启动倒计时
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

        val app = SleepFocusApp.get()
        val pendingResult = goAsync()

        app.appScope.launch {
            try {
                val config = app.settings.scheduleOnce()
                if (!config.enabled) {
                    app.alarmScheduler.cancelAll()
                    return@launch
                }

                when (action) {
                    Constants.ACTION_SCHEDULE_START -> {
                        val startRequested = handleStart(app, context, config.durationMinutes)
                        // 冻结在服务协程里异步执行，必须等它落库为 RUNNING 之后再释放
                        // 广播的临时 WakeLock。否则这段没有唤醒锁保护的窗口里设备可能重新
                        // 挂起，冻结会被推迟到下次唤醒，表现为「进入冻结延后」。
                        if (startRequested) awaitSessionRunning(app)
                    }

                    Constants.ACTION_SCHEDULE_STOP -> handleStop(app, context)
                }

                // 安排下一次（每天循环）
                app.alarmScheduler.reschedule(config)
            } catch (t: Throwable) {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "定时睡眠执行异常：${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 触发定时睡眠的开始。
     *
     * @return 是否真的向 [FocusService] 发出了启动请求（未选应用 / 已有专注时返回 false，
     *         调用方据此决定是否还需要等待冻结完成）
     */
    private suspend fun handleStart(
        app: SleepFocusApp,
        context: Context,
        durationMinutes: Int
    ): Boolean {
        val storedPackages = app.settings.selectedPackagesOnce()
        // 剔掉已被卸载的应用，避免对不存在的包执行冻结命令而误报失败
        val packages = storedPackages.filter { app.appRepository.isInstalled(it) }
        if (packages.size != storedPackages.size) {
            app.settings.setSelectedPackages(packages.toSet())
            app.focusRepository.log(
                action = Constants.LOG_ACTION_SESSION,
                packageName = null,
                success = true,
                message = "定时睡眠触发前清理了 ${storedPackages.size - packages.size} 个已卸载应用"
            )
        }
        return when {
            packages.isEmpty() -> {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "定时睡眠触发，但没有已选应用，已跳过"
                )
                false
            }

            app.focusRepository.getRunningSession() != null -> {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "定时睡眠触发，但已有专注在进行中，已跳过"
                )
                false
            }

            else -> {
                val started = FocusService.start(context.applicationContext, durationMinutes, packages)
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = started,
                    message = if (started) {
                        "定时睡眠触发，冻结 ${packages.size} 个应用，时长 $durationMinutes 分钟"
                    } else {
                        "定时睡眠启动前台服务失败"
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
     * 等待冻结真正完成（会话落库为 RUNNING），超时后放弃。
     *
     * 冻结在 [FocusService] 的协程里异步执行，而本方法运行在广播的 [goAsync] 生命周期内，
     * 此时系统为广播持有的临时 WakeLock 尚未释放 —— 正好用它护住「响铃到冻结落地」这段窗口。
     */
    private suspend fun awaitSessionRunning(app: SleepFocusApp) {
        val deadline = System.currentTimeMillis() + Constants.SCHEDULE_AWAIT_RUNNING_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (app.focusRepository.getRunningSession() != null) return
            delay(Constants.SCHEDULE_AWAIT_RUNNING_POLL_MS)
        }
        app.focusRepository.log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = false,
            message = "等待冻结完成超时（${Constants.SCHEDULE_AWAIT_RUNNING_TIMEOUT_MS / 1000} 秒），已释放唤醒锁"
        )
    }

    private suspend fun handleStop(app: SleepFocusApp, context: Context) {
        if (app.focusRepository.getRunningSession() != null) {
            FocusService.requestFinish(context.applicationContext)
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