package com.chen.sleepfocus.data.repo

import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.data.db.SessionDao
import com.chen.sleepfocus.data.db.SessionEntity
import com.chen.sleepfocus.data.log.LogFile
import com.chen.sleepfocus.data.model.DailyFocus
import com.chen.sleepfocus.data.model.MonthFocus
import com.chen.sleepfocus.data.model.StatsSummary
import com.chen.sleepfocus.data.prefs.SettingsStore
import com.chen.sleepfocus.focus.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 专注 / 统计 / 日志的唯一入口。冻结解冻这类设备操作由 PackageController 负责，
 * 但日志统一写到这里，保证日志文件能反映全部动作。
 */
class FocusRepository(
    private val sessionDao: SessionDao,
    private val settings: SettingsStore,
    private val logFile: LogFile
) : SessionStore {

    companion object {
        private const val DAY_TICK_MS = 60_000L

        /** 备份文件格式版本 */
        private const val BACKUP_VERSION = 1

        /** 备份文件标识，导入时用于校验 */
        private const val BACKUP_TAG = "SleepFocus"
    }

    /**
     * 每分钟触发一次，用于跨零点后刷新「今日 / 本周」区间，避免统计停留在前一天。
     */
    private val dayTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DAY_TICK_MS)
        }
    }

    // ---------------- 专注 ----------------

    override suspend fun startSession(
        plannedMinutes: Int,
        packages: List<String>,
        now: Long
    ): Long {
        val session = SessionEntity(
            startTime = now,
            plannedEndTime = now + plannedMinutes * 60_000L,
            plannedMinutes = plannedMinutes,
            status = Constants.STATUS_RUNNING,
            frozenPackages = packages
        )
        val id = sessionDao.insert(session)
        log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = true,
            message = "专注记录 #$id 开始，计划 $plannedMinutes 分钟，冻结 ${packages.size} 个应用"
        )
        return id
    }

    /** 结束专注，按实际时间计算分钟数并写回数据库 */
    override suspend fun finishSession(
        id: Long,
        status: String,
        endTime: Long
    ): SessionEntity? {
        val session = sessionDao.getById(id) ?: return null
        if (!session.isRunning) return session

        val actual = TimeUtils.actualMinutes(session.startTime, endTime)
        sessionDao.finish(id = id, status = status, endTime = endTime, actualMinutes = actual)
        log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = true,
            message = "专注记录 #$id 结束（$status），实际 $actual 分钟"
        )
        return session.copy(status = status, endTime = endTime, actualMinutes = actual)
    }

    override suspend fun getSession(id: Long): SessionEntity? = sessionDao.getById(id)

    override suspend fun getRunningSessions(): List<SessionEntity> = sessionDao.getRunningSessions()

    override suspend fun getRunningSession(): SessionEntity? = sessionDao.getRunning()

    fun observeRunningSession(): Flow<SessionEntity?> = sessionDao.observeRunning()

    /** 历史页用：全部专注 */
    fun observeAllSessions(): Flow<List<SessionEntity>> = sessionDao.observeAllSessions()

    /**
     * 按月份分组汇总全部专注记录，按月倒序。
     *
     * 分钟数遵循 [includeStopped]（提前停止是否计入累计），
     * 次数只统计已完成的专注（中断不计入）。
     * [currentMonthStart] 用于保证当前月始终有一行，即使当月还没有记录。
     */
    fun groupByMonth(
        sessions: List<SessionEntity>,
        includeStopped: Boolean,
        currentMonthStart: Long
    ): List<MonthFocus> {
        val minutes = HashMap<Long, Int>()
        val counts = HashMap<Long, Int>()
        sessions.forEach { session ->
            val key = TimeUtils.monthStartOf(session.startTime)
            if (isCounted(session, includeStopped)) {
                minutes[key] = (minutes[key] ?: 0) + session.actualMinutes
            }
            if (session.status == Constants.STATUS_COMPLETED) {
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return (minutes.keys + counts.keys + currentMonthStart)
            .distinct()
            .sortedDescending()
            .map { monthStart ->
                MonthFocus(
                    monthStart = monthStart,
                    minutes = minutes[monthStart] ?: 0,
                    count = counts[monthStart] ?: 0
                )
            }
    }

    /** 清空统计数据：保留进行中的专注，避免倒计时与冻结状态失去依据 */
    suspend fun clearStats() {
        sessionDao.clearFinished()
        log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = true,
            message = "已清空统计数据（进行中的专注保留）"
        )
    }

    /** 删除单条专注记录 */
    suspend fun deleteSession(id: Long) {
        sessionDao.deleteById(id)
        log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = true,
            message = "已删除专注记录 #$id"
        )
    }

    // ---------------- 数据导入导出 ----------------

    /** 导出全部专注记录为 JSON 文本（不包含设置项） */
    suspend fun exportSessionsJson(): String = withContext(Dispatchers.IO) {
        val sessions = sessionDao.getAllSessions()
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject().apply {
                    put("startTime", session.startTime)
                    put("plannedEndTime", session.plannedEndTime)
                    put("endTime", session.endTime ?: JSONObject.NULL)
                    put("plannedMinutes", session.plannedMinutes)
                    put("actualMinutes", session.actualMinutes)
                    put("status", session.status)
                    put("frozenPackages", JSONArray(session.frozenPackages))
                }
            )
        }
        JSONObject().apply {
            put("app", BACKUP_TAG)
            put("version", BACKUP_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("sessions", array)
        }.toString(2)
    }

    /**
     * 导入专注记录（合并追加，不删除已有数据），返回实际导入条数。
     *
     * 会跳过 RUNNING 记录：这些记录没有前台服务托管，导入后只会变成无法结束的「幻影专注」。
     * 内容不是合法的备份 JSON 时抛出异常，由调用方提示用户。
     */
    suspend fun importSessionsJson(json: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        val array = root.optJSONArray("sessions") ?: JSONArray()

        val parsed = ArrayList<SessionEntity>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val startTime = obj.optLong("startTime", 0L)
            if (startTime <= 0L) continue
            val status = obj.optString("status", Constants.STATUS_COMPLETED)
            if (status == Constants.STATUS_RUNNING) continue

            val plannedMinutes = obj.optInt("plannedMinutes", 0).coerceAtLeast(0)
            val frozenArray = obj.optJSONArray("frozenPackages")
            val packages = if (frozenArray == null) {
                emptyList()
            } else {
                (0 until frozenArray.length()).mapNotNull { index ->
                    frozenArray.optString(index).takeIf { it.isNotEmpty() }
                }
            }

            parsed += SessionEntity(
                startTime = startTime,
                plannedEndTime = obj.optLong(
                    "plannedEndTime",
                    startTime + plannedMinutes * 60_000L
                ),
                endTime = if (obj.isNull("endTime")) null else obj.optLong("endTime"),
                plannedMinutes = plannedMinutes,
                actualMinutes = obj.optInt("actualMinutes", 0).coerceAtLeast(0),
                status = status,
                frozenPackages = packages
            )
        }

        if (parsed.isEmpty()) return@withContext 0
        sessionDao.insertAll(parsed)
        log(
            action = Constants.LOG_ACTION_SESSION,
            packageName = null,
            success = true,
            message = "导入专注记录 ${parsed.size} 条"
        )
        parsed.size
    }

    // ---------------- 统计 ----------------

    fun observeStats(): Flow<StatsSummary> = combine(
        dayTicker,
        settings.countStoppedInTotal,
        sessionDao.observeSessionsSince(0L)
    ) { _, includeStopped, sessions ->
        val todayStart = TimeUtils.startOfToday()
        val weekStart = TimeUtils.startOfWeek()

        var todayMinutes = 0
        var weekMinutes = 0
        var totalMinutes = 0
        var todayCompleted = 0
        var todayStopped = 0
        var totalCompleted = 0
        var totalStopped = 0

        sessions.forEach { session ->
            when (session.status) {
                Constants.STATUS_COMPLETED -> {
                    totalCompleted++
                    if (session.startTime >= todayStart) todayCompleted++
                }

                Constants.STATUS_STOPPED -> {
                    totalStopped++
                    if (session.startTime >= todayStart) todayStopped++
                }
            }

            if (!isCounted(session, includeStopped)) return@forEach

            totalMinutes += session.actualMinutes
            if (session.startTime >= weekStart) weekMinutes += session.actualMinutes
            if (session.startTime >= todayStart) todayMinutes += session.actualMinutes
        }

        StatsSummary(
            todayMinutes = todayMinutes,
            weekMinutes = weekMinutes,
            totalMinutes = totalMinutes,
            todayCompleted = todayCompleted,
            todayStopped = todayStopped,
            totalCompleted = totalCompleted,
            totalStopped = totalStopped
        )
    }

    /** 最近 [days] 天每日专注分钟数（含今天），按日期升序 */
    fun observeDailyFocus(days: Int = 7): Flow<List<DailyFocus>> = combine(
        dayTicker,
        settings.countStoppedInTotal,
        sessionDao.observeSessionsSince(0L)
    ) { _, includeStopped, sessions ->
        val dayStarts = TimeUtils.lastDayStarts(days)
        val buckets = HashMap<Long, Int>()
        sessions.forEach { session ->
            if (!isCounted(session, includeStopped)) return@forEach
            val key = TimeUtils.dayStartOf(session.startTime)
            buckets[key] = (buckets[key] ?: 0) + session.actualMinutes
        }
        dayStarts.map { DailyFocus(dayStart = it, minutes = buckets[it] ?: 0) }
    }

    private fun isCounted(session: SessionEntity, includeStopped: Boolean): Boolean =
        session.status == Constants.STATUS_COMPLETED ||
            (includeStopped && session.status == Constants.STATUS_STOPPED)

    // ---------------- 日志 ----------------

    override suspend fun log(
        action: String,
        packageName: String?,
        success: Boolean,
        message: String?
    ) {
        logFile.append(
            action = action,
            packageName = packageName,
            success = success,
            message = message
        )
    }
}