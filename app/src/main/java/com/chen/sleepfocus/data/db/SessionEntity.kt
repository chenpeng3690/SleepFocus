package com.chen.sleepfocus.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chen.sleepfocus.core.Constants

/**
 * 一次专注专注。
 *
 * 状态流转：RUNNING -> COMPLETED（倒计时自然结束） / STOPPED（用户提前停止）
 */
@Entity(
    tableName = "focus_session",
    indices = [Index("startTime"), Index("status")]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 开始时间（epoch millis） */
    val startTime: Long,

    /** 计划结束时间（epoch millis）= startTime + plannedMinutes * 60000 */
    val plannedEndTime: Long,

    /** 实际结束时间，RUNNING 时为 null */
    val endTime: Long? = null,

    /** 计划专注分钟数 */
    val plannedMinutes: Int,

    /** 实际专注分钟数，RUNNING 时为 0 */
    val actualMinutes: Int = 0,

    /** RUNNING / COMPLETED / STOPPED */
    val status: String = Constants.STATUS_RUNNING,

    /** 本次被冻结的包名列表 */
    val frozenPackages: List<String> = emptyList()
) {
    val isRunning: Boolean get() = status == Constants.STATUS_RUNNING
}