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
import com.chen.sleepfocus.data.model.ScheduleConfig
import com.chen.sleepfocus.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sleepfocus_settings")

/**
 * 用户偏好：默认时长、是否计入中断时长、已选应用、Root 状态快照、定时睡眠配置。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val DEFAULT_MINUTES = intPreferencesKey("default_minutes")
        val COUNT_STOPPED = booleanPreferencesKey("count_stopped_in_total")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val ROOT_STATUS = stringPreferencesKey("root_status")
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

    // ---------------- 定时睡眠 ----------------

    val schedule: Flow<ScheduleConfig> = context.dataStore.data.map { prefs ->
        ScheduleConfig(
            enabled = prefs[Keys.SCHEDULE_ENABLED] ?: false,
            startMinuteOfDay = prefs[Keys.SCHEDULE_START] ?: 23 * 60,
            endMinuteOfDay = prefs[Keys.SCHEDULE_END] ?: 7 * 60
        )
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SCHEDULE_ENABLED] = enabled }
    }

    suspend fun setScheduleStartMinute(minuteOfDay: Int) {
        context.dataStore.edit { it[Keys.SCHEDULE_START] = minuteOfDay }
    }

    suspend fun setScheduleEndMinute(minuteOfDay: Int) {
        context.dataStore.edit { it[Keys.SCHEDULE_END] = minuteOfDay }
    }

    suspend fun scheduleOnce(): ScheduleConfig = schedule.first()
}