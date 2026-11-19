package com.chen.sleepfocus.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.data.model.InterruptMode
import com.chen.sleepfocus.data.model.RootStatus
import com.chen.sleepfocus.data.model.SleepPlan
import com.chen.sleepfocus.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sleepfocus_settings")

/**
 * 用户偏好：默认时长、是否计入中断时长、已选应用、Root 状态快照、睡眠计划。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val DEFAULT_MINUTES = intPreferencesKey("default_minutes")
        val COUNT_STOPPED = booleanPreferencesKey("count_stopped_in_total")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val ROOT_STATUS = stringPreferencesKey("root_status")
        val PLANS = stringPreferencesKey("sleep_plans")

        // 旧版单条定时睡眠配置，仅用于升级时迁移
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_START = intPreferencesKey("schedule_start_minute")
        val SCHEDULE_END = intPreferencesKey("schedule_end_minute")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val INTERRUPT_MODE = stringPreferencesKey("interrupt_mode")
        val CUSTOM_MINUTES = stringPreferencesKey("custom_minutes")
        val LOG_ENABLED = booleanPreferencesKey("log_enabled")
    }

    // ---------------- 执行日志 ----------------

    /** 是否记录执行日志，默认关闭 */
    val logEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.LOG_ENABLED] ?: false }

    suspend fun setLogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOG_ENABLED] = enabled }
    }

    // ---------------- 中断专注 ----------------

    val interruptMode: Flow<InterruptMode> = context.dataStore.data
        .map { InterruptMode.fromName(it[Keys.INTERRUPT_MODE]) }

    suspend fun setInterruptMode(mode: InterruptMode) {
        context.dataStore.edit { it[Keys.INTERRUPT_MODE] = mode.name }
    }

    // ---------------- 界面配色 ----------------

    val themeMode: Flow<ThemeMode> = context.dataStore.data
        .map { ThemeMode.fromName(it[Keys.THEME_MODE]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    // ---------------- 默认倒计时时长 ----------------

    val defaultMinutes: Flow<Int> = context.dataStore.data
        .map { it[Keys.DEFAULT_MINUTES] ?: Constants.DEFAULT_MINUTES }

    suspend fun setDefaultMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.DEFAULT_MINUTES] = minutes }
    }

    // ---------------- 用户自定义时长 ----------------

    /**
     * 用户添加的自定义时长，按添加顺序，最多 [Constants.MAX_CUSTOM_MINUTES] 个。
     * 用逗号分隔的字符串保存，因为 Set 无序，而芯片的展示顺序需要稳定。
     */
    val customMinutes: Flow<List<Int>> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_MINUTES]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in Constants.MIN_MINUTES..Constants.MAX_MINUTES }
            ?.distinct()
            ?.take(Constants.MAX_CUSTOM_MINUTES)
            .orEmpty()
    }

    suspend fun setCustomMinutes(minutes: List<Int>) {
        context.dataStore.edit { it[Keys.CUSTOM_MINUTES] = minutes.joinToString(",") }
    }

    suspend fun customMinutesOnce(): List<Int> = customMinutes.first()

    // ---------------- 提前停止是否计入累计 ----------------

    val countStoppedInTotal: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.COUNT_STOPPED] ?: true }

    suspend fun setCountStoppedInTotal(value: Boolean) {
        context.dataStore.edit { it[Keys.COUNT_STOPPED] = value }
    }

    // ---------------- 已选应用 ----------------

    val selectedPackages: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.SELECTED_PACKAGES] ?: emptySet() }

    suspend fun setSelectedPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.SELECTED_PACKAGES] = packages }
    }

    suspend fun selectedPackagesOnce(): Set<String> = selectedPackages.first()

    // ---------------- Root 状态快照 ----------------

    val rootStatus: Flow<RootStatus> = context.dataStore.data
        .map { RootStatus.fromName(it[Keys.ROOT_STATUS]) }

    suspend fun setRootStatus(status: RootStatus) {
        context.dataStore.edit { it[Keys.ROOT_STATUS] = status.name }
    }

    suspend fun rootStatusOnce(): RootStatus = rootStatus.first()

    // ---------------- 睡眠计划 ----------------

    /** 全部睡眠计划，按创建顺序保存 */
    val plans: Flow<List<SleepPlan>> = context.dataStore.data.map { prefs ->
        PlanCodec.decode(prefs[Keys.PLANS])
    }

    suspend fun plansOnce(): List<SleepPlan> = plans.first()

    suspend fun setPlans(plans: List<SleepPlan>) {
        context.dataStore.edit { it[Keys.PLANS] = PlanCodec.encode(plans) }
    }

    /** 追加一条计划并分配自增 id，返回新 id */
    suspend fun createPlan(plan: SleepPlan): Long {
        val current = plansOnce()
        val id = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        setPlans(current + plan.copy(id = id))
        return id
    }

    /**
     * 把旧版单条定时睡眠迁移为一条计划（只执行一次）。
     * 旧配置未启用时不生成计划，仅清掉旧键，避免无意义的空计划。
     */
    suspend fun migrateLegacySchedule() {
        val prefs = context.dataStore.data.first()
        // 已迁移过（包括迁移出空列表）就跳过，避免重复写入
        if (prefs[Keys.PLANS] != null) return

        val legacyEnabled = prefs[Keys.SCHEDULE_ENABLED] ?: false
        val migrated = if (legacyEnabled) {
            listOf(
                SleepPlan(
                    id = 1L,
                    enabled = true,
                    startMinuteOfDay = prefs[Keys.SCHEDULE_START] ?: 23 * 60,
                    endMinuteOfDay = prefs[Keys.SCHEDULE_END] ?: 7 * 60,
                    packages = prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
                )
            )
        } else {
            emptyList()
        }

        context.dataStore.edit {
            it[Keys.PLANS] = PlanCodec.encode(migrated)
            it.remove(Keys.SCHEDULE_ENABLED)
            it.remove(Keys.SCHEDULE_START)
            it.remove(Keys.SCHEDULE_END)
        }
    }

    /** 计划用 JSON 字符串持久化：DataStore Preferences 不支持直接存放对象列表 */
    private object PlanCodec {

        fun encode(plans: List<SleepPlan>): String {
            val array = JSONArray()
            plans.forEach { plan ->
                array.put(
                    JSONObject().apply {
                        put("id", plan.id)
                        put("enabled", plan.enabled)
                        put("start", plan.startMinuteOfDay)
                        put("end", plan.endMinuteOfDay)
                        put("days", JSONArray(plan.activeDays.sorted()))
                        put("packages", JSONArray(plan.packages.toList()))
                    }
                )
            }
            return array.toString()
        }

        fun decode(raw: String?): List<SleepPlan> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val packageArray = obj.optJSONArray("packages") ?: JSONArray()
                    val packages = (0 until packageArray.length())
                        .mapNotNull { i ->
                            packageArray.optString(i).takeIf { it.isNotEmpty() }
                        }
                        .toSet()
                    // 旧数据没有 days 字段，缺省为每天
                    val daysArray = obj.optJSONArray("days")
                    val days = if (daysArray == null) {
                        SleepPlan.ALL_DAYS
                    } else {
                        (0 until daysArray.length())
                            .mapNotNull { i -> daysArray.optInt(i, 0).takeIf { it in 1..7 } }
                            .toSet()
                            .ifEmpty { SleepPlan.ALL_DAYS }
                    }
                    SleepPlan(
                        id = obj.optLong("id", 0L),
                        enabled = obj.optBoolean("enabled", true),
                        startMinuteOfDay = obj.optInt("start", 23 * 60).coerceIn(0, 24 * 60 - 1),
                        endMinuteOfDay = obj.optInt("end", 7 * 60).coerceIn(0, 24 * 60 - 1),
                        packages = packages,
                        daysOfWeek = days
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}