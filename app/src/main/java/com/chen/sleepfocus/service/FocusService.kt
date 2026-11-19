package com.chen.sleepfocus.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chen.sleepfocus.SleepFocusApp
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.focus.FocusAlert
import com.chen.sleepfocus.focus.FocusSessionController
import com.chen.sleepfocus.focus.SystemTicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 专注前台服务的 Android 外壳。
 *
 * 这里只做三件事：生命周期、前台通知、把请求转交给 [FocusSessionController]。
 * 倒计时、冻结、解冻、恢复、统计落库等全部逻辑都在控制器里，由单元测试覆盖。
 *
 * 关键约束：startForegroundService 之后必须在 5 秒内 startForeground，
 * 而检查 Root（可能弹 Magisk 授权框）+ 冻结应用可能更久，
 * 所以 onStartCommand 里先用「准备中」通知进入前台，再去做具体工作。
 */
class FocusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: SleepFocusApp
    private lateinit var controller: FocusSessionController

    private var mainJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = SleepFocusApp.get()
        controller = FocusSessionController(
            freezer = app.packageController,
            store = app.focusRepository,
            notifier = app.notifications,
            ticker = SystemTicker
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_START -> {
                startForegroundSafely(app.notifications.buildPreparing())
                val minutes = intent.getIntExtra(Constants.EXTRA_MINUTES, Constants.DEFAULT_MINUTES)
                val packages = intent.getStringArrayListExtra(Constants.EXTRA_PACKAGES).orEmpty()
                val sourcePlanId = intent.getLongExtra(Constants.EXTRA_SOURCE_PLAN_ID, 0L)
                    .takeIf { it > 0L }
                val replaceRunning = intent.getBooleanExtra(Constants.EXTRA_REPLACE_RUNNING, false)
                // 按计划结束时间注册提前唤醒，避免长时间深度睡眠导致到点无法及时解冻
                app.alarmScheduler.schedulePreWakeAt(
                    System.currentTimeMillis() + minutes * 60_000L
                )
                if (replaceRunning) {
                    runReplacingSession { controller.start(minutes, packages, sourcePlanId) }
                } else {
                    runSession { controller.start(minutes, packages, sourcePlanId) }
                }
                return START_STICKY
            }

            Constants.ACTION_RESTORE -> {
                startForegroundSafely(app.notifications.buildPreparing())
                runSession {
                    scheduleRestorePreWake()
                    controller.restore()
                }
                return START_STICKY
            }

            Constants.ACTION_FINISH -> {
                scope.launch { stopSession(Constants.STATUS_COMPLETED) }
                return START_NOT_STICKY
            }

            Constants.ACTION_STOP -> {
                scope.launch { stopSession(Constants.STATUS_STOPPED) }
                return START_NOT_STICKY
            }

            else -> {
                // 进程被系统回收后重启（intent 为 null）：按数据库里的未完成专注恢复
                startForegroundSafely(app.notifications.buildPreparing())
                runSession {
                    scheduleRestorePreWake()
                    controller.restore()
                }
                return START_STICKY
            }
        }
    }

    /** 运行一次专注，返回即代表本次专注已经结束或中止 */
    private fun runSession(block: suspend () -> Unit) {
        if (mainJob?.isActive == true) return
        mainJob = scope.launch {
            try {
                block()
                shutdownService()
            } catch (c: CancellationException) {
                // 由 stopSession 负责收尾（解冻 + 落库），这里不能吞掉
                throw c
            } catch (t: Throwable) {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "专注服务异常：${t.message ?: t.javaClass.simpleName}"
                )
                app.notifications.notifyAlert(
                    FocusAlert.SERVICE_ERROR,
                    t.message ?: t.javaClass.simpleName
                )
                withContext(NonCancellable) { controller.stop(Constants.STATUS_STOPPED) }
                shutdownService()
            }
        }
    }

    /**
     * 计划重叠时由后触发的计划接管：
     * 先取消并结束旧专注（解冻 + 落库为已中断），再在同一个服务实例里开始新计划。
     * 这样避免「先 requestStop 再重新 startService」之间服务被 stopSelf 的竞态。
     */
    private fun runReplacingSession(block: suspend () -> Unit) {
        val previous = mainJob
        mainJob = null

        var currentJob: Job? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.cancelAndJoin()
                // 旧计划已到或即将到解冻时刻（例如两条计划首尾相接）记为已完成，否则记为中断
                val oldSession = app.focusRepository.getRunningSession()
                val oldStatus = if (oldSession != null &&
                    oldSession.plannedEndTime <= System.currentTimeMillis() + 60_000L
                ) {
                    Constants.STATUS_COMPLETED
                } else {
                    Constants.STATUS_STOPPED
                }
                controller.stop(oldStatus)
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "计划接管专注异常：${t.message ?: t.javaClass.simpleName}"
                )
                app.notifications.notifyAlert(
                    FocusAlert.SERVICE_ERROR,
                    t.message ?: t.javaClass.simpleName
                )
                withContext(NonCancellable) { controller.stop(Constants.STATUS_STOPPED) }
            } finally {
                // 被更新的接管或用户停止取代时，由新任务 / stopSession 负责收尾
                if (mainJob === currentJob) shutdownService()
            }
        }
        currentJob = job
        mainJob = job
        job.start()
    }

    /** 用户提前停止 / 定时到点结束 */
    private suspend fun stopSession(status: String) {
        val job = mainJob
        mainJob = null
        job?.cancelAndJoin()
        controller.stop(status)
        shutdownService()
    }

    /** 进程重启 / 开机恢复时，按数据库里的计划结束时间补注册提前唤醒 */
    private suspend fun scheduleRestorePreWake() {
        app.focusRepository.getRunningSession()?.let {
            app.alarmScheduler.schedulePreWakeAt(it.plannedEndTime)
        }
    }

    private fun shutdownService() {
        app.alarmScheduler.cancelPreWake()
        app.notifications.cancelOngoing()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Constants.NOTIFY_ID_ONGOING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Constants.NOTIFY_ID_ONGOING, notification)
            }
        } catch (t: Throwable) {
            scope.launch {
                app.focusRepository.log(
                    action = Constants.LOG_ACTION_SESSION,
                    packageName = null,
                    success = false,
                    message = "startForeground 失败：${t.message ?: t.javaClass.simpleName}"
                )
            }
            app.notifications.notifyAlert(
                FocusAlert.SERVICE_ERROR,
                "无法进入前台服务：${t.message ?: "未知错误"}"
            )
            stopSelf()
        }
    }

    companion object {
        /**
         * @param planId 触发本次专注的睡眠计划；手动开始传 null
         * @param replaceRunning 为 true 时先结束当前进行中的专注，由本次计划接管
         * @return 是否成功发出启动请求
         */
        fun start(
            context: Context,
            minutes: Int,
            packages: List<String>,
            planId: Long? = null,
            replaceRunning: Boolean = false
        ): Boolean {
            val intent = Intent(context, FocusService::class.java)
                .setAction(Constants.ACTION_START)
                .putExtra(Constants.EXTRA_MINUTES, minutes)
                .putStringArrayListExtra(Constants.EXTRA_PACKAGES, ArrayList(packages))
                .putExtra(Constants.EXTRA_REPLACE_RUNNING, replaceRunning)
            if (planId != null) {
                intent.putExtra(Constants.EXTRA_SOURCE_PLAN_ID, planId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }

        /** 开机 / 进程重启后按数据库恢复 */
        fun restore(context: Context): Boolean {
            val intent = Intent(context, FocusService::class.java)
                .setAction(Constants.ACTION_RESTORE)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }

        /** 用户提前停止：记为 STOPPED */
        fun requestStop(context: Context): Boolean {
            val intent = Intent(context, FocusService::class.java)
                .setAction(Constants.ACTION_STOP)
            return runCatching {
                context.startService(intent)
                true
            }.getOrDefault(false)
        }

        /** 定时睡眠到点：记为 COMPLETED */
        fun requestFinish(context: Context): Boolean {
            val intent = Intent(context, FocusService::class.java)
                .setAction(Constants.ACTION_FINISH)
            return runCatching {
                context.startService(intent)
                true
            }.getOrDefault(false)
        }
    }
}