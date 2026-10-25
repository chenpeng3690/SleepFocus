package com.chen.sleepfocus.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chen.sleepfocus.SleepFocusApp
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.service.FocusService
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后：
 * 1. 检查是否有 RUNNING 专注，有则交给 FocusService 恢复（过期则解冻并标记完成）；
 * 2. 重新注册定时睡眠闹钟（重启后系统的闹钟会被清空）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val app = SleepFocusApp.get()
        val pendingResult = goAsync()

        app.appScope.launch {
            try {
                val sessions = app.focusRepository.getRunningSessions()
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_BOOT_RECOVER,
                    packageName = null,
                    success = true,
                    message = "收到 $action，发现 ${sessions.size} 个未完成专注记录"
                )

                app.alarmScheduler.reschedule()

                if (sessions.isNotEmpty()) {
                    val requested = FocusService.restore(context.applicationContext)
                    if (!requested) {
                        app.focusRepository.log(
                            action = Constants.LOG_ACTION_BOOT_RECOVER,
                            packageName = null,
                            success = false,
                            message = "无法启动前台服务恢复专注记录，请手动打开应用处理"
                        )
                        app.notifications.notifyAlert(
                            app.getString(com.chen.sleepfocus.R.string.notify_root_error_title),
                            "存在未完成的专注记录，但恢复服务启动失败，请手动打开 SleepFocus 处理。"
                        )
                    }
                }
            } catch (t: Throwable) {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_BOOT_RECOVER,
                    packageName = null,
                    success = false,
                    message = "开机恢复异常：${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}