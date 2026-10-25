package com.chen.sleepfocus

import android.Manifest
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chen.sleepfocus.data.model.ThemeMode
import com.chen.sleepfocus.ui.MainViewModel
import com.chen.sleepfocus.ui.nav.AppNavHost
import com.chen.sleepfocus.ui.theme.DayBackground
import com.chen.sleepfocus.ui.theme.NightBackground
import com.chen.sleepfocus.ui.theme.SleepFocusTheme

/**
 * 唯一的 Activity。
 *
 * 通知权限在启动时申请一次：前台服务的倒计时通知依赖它，
 * Android 13+ 未授权时服务仍能运行，但通知栏不会显示剩余时间。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果无需处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先用上次的配色把窗口背景与系统栏定下来，避免开屏先闪一帧错误配色
        val bootTheme = SleepFocusApp.get().bootThemeMode
        val bootDark = when (bootTheme) {
            ThemeMode.SYSTEM -> isSystemNightMode()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        window.setBackgroundDrawable(
            ColorDrawable((if (bootDark) NightBackground else DayBackground).toArgb())
        )
        applySystemBarStyle(bootDark)

        requestNotificationPermissionIfNeeded()

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            // SYSTEM 模式下读取系统配置，系统切换深浅色时会自动重组跟随
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // 系统栏不吃 Compose 主题，切换配色时同步图标明暗
            LaunchedEffect(isDark) { applySystemBarStyle(isDark) }

            SleepFocusTheme(themeMode = themeMode) {
                AppNavHost(viewModel = viewModel)
            }
        }
    }

    /** 冷启动首帧用的系统深浅色（非 Compose 环境，直接读配置） */
    private fun isSystemNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applySystemBarStyle(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}