package com.chen.sleepfocus.focus

import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 专注专注状态机。纯 Kotlin，不依赖任何 Android 类，因此可被 JVM 单元测试完整覆盖。
 *
 * 职责：
 * 1. 开始：校验 Root -> 冻结 -> 落库 RUNNING -> 倒计时循环（刷新通知 + 每 30 秒复查冻结）
 * 2. 结束：解冻 -> 落库 COMPLETED / STOPPED -> 通知（幂等，可被并发触发两次）
 * 3. 恢复：开机或进程重启后，过期专注直接解冻并标记完成；未过期则重新冻结并继续倒计时
 *
 * 所有副作用都通过构造参数注入，FocusService 只负责生命周期与前台通知的接线。
 */
class FocusSessionController(
    private val freezer: PackageFreezer,
    private val store: SessionStore,
    private val notifier: FocusNotifier,
    private val ticker: Ticker
) {

    @Volatile
    private var sessionId: Long = 0L

    @Volatile
    private var plannedEndTime: Long = 0L

    @Volatile
    private var frozen: List<String> = emptyList()

    /**
     * 本次请求要冻结的包。冻结执行到一半被取消（用户按了停止）时，
     * 靠它才知道该去解冻哪些包，避免应用被永久禁用。
     */
    @Volatile
    private var requestedTargets: List<String> = emptyList()

    @Volatile
    var isRunning: Boolean = false
        private set

    // ---------------- 开始 ----------------

    /**
     * 开始一次专注，阻塞直到倒计时自然结束（或抛 CancellationException 被取消）。
     * 未真正开始时（未选应用 / Root 不可用 / 全部冻结失败）直接返回，且不产生专注记录。
     */
    suspend fun start(minutes: Int, packages: List<String>) {
        requestedTargets = emptyList()
        val targets = packages.distinct().filter { it.isNotBlank() }
        val plannedMinutes = minutes.coerceIn(Constants.MIN_MINUTES, Constants.MAX_MINUTES)

        if (targets.isEmpty()) {
            store.log(Constants.LOG_ACTION_SESSION, null, false, "未选择任何应用，已取消开始")
            notifier.notifyAlert(FocusAlert.NO_APPS_SELECTED)
            return
        }

        // 已有未完成专注：转为恢复，避免重复开始
        if (store.getRunningSession() != null) {
            restore()
            return
        }

        val startTime = ticker.now()

        val root = freezer.checkRoot()
        if (!root.granted) {
            store.log(Constants.LOG_ACTION_SESSION, null, false, "Root 检测失败：${root.detail}")
            notifier.notifyAlert(FocusAlert.ROOT_MISSING, root.detail)
            return
        }

        // 从这里开始，应用可能已经被部分冻结，因此必须记录目标列表
        requestedTargets = targets

        val result = freezer.freezeAll(targets)
        if (result.succeeded.isEmpty()) {
            notifier.notifyAlert(FocusAlert.FREEZE_ALL_FAILED)
            return
        }

        frozen = result.succeeded
        plannedEndTime = startTime + plannedMinutes * 60_000L
        sessionId = store.startSession(plannedMinutes, result.succeeded, startTime)
        isRunning = true

        notifier.updateOngoing(plannedEndTime - ticker.now(), frozen.size)

        if (result.failed.isNotEmpty()) {
            notifier.notifyAlert(
                FocusAlert.FREEZE_PARTIAL_FAILED,
                result.failed.joinToString("、")
            )
        }

        tickLoop()
    }

    // ---------------- 恢复 ----------------

    /**
     * 按数据库中的未完成专注恢复。
     * 无论成功与否都会返回（由调用方关闭前台服务）；
     * 若 Root 不可用导致无法恢复，专注保持 RUNNING 并给出提示，不静默丢弃。
     */
    suspend fun restore() {
        val sessions = store.getRunningSessions()
        if (sessions.isEmpty()) return

        // 只保留最新一条继续倒计时，其余历史残留按「已过期」处理
        val active = sessions.last()
        sessions.dropLast(1).forEach { stale ->
            runCatching {
                freezer.unfreezeAll(stale.frozenPackages)
                store.finishSession(stale.id, Constants.STATUS_COMPLETED, stale.plannedEndTime)
            }
        }

        sessionId = active.id
        frozen = active.frozenPackages
        plannedEndTime = active.plannedEndTime
        requestedTargets = active.frozenPackages

        // 情况一：倒计时已过期 -> 直接解冻并标记完成
        if (active.plannedEndTime <= ticker.now()) {
            store.log(
                Constants.LOG_ACTION_BOOT_RECOVER,
                null,
                true,
                "专注记录 #${active.id} 已过期，直接解冻并标记为已完成"
            )
            withContext(NonCancellable) {
                releaseAndFinish(Constants.STATUS_COMPLETED, active.plannedEndTime)
            }
            return
        }

        // 情况二：Root 不可用 -> 明确提示，专注保持 RUNNING 等用户处理
        val root = freezer.checkRoot()
        if (!root.granted) {
            store.log(
                Constants.LOG_ACTION_BOOT_RECOVER,
                null,
                false,
                "恢复失败，Root 不可用：${root.detail}"
            )
            notifier.notifyAlert(FocusAlert.ROOT_MISSING, root.detail)
            isRunning = false
            return
        }

        // 情况三：重新冻结并继续倒计时
        val result = freezer.freezeAll(active.frozenPackages)
        if (result.succeeded.isEmpty()) {
            store.log(
                Constants.LOG_ACTION_BOOT_RECOVER,
                null,
                false,
                "重新冻结全部应用失败，专注记录 #${active.id} 保持未完成"
            )
            notifier.notifyAlert(FocusAlert.FREEZE_ALL_FAILED)
            isRunning = false
            return
        }

        frozen = result.succeeded
        isRunning = true
        val remaining = plannedEndTime - ticker.now()
        store.log(
            Constants.LOG_ACTION_BOOT_RECOVER,
            null,
            true,
            "已恢复专注记录 #${active.id}，重新冻结 ${result.succeeded.size} 个应用，" +
                "剩余 ${TimeUtils.formatCountdown(remaining)}"
        )
        notifier.notifyRecovered(remaining)
        notifier.updateOngoing(remaining, frozen.size)

        tickLoop()
    }

    // ---------------- 停止 ----------------

    /**
     * 结束当前专注：解冻 -> 落库 -> 通知。
     * 用户提前停止传 [Constants.STATUS_STOPPED]；定时睡眠到点传 [Constants.STATUS_COMPLETED]。
     */
    suspend fun stop(status: String) {
        withContext(NonCancellable) {
            releaseAndFinish(status, ticker.now())
        }
    }

    // ---------------- 倒计时循环 ----------------

    private suspend fun tickLoop() {
        var lastReenforceAt = ticker.now()
        while (true) {
            val now = ticker.now()
            val remaining = plannedEndTime - now

            if (remaining <= 0L) {
                withContext(NonCancellable) {
                    releaseAndFinish(Constants.STATUS_COMPLETED, plannedEndTime)
                }
                return
            }

            notifier.updateOngoing(remaining, frozen.size)

            // 每 30 秒复查：部分 ROM 会把 disabled 状态改回来
            if (now - lastReenforceAt >= Constants.REENFORCE_INTERVAL_MS) {
                lastReenforceAt = now
                freezer.reEnforce(frozen)
            }

            ticker.delay(Constants.NOTIFY_TICK_MS)
        }
    }

    // ---------------- 收尾（幂等） ----------------

    private suspend fun releaseAndFinish(status: String, endTime: Long) {
        val id = if (sessionId > 0L) sessionId else store.getRunningSession()?.id ?: 0L

        val targets = frozen.ifEmpty {
            requestedTargets.ifEmpty {
                if (id > 0L) store.getSession(id)?.frozenPackages.orEmpty() else emptyList()
            }
        }

        if (targets.isNotEmpty()) {
            // 无条件对全部目标包执行 unsuspend。unsuspend 对未挂起的包是无副作用的空操作，
            // 比「先查状态再决定解冻谁」更安全：一旦状态查询在某个 ROM 上误报，
            // 被冻结的应用会永久挂起且没人能解冻，这个代价远大于多做一次空操作。
            freezer.unfreezeAll(targets)
        }

        val finished = if (id > 0L) store.finishSession(id, status, endTime) else null

        isRunning = false
        sessionId = 0L
        frozen = emptyList()
        plannedEndTime = 0L
        requestedTargets = emptyList()

        notifier.cancelOngoing()
        // 只有真的结束了一条专注才发结果通知，重复调用保持静默
        if (finished != null) {
            if (status == Constants.STATUS_COMPLETED) {
                notifier.notifyCompleted(finished.actualMinutes)
            } else {
                notifier.notifyStopped(finished.actualMinutes)
            }
        }
    }
}