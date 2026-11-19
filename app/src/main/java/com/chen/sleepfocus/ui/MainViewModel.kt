package com.chen.sleepfocus.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chen.sleepfocus.R
import com.chen.sleepfocus.SleepFocusApp
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.data.db.SessionEntity
import com.chen.sleepfocus.data.model.AppInfo
import com.chen.sleepfocus.data.model.InterruptMode
import com.chen.sleepfocus.data.model.RootStatus
import com.chen.sleepfocus.data.model.SleepPlan
import com.chen.sleepfocus.data.model.StatsSummary
import com.chen.sleepfocus.data.model.ThemeMode
import com.chen.sleepfocus.data.repo.FocusRepository
import com.chen.sleepfocus.service.FocusService
import com.chen.sleepfocus.ui.home.HomeUiState
import com.chen.sleepfocus.ui.settings.SettingsUiState
import com.chen.sleepfocus.ui.stats.StatsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 应用选择页状态。
 */
data class AppSelectUiState(
    val apps: List<AppInfo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false
)

/** 主页组合用的中间载体（combine 的重载最多 5 个 flow，多出来的靠嵌套折叠） */
private data class HomeInputs(
    val running: SessionEntity?,
    val stats: StatsSummary,
    val plannedMinutes: Int,
    val selected: Set<String>,
    val customMinutes: List<Int>
)

private data class EnvInputs(
    val rootStatus: RootStatus,
    val rootDetail: String?,
    val message: String?,
    val remainingMillis: Long,
    val interruptMode: InterruptMode
)

private data class SettingsConfigInputs(
    val themeMode: ThemeMode,
    val interruptMode: InterruptMode,
    val logEnabled: Boolean
)

/**
 * 全局 ViewModel：把仓库的 Flow 组装成各页面的 UI 状态，并承接用户操作。
 *
 * 倒计时不在 App 内推进，而是以数据库里的 RUNNING 专注为准（前台服务负责真实计时），
 * 这里只做每秒刷新剩余毫秒数，保证杀掉 App 再打开也能接上。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication<Application>()

    private val app: SleepFocusApp = SleepFocusApp.get()
    private val repository: FocusRepository = app.focusRepository
    private val settings = app.settings
    private val appRepository = app.appRepository
    private val packageController = app.packageController
    private val alarmScheduler = app.alarmScheduler

    // ---------------- 可变状态 ----------------

    private val plannedMinutes = MutableStateFlow(Constants.DEFAULT_MINUTES)

    /** 用户通过「自定义」添加的时长（按添加顺序，最多 [Constants.MAX_CUSTOM_MINUTES] 个），用于生成可选时长芯片 */
    private val customMinutes = MutableStateFlow<List<Int>>(emptyList())

    private val selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val remainingMillis = MutableStateFlow(0L)
    private val message = MutableStateFlow<String?>(null)

    /** 全部睡眠计划的最新快照，供页面渲染、闹钟排程与手动专注顺延判断使用 */
    private val plans = MutableStateFlow<List<SleepPlan>>(emptyList())

    /** 应用选择页当前要编辑的计划 id；为空表示在给主页选择全局应用 */
    private val planAppTargetId = MutableStateFlow<Long?>(null)

    private val rootStatus = MutableStateFlow(RootStatus.UNKNOWN)
    private val rootDetail = MutableStateFlow<String?>(null)
    private val isCheckingRoot = MutableStateFlow(false)

    private val installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val isLoadingApps = MutableStateFlow(false)

    /** 数据库里当前未完成的专注，倒计时倒计时以它为准 */
    private val runningSession: StateFlow<SessionEntity?> = repository.observeRunningSession()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------------- 对外 UI 状态 ----------------

    private val homeInputs = combine(
        runningSession,
        repository.observeStats(),
        plannedMinutes,
        selectedPackages,
        customMinutes
    ) { running, stats, planned, selected, custom ->
        HomeInputs(running, stats, planned, selected, custom)
    }

    private val envInputs = combine(
        rootStatus,
        rootDetail,
        message,
        remainingMillis,
        settings.interruptMode
    ) { status, detail, msg, remaining, interrupt ->
        EnvInputs(status, detail, msg, remaining, interrupt)
    }

    val homeState: StateFlow<HomeUiState> = combine(homeInputs, envInputs) { home, env ->
        HomeUiState(
            isRunning = home.running != null,
            remainingMillis = env.remainingMillis,
            plannedMinutes = home.plannedMinutes,
            customMinutes = home.customMinutes,
            canAddCustomMinutes = home.customMinutes.size < Constants.MAX_CUSTOM_MINUTES,
            selectedCount = home.selected.size,
            todayMinutes = home.stats.todayMinutes,
            todayCompleted = home.stats.todayCompleted,
            rootStatus = env.rootStatus,
            rootDetail = env.rootDetail,
            interruptMode = env.interruptMode,
            message = env.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val statsState: StateFlow<StatsUiState> = combine(
        repository.observeStats(),
        repository.observeDailyFocus(7),
        repository.observeDailyFocus(30),
        repository.observeAllSessions(),
        settings.countStoppedInTotal
    ) { stats, week, month, sessions, countStopped ->
        StatsUiState(
            todayMinutes = stats.todayMinutes,
            weekMinutes = stats.weekMinutes,
            totalMinutes = stats.totalMinutes,
            completedCount = stats.totalCompleted,
            stoppedCount = stats.totalStopped,
            daily = week,
            dailyMonth = month,
            months = repository.groupByMonth(
                sessions = sessions,
                includeStopped = countStopped,
                currentMonthStart = TimeUtils.monthStartOf(System.currentTimeMillis())
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** 历史页：数据库中的全部专注 */
    val historySessions: StateFlow<List<SessionEntity>> = repository.observeAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 月份详情页用：总专注时间是否计入中断，遵循全局开关 */
    val countStoppedInTotal: StateFlow<Boolean> = settings.countStoppedInTotal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val appSelectState: StateFlow<AppSelectUiState> = combine(
        installedApps,
        selectedPackages,
        isLoadingApps
    ) { apps, selected, loading ->
        AppSelectUiState(apps = apps, selected = selected, isLoading = loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSelectUiState())

    /** 界面配色模式，Activity 直接订阅以应用主题。
     *  初始值取冷启动的同步读取结果，保证首帧就是用户上次选的配色。 */
    private val themeModeState = MutableStateFlow(app.bootThemeMode)

    val themeMode: StateFlow<ThemeMode> = themeModeState.asStateFlow()

    /** 计划 Tab 与其他页面读取的计划快照（写入时同步更新，便于导航后立即取到新计划） */
    val plansState: StateFlow<List<SleepPlan>> = plans.asStateFlow()

    /** 计划编辑页进入应用选择时的状态，选中集来自目标计划实时快照 */
    val planAppSelectState: StateFlow<AppSelectUiState> = combine(
        installedApps,
        isLoadingApps,
        plans,
        planAppTargetId
    ) { apps, loading, allPlans, targetId ->
        AppSelectUiState(
            apps = apps,
            selected = allPlans.firstOrNull { it.id == targetId }?.packages ?: emptySet(),
            isLoading = loading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSelectUiState())

    private val settingsConfigInputs = combine(
        settings.themeMode,
        settings.interruptMode,
        settings.logEnabled
    ) { theme, interrupt, log ->
        SettingsConfigInputs(theme, interrupt, log)
    }

    val settingsState: StateFlow<SettingsUiState> = combine(
        settingsConfigInputs,
        message,
        runningSession
    ) { config, msg, running ->
        SettingsUiState(
            themeMode = config.themeMode,
            interruptMode = config.interruptMode,
            isRunning = running != null,
            logEnabled = config.logEnabled,
            logFilePath = app.logFile.path,
            message = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(logFilePath = app.logFile.path))

    init {
        viewModelScope.launch {
            val initialMinutes = settings.defaultMinutes.first()
            val savedCustom = settings.customMinutesOnce()
            // 上次用的若是自定义时长，确保它仍在可选列表里（否则会「没有任何芯片被选中」）
            customMinutes.value =
                if (initialMinutes != Constants.DEFAULT_MINUTES && initialMinutes !in savedCustom) {
                    (savedCustom + initialMinutes).takeLast(Constants.MAX_CUSTOM_MINUTES)
                } else {
                    savedCustom
                }
            plannedMinutes.value = initialMinutes
            selectedPackages.value = settings.selectedPackagesOnce()
            plans.value = settings.plansOnce()
            pruneSelectedPackages()
            rootStatus.value = settings.rootStatusOnce()

            // 重启后若存在未完成专注，交给前台服务恢复（服务仍在运行时该请求会被忽略）
            if (repository.getRunningSession() != null) {
                FocusService.restore(ctx)
            }

            alarmScheduler.reschedule()
        }

        // 启动时自动检测一次 Root
        checkRoot()

        // 之后配色以 DataStore 为准（用户在设置里切换时同步过来）
        viewModelScope.launch {
            settings.themeMode.collect { themeModeState.value = it }
        }

        // 计划以 DataStore 为准，用户修改或广播侧清理卸载应用后同步到内存快照
        viewModelScope.launch {
            settings.plans.collect { latest ->
                if (latest != plans.value) plans.value = latest
            }
        }

        viewModelScope.launch {
            while (isActive) {
                remainingMillis.value = runningSession.value
                    ?.let { (it.plannedEndTime - System.currentTimeMillis()).coerceAtLeast(0L) }
                    ?: 0L
                delay(Constants.NOTIFY_TICK_MS)
            }
        }
    }

    // ---------------- Root ----------------

    /**
     * 启动时自动检测一次 Root，结果写入 [rootStatus] / [rootDetail]，
     * 由主页的告警条负责呈现（失败原因也会随异常通知一起发出）。
     */
    private fun checkRoot() {
        if (isCheckingRoot.value) return
        viewModelScope.launch {
            isCheckingRoot.value = true
            val result = packageController.checkRoot()
            rootStatus.value = if (result.granted) RootStatus.GRANTED else RootStatus.DENIED
            rootDetail.value = result.detail
            settings.setRootStatus(rootStatus.value)
            isCheckingRoot.value = false
        }
    }

    // ---------------- 主页操作 ----------------

    fun onDurationSelected(minutes: Int) {
        plannedMinutes.value = minutes.coerceIn(Constants.MIN_MINUTES, Constants.MAX_MINUTES)
        viewModelScope.launch { settings.setDefaultMinutes(plannedMinutes.value) }
    }

    /**
     * 用户在「自定义」对话框里确认的时长：先在可选时长里登记（最多 4 个），再选中它。
     * 输入的值若等于预设的 15 分钟，则只做选中，不占用自定义名额。
     */
    fun onCustomMinutesSelected(minutes: Int) {
        val value = minutes.coerceIn(Constants.MIN_MINUTES, Constants.MAX_MINUTES)
        if (value != Constants.DEFAULT_MINUTES &&
            value !in customMinutes.value &&
            customMinutes.value.size < Constants.MAX_CUSTOM_MINUTES
        ) {
            val updated = customMinutes.value + value
            customMinutes.value = updated
            viewModelScope.launch { settings.setCustomMinutes(updated) }
        }
        onDurationSelected(value)
    }

    /** 长按自定义时长芯片删除它；若删掉的正是当前选中项，回落到默认的 15 分钟 */
    fun onCustomMinutesRemoved(minutes: Int) {
        val updated = customMinutes.value - minutes
        if (updated.size == customMinutes.value.size) return
        customMinutes.value = updated
        viewModelScope.launch { settings.setCustomMinutes(updated) }
        if (plannedMinutes.value == minutes) {
            onDurationSelected(Constants.DEFAULT_MINUTES)
        }
    }

    fun startFocus() {
        viewModelScope.launch {
            // 冻结前再核实一次，防止刚卸载的应用仍留在选中集里触发冻结失败
            pruneSelectedPackages()
            val packages = selectedPackages.value
            when {
                packages.isEmpty() -> {
                    message.value = ctx.getString(R.string.msg_no_app_selected)
                }

                runningSession.value != null -> {
                    message.value = ctx.getString(R.string.msg_already_running)
                }

                rootStatus.value == RootStatus.DENIED -> {
                    message.value = ctx.getString(R.string.msg_root_required)
                }

                else -> {
                    val started =
                        FocusService.start(ctx, effectivePlannedMinutes(), packages.toList())
                    if (!started) {
                        message.value = ctx.getString(R.string.msg_service_start_failed)
                    }
                }
            }
        }
    }

    /**
     * 手动专注的实际时长：若计划结束时刻落在任一已启用计划的时间段内，
     * 则顺延到最晚的那个结束时刻解冻（仍记为同一条专注记录）。
     */
    private fun effectivePlannedMinutes(): Int {
        val requested = plannedMinutes.value
        val now = System.currentTimeMillis()
        val plannedEnd = now + requested * 60_000L
        val windowEnd = plans.value
            .filter { it.enabled }
            .mapNotNull { it.windowEndContaining(plannedEnd) }
            .maxOrNull() ?: return requested
        if (windowEnd <= plannedEnd) return requested

        return ((windowEnd - now) / 60_000.0).roundToInt()
            .coerceAtLeast(Constants.MIN_MINUTES)
    }

    fun stopFocus() {
        if (!FocusService.requestStop(ctx)) {
            message.value = ctx.getString(R.string.msg_service_start_failed)
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    // ---------------- 应用选择 ----------------

    fun loadInstalledApps() {
        if (isLoadingApps.value) return
        viewModelScope.launch {
            // 每次进入选择页都顺手清理一次已卸载的选中项
            pruneSelectedPackages()
            if (installedApps.value.isNotEmpty()) return@launch
            isLoadingApps.value = true
            val apps = appRepository.loadInstalledApps(includeSystem = true)
            installedApps.value = apps
            isLoadingApps.value = false
            prunePlanPackages(apps)
        }
    }

    /** 剔除各计划里已被卸载应用的包名，避免冻结时对不存在的包执行命令 */
    private suspend fun prunePlanPackages(loadedApps: List<AppInfo>) {
        if (loadedApps.isEmpty()) return
        val installed = loadedApps.map { it.packageName }.toSet()
        val current = plans.value
        val pruned = current.map { plan ->
            if (plan.packages.all { it in installed }) {
                plan
            } else {
                plan.copy(packages = plan.packages.intersect(installed))
            }
        }
        if (pruned == current) return

        plans.value = pruned
        settings.setPlans(pruned)
    }

    fun toggleApp(packageName: String) {
        selectedPackages.update { current ->
            if (packageName in current) current - packageName else current + packageName
        }
    }

    /** [select] 为 true 时把 [packages] 加入选择，false 时移除 */
    fun changeVisibleSelection(packages: Set<String>, select: Boolean) {
        selectedPackages.update { current ->
            if (select) current + packages else current - packages
        }
    }

    fun saveSelectedApps() {
        viewModelScope.launch {
            pruneSelectedPackages()
            settings.setSelectedPackages(selectedPackages.value)
            message.value = ctx.getString(R.string.apps_saved, selectedPackages.value.size)
        }
    }

    /**
     * 剔除已被卸载应用的选中项。
     *
     * 用户卸载应用后本地仍留着包名，会导致「已选择」数量虚高，
     * 冻结时还会对这些不存在的包执行命令并提示失败，因此主动清理并回写。
     */
    private suspend fun pruneSelectedPackages() {
        val current = selectedPackages.value
        if (current.isEmpty()) return

        val installed = current
            .filter { it != appRepository.selfPackageName && appRepository.isInstalled(it) }
            .toSet()
        if (installed.size == current.size) return

        selectedPackages.value = installed
        settings.setSelectedPackages(installed)
    }

    // ---------------- 睡眠计划 ----------------

    /** 新建一条默认计划，创建完成后回调新 id 供页面直接进入编辑 */
    fun createPlan(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = settings.createPlan(SleepPlan())
            plans.value = settings.plansOnce()
            alarmScheduler.reschedule(plans.value)
            onCreated(id)
        }
    }

    fun setPlanEnabled(planId: Long, enabled: Boolean) {
        updatePlan(planId, reschedule = true) { it.copy(enabled = enabled) }
    }

    fun setPlanStart(planId: Long, minuteOfDay: Int) {
        updatePlan(planId, reschedule = true) { it.copy(startMinuteOfDay = minuteOfDay) }
    }

    fun setPlanEnd(planId: Long, minuteOfDay: Int) {
        updatePlan(planId, reschedule = true) { it.copy(endMinuteOfDay = minuteOfDay) }
    }

    fun deletePlan(planId: Long) {
        val updated = plans.value.filterNot { it.id == planId }
        plans.value = updated
        viewModelScope.launch {
            alarmScheduler.cancelPlan(planId)
            settings.setPlans(updated)
        }
    }

    /**
     * 修改某条计划并同步落库。
     * 只有时间 / 启停变化才需要重排闹钟（[reschedule]），换应用不影响已注册的闹钟。
     */
    private fun updatePlan(
        planId: Long,
        reschedule: Boolean = false,
        transform: (SleepPlan) -> SleepPlan
    ) {
        val before = plans.value
        val updated = before.map { plan -> if (plan.id == planId) transform(plan) else plan }
        if (updated == before) return

        plans.value = updated
        viewModelScope.launch {
            settings.setPlans(updated)
            if (reschedule) alarmScheduler.reschedule(updated)
        }
    }

    /** 进入某条计划的应用选择页 */
    fun setPlanAppTarget(planId: Long) {
        planAppTargetId.value = planId
    }

    fun togglePlanApp(packageName: String) {
        val planId = planAppTargetId.value ?: return
        updatePlan(planId) { plan ->
            plan.copy(
                packages = if (packageName in plan.packages) {
                    plan.packages - packageName
                } else {
                    plan.packages + packageName
                }
            )
        }
    }

    fun changePlanVisibleSelection(packages: Set<String>, select: Boolean) {
        val planId = planAppTargetId.value ?: return
        updatePlan(planId) { plan ->
            plan.copy(
                packages = if (select) plan.packages + packages else plan.packages - packages
            )
        }
    }

    // ---------------- 设置 ----------------

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setInterruptMode(mode: InterruptMode) {
        viewModelScope.launch { settings.setInterruptMode(mode) }
    }

    fun setLogEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setLogEnabled(enabled) }
    }

    fun clearStats() {
        viewModelScope.launch {
            repository.clearStats()
            message.value = ctx.getString(R.string.settings_cleared)
        }
    }

    /** 删除单条历史专注记录 */
    fun deleteSession(id: Long) {
        viewModelScope.launch { repository.deleteSession(id) }
    }

    // ---------------- 数据导入导出 ----------------

    /** 把全部专注记录导出为 JSON 写入 [uri]（由 SAF 创建） */
    fun exportData(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val json = repository.exportSessionsJson()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
            }
            message.value = ctx.getString(
                if (result.isSuccess) R.string.settings_export_success
                else R.string.settings_export_failed
            )
        }
    }

    /** 从 [uri] 读取 JSON 并合并追加到专注记录 */
    fun importData(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val json = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
                repository.importSessionsJson(json)
            }
            message.value = result.fold(
                onSuccess = { count ->
                    if (count > 0) ctx.getString(R.string.settings_import_success, count)
                    else ctx.getString(R.string.settings_import_empty)
                },
                onFailure = { ctx.getString(R.string.settings_import_failed) }
            )
        }
    }
}