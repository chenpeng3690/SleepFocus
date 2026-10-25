package com.chen.sleepfocus

import android.app.Application
import androidx.room.Room
import com.chen.sleepfocus.alarm.AlarmScheduler
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.data.db.AppDatabase
import com.chen.sleepfocus.data.log.LogFile
import com.chen.sleepfocus.data.model.ThemeMode
import com.chen.sleepfocus.data.prefs.SettingsStore
import com.chen.sleepfocus.data.repo.AppRepository
import com.chen.sleepfocus.data.repo.FocusRepository
import com.chen.sleepfocus.root.PackageController
import com.chen.sleepfocus.root.RootShell
import com.chen.sleepfocus.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application 兼简易 ServiceLocator，供 Activity / Service / Receiver 取用单例。
 */
class SleepFocusApp : Application() {

    /** 应用级协程作用域，用于不可中断的收尾动作（解冻、写库） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, Constants.DB_NAME)
            .fallbackToDestructiveMigration()
            .build()
    }

    val settings: SettingsStore by lazy { SettingsStore(applicationContext) }

    /** 日志开关的内存快照，供 [LogFile] 在写入热路径上同步判断（DataStore 只能异步读取） */
    @Volatile
    private var logEnabled: Boolean = false

    val logFile: LogFile by lazy { LogFile(applicationContext) { logEnabled } }

    val focusRepository: FocusRepository by lazy {
        FocusRepository(
            sessionDao = database.sessionDao(),
            settings = settings,
            logFile = logFile
        )
    }

    val appRepository: AppRepository by lazy { AppRepository(applicationContext) }

    val rootShell: RootShell by lazy { RootShell() }

    val packageController: PackageController by lazy {
        PackageController(
            context = applicationContext,
            rootShell = rootShell,
            focusRepository = focusRepository
        )
    }

    val notifications: Notifications by lazy { Notifications(applicationContext) }

    val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler(applicationContext, settings) }

    /**
     * 冷启动首帧要用的配色。
     *
     * DataStore 只能异步读取，但窗口背景与 Compose 首帧必须立刻知道用户上次的选择，
     * 否则会先渲染一帧深色再切到浅色。这里同步读一次（小文件，开销可忽略），且只算一次。
     */
    val bootThemeMode: ThemeMode by lazy { runBlocking { settings.themeMode.first() } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 启动时同步读一次日志开关，保证用户已开启日志时重启后不丢早期日志
        logEnabled = runBlocking { settings.logEnabled.first() }
        appScope.launch { settings.logEnabled.collect { logEnabled = it } }
        notifications.createChannels()
    }

    companion object {
        @Volatile
        lateinit var instance: SleepFocusApp
            private set

        fun get(): SleepFocusApp = instance
    }
}