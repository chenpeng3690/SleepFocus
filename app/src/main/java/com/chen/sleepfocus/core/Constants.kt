package com.chen.sleepfocus.core

/**
 * 全局常量：通知渠道 / 服务与闹钟的 Action / 各种 ID。
 */
object Constants {

    // ---------- 通知渠道 ----------
    const val CHANNEL_ONGOING = "channel_focus_ongoing"
    const val CHANNEL_RESULT = "channel_focus_result"
    const val CHANNEL_ALERT = "channel_focus_alert"

    // ---------- 通知 ID ----------
    const val NOTIFY_ID_ONGOING = 1001
    const val NOTIFY_ID_RESULT = 1002
    const val NOTIFY_ID_ALERT = 1003

    // ---------- FocusService Action ----------
    const val ACTION_START = "com.chen.sleepfocus.action.START"
    const val ACTION_STOP = "com.chen.sleepfocus.action.STOP"
    const val ACTION_RESTORE = "com.chen.sleepfocus.action.RESTORE"

    /** 定时睡眠到点解冻：记为 COMPLETED 而非 STOPPED */
    const val ACTION_FINISH = "com.chen.sleepfocus.action.FINISH"

    const val EXTRA_MINUTES = "extra_minutes"
    const val EXTRA_PACKAGES = "extra_packages"

    // ---------- 定时睡眠 Action ----------
    const val ACTION_SCHEDULE_START = "com.chen.sleepfocus.action.SCHEDULE_START"
    const val ACTION_SCHEDULE_STOP = "com.chen.sleepfocus.action.SCHEDULE_STOP"

    /** 解冻前提前唤醒：只申请 WakeLock 让 CPU 保持清醒，不涉及业务状态 */
    const val ACTION_PRE_WAKE = "com.chen.sleepfocus.action.PRE_WAKE"

    const val REQUEST_CODE_SCHEDULE_START = 2001
    const val REQUEST_CODE_SCHEDULE_STOP = 2002

    /** 定时睡眠配置的提前唤醒闹钟（随 START / STOP 一起注册与取消） */
    const val REQUEST_CODE_PRE_WAKE_SCHEDULE = 2003

    /** 正在进行中的专注（手动或定时）按实际结束时间注册的提前唤醒闹钟 */
    const val REQUEST_CODE_PRE_WAKE_SESSION = 2004

    /**
     * 定时睡眠触发后，等待服务真正完成冻结（会话落库为 RUNNING）的最长时间。
     * 冻结在服务协程里异步执行，广播唤醒锁必须撑到那一刻，但 goAsync 本身有
     * 系统超时限制（前台广播约 10 秒），所以这里取 8 秒留出余量。
     */
    const val SCHEDULE_AWAIT_RUNNING_TIMEOUT_MS = 8_000L

    /** 上面这段等待的轮询间隔 */
    const val SCHEDULE_AWAIT_RUNNING_POLL_MS = 200L

    // ---------- 解冻前提前唤醒 ----------
    /** 解冻前多久开始唤醒设备；深度睡眠下协程定时器会停止走动，需提前把设备唤醒 */
    const val PRE_WAKE_LEAD_MS = 3 * 60_000L

    /** 提前唤醒时持有的 WakeLock 超时，需覆盖到解冻时刻之后，避免设备再次进入深度睡眠 */
    const val PRE_WAKE_WAKELOCK_TIMEOUT_MS = PRE_WAKE_LEAD_MS + 2 * 60_000L

    // ---------- 时长 ----------
    /** 固定预设时长，只有一个；其余可选时长由用户自定义 */
    val PRESET_MINUTES = listOf(15)
    const val DEFAULT_MINUTES = 15
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 720

    /** 最多保留的自定义时长个数 */
    const val MAX_CUSTOM_MINUTES = 4

    // ---------- 周期 ----------
    /** 倒计时期间复查被系统重新启用的应用的间隔 */
    const val REENFORCE_INTERVAL_MS = 30_000L

    /** 通知栏剩余时间刷新间隔 */
    const val NOTIFY_TICK_MS = 1_000L

    // ---------- 中断专注 ----------
    /** 「延迟」模式下，需等待多少秒后确认按钮才可点击 */
    const val INTERRUPT_DELAY_SECONDS = 60

    // ---------- Room ----------
    const val DB_NAME = "sleepfocus.db"

    // ---------- 专注状态 ----------
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_STOPPED = "STOPPED"

    // ---------- 日志动作 ----------
    const val LOG_ACTION_FREEZE = "FREEZE"
    const val LOG_ACTION_UNFREEZE = "UNFREEZE"
    const val LOG_ACTION_ROOT_CHECK = "ROOT_CHECK"
    const val LOG_ACTION_BOOT_RECOVER = "BOOT_RECOVER"
    const val LOG_ACTION_REENFORCE = "REENFORCE"
    const val LOG_ACTION_SESSION = "SESSION"
}