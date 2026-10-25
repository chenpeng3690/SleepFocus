package com.chen.sleepfocus.focus

import com.chen.sleepfocus.data.db.SessionEntity
import com.chen.sleepfocus.data.model.RootCheckResult
import com.chen.sleepfocus.root.BatchResult

/**
 * 冻结 / 解冻设备能力。由 [com.chen.sleepfocus.root.PackageController] 实现。
 *
 * 「冻结」= 把包置为系统的 suspended 状态（root 执行 `pm suspend`）：
 * 图标仍留在启动器原位、点开被系统拦截，而不是 `disable-user`（会把图标从桌面移除）。
 */
interface PackageFreezer {
    suspend fun checkRoot(): RootCheckResult
    suspend fun freezeAll(packages: List<String>): BatchResult
    suspend fun unfreezeAll(packages: List<String>): BatchResult

    suspend fun reEnforce(frozenPackages: List<String>): List<String>
}

/**
 * 专注与日志持久化。由 [com.chen.sleepfocus.data.repo.FocusRepository] 实现。
 */
interface SessionStore {
    suspend fun startSession(plannedMinutes: Int, packages: List<String>, now: Long): Long
    suspend fun finishSession(id: Long, status: String, endTime: Long): SessionEntity?
    suspend fun getRunningSession(): SessionEntity?
    suspend fun getRunningSessions(): List<SessionEntity>
    suspend fun getSession(id: Long): SessionEntity?
    suspend fun log(action: String, packageName: String?, success: Boolean, message: String? = null)
}

/**
 * 需要让用户看到的异常类型。控制器只产出语义，具体文案由 Android 层映射。
 */
enum class FocusAlert {
    NO_APPS_SELECTED,
    ROOT_MISSING,
    FREEZE_ALL_FAILED,
    FREEZE_PARTIAL_FAILED,
    SERVICE_ERROR
}

/**
 * 通知能力。由 [com.chen.sleepfocus.service.Notifications] 实现。
 */
interface FocusNotifier {
    fun updateOngoing(remainingMillis: Long, frozenCount: Int)
    fun cancelOngoing()
    fun notifyCompleted(actualMinutes: Int)
    fun notifyStopped(actualMinutes: Int)
    fun notifyRecovered(remainingMillis: Long)
    fun notifyAlert(alert: FocusAlert, detail: String? = null)
}

/**
 * 时间与等待的抽象。生产用 [SystemTicker]；
 * 测试注入基于协程虚拟时间的实现，25 分钟的倒计时可以在毫秒内跑完。
 */
interface Ticker {
    fun now(): Long
    suspend fun delay(millis: Long)
}

object SystemTicker : Ticker {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun delay(millis: Long) {
        kotlinx.coroutines.delay(millis)
    }
}