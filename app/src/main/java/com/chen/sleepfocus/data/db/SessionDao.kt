package com.chen.sleepfocus.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    /** 数据导入：批量插入，自增主键由 Room 重新分配，避免与已有记录冲突 */
    @Insert
    suspend fun insertAll(sessions: List<SessionEntity>): List<Long>

    @Query("SELECT * FROM focus_session WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SessionEntity?

    /** 当前进行中的专注（理论上最多一条） */
    @Query("SELECT * FROM focus_session WHERE status = 'RUNNING' ORDER BY startTime DESC LIMIT 1")
    suspend fun getRunning(): SessionEntity?

    @Query("SELECT * FROM focus_session WHERE status = 'RUNNING' ORDER BY startTime DESC LIMIT 1")
    fun observeRunning(): Flow<SessionEntity?>

    /** 重启恢复用：所有未完成专注 */
    @Query("SELECT * FROM focus_session WHERE status = 'RUNNING' ORDER BY startTime ASC")
    suspend fun getRunningSessions(): List<SessionEntity>

    @Query(
        "UPDATE focus_session SET status = :status, endTime = :endTime, actualMinutes = :actualMinutes " +
            "WHERE id = :id"
    )
    suspend fun finish(id: Long, status: String, endTime: Long, actualMinutes: Int)

    @Query("UPDATE focus_session SET frozenPackages = :packages WHERE id = :id")
    suspend fun updateFrozenPackages(id: Long, packages: List<String>)

    // ---------------- 统计 ----------------

    /**
     * 区间内累计专注分钟数。
     * RUNNING 永不计入；STOPPED 是否计入由 [includeStopped] 决定。
     */
    @Query(
        "SELECT COALESCE(SUM(actualMinutes), 0) FROM focus_session " +
            "WHERE startTime >= :fromTime " +
            "AND (status = 'COMPLETED' OR (:includeStopped = 1 AND status = 'STOPPED'))"
    )
    fun observeMinutesSince(fromTime: Long, includeStopped: Boolean): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM focus_session WHERE status = 'COMPLETED' AND startTime >= :fromTime"
    )
    fun observeCompletedCount(fromTime: Long): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM focus_session WHERE status = 'STOPPED' AND startTime >= :fromTime"
    )
    fun observeStoppedCount(fromTime: Long): Flow<Int>

    /** 最近数天柱状图用：区间内全部专注 */
    @Query("SELECT * FROM focus_session WHERE startTime >= :fromTime ORDER BY startTime DESC")
    fun observeSessionsSince(fromTime: Long): Flow<List<SessionEntity>>

    /** 历史页用：全部专注，按开始时间倒序 */
    @Query("SELECT * FROM focus_session ORDER BY startTime DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    /** 数据导出用：全部专注，按开始时间升序 */
    @Query("SELECT * FROM focus_session ORDER BY startTime ASC")
    suspend fun getAllSessions(): List<SessionEntity>

    /**
     * 清空统计：只删已完成 / 已中断的记录，保留进行中的专注。
     * 进行中的记录被删会让倒计时失去依据，而前台服务仍在冻结应用。
     */
    @Query("DELETE FROM focus_session WHERE status != 'RUNNING'")
    suspend fun clearFinished()

    /** 删除单条专注记录 */
    @Query("DELETE FROM focus_session WHERE id = :id")
    suspend fun deleteById(id: Long)
}