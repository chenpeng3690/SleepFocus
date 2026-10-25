package com.chen.sleepfocus.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chen.sleepfocus.R
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.ui.MainViewModel
import com.chen.sleepfocus.ui.apps.AppSelectScreen
import com.chen.sleepfocus.ui.home.HomeScreen
import com.chen.sleepfocus.ui.settings.SettingsScreen
import com.chen.sleepfocus.ui.stats.HistoryScreen
import com.chen.sleepfocus.ui.stats.StatsScreen

object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val STATS = "stats"
    const val HISTORY = "history/{monthStart}"
    const val SETTINGS = "settings"

    /** 进入某个月份的专注详情页 */
    fun history(monthStart: Long) = "history/$monthStart"
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

/**
 * 底栏「统计」图标。原先用 material-icons-extended 里的 BarChart，
 * 该库会把五套主题共两万多个图标全部打进 APK，故这里手写一个 24dp 矢量图标替代。
 */
private val BarChartIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BarChart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 9.2f)
            horizontalLineTo(8f)
            verticalLineTo(19f)
            horizontalLineTo(5f)
            close()
            moveTo(10.6f, 5f)
            horizontalLineTo(13.4f)
            verticalLineTo(19f)
            horizontalLineTo(10.6f)
            close()
            moveTo(16.2f, 13f)
            horizontalLineTo(19f)
            verticalLineTo(19f)
            horizontalLineTo(16.2f)
            close()
        }
    }.build()
}

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    BottomTab(Routes.STATS, R.string.nav_stats, BarChartIcon),
    BottomTab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
)

/**
 * 应用导航。
 *
 * 应用选择页是压在栈上的全屏页面，因此它自己的顶栏与保存栏接管界面，
 * 这一层隐藏 TopAppBar 与底部导航，只保留系统栏内边距。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    viewModel: MainViewModel
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME
    val isFullScreenRoute = currentRoute == Routes.APPS || currentRoute == Routes.HISTORY

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!isFullScreenRoute) {
                TopAppBar(
                    title = { Text(text = routeTitle(currentRoute)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        bottomBar = {
            if (!isFullScreenRoute) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(tab.labelRes)
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                val state by viewModel.homeState.collectAsStateWithLifecycle()
                HomeScreen(
                    uiState = state,
                    onPresetSelected = viewModel::onDurationSelected,
                    onCustomMinutesSelected = viewModel::onCustomMinutesSelected,
                    onCustomMinutesRemoved = viewModel::onCustomMinutesRemoved,
                    onStart = viewModel::startFocus,
                    onStop = viewModel::stopFocus,
                    onManageApps = { navController.navigate(Routes.APPS) },
                    onMessageDismissed = viewModel::dismissMessage
                )
            }

            composable(Routes.APPS) {
                val state by viewModel.appSelectState.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) { viewModel.loadInstalledApps() }

                AppSelectScreen(
                    apps = state.apps,
                    selectedPackages = state.selected,
                    isLoading = state.isLoading,
                    onToggleApp = viewModel::toggleApp,
                    onSelectionChanged = viewModel::changeVisibleSelection,
                    onSave = {
                        viewModel.saveSelectedApps()
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.STATS) {
                val state by viewModel.statsState.collectAsStateWithLifecycle()
                StatsScreen(
                    uiState = state,
                    onOpenMonth = { monthStart -> navController.navigate(Routes.history(monthStart)) }
                )
            }

            composable(
                route = Routes.HISTORY,
                arguments = listOf(navArgument("monthStart") { type = NavType.LongType })
            ) { entry ->
                val monthStart = entry.arguments?.getLong("monthStart")
                    ?: TimeUtils.monthStartOf(System.currentTimeMillis())
                val sessions by viewModel.historySessions.collectAsStateWithLifecycle()
                val countStopped by viewModel.countStoppedInTotal.collectAsStateWithLifecycle()
                HistoryScreen(
                    monthStart = monthStart,
                    sessions = sessions,
                    countStoppedInTotal = countStopped,
                    onDeleteSession = viewModel::deleteSession,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.SETTINGS) {
                val state by viewModel.settingsState.collectAsStateWithLifecycle()
                SettingsScreen(
                    uiState = state,
                    onScheduleEnabledChanged = viewModel::setScheduleEnabled,
                    onScheduleTimeChanged = viewModel::setScheduleTime,
                    onInterruptModeChanged = viewModel::setInterruptMode,
                    onThemeModeChanged = viewModel::setThemeMode,
                    onLogEnabledChanged = viewModel::setLogEnabled,
                    onClearStats = viewModel::clearStats,
                    onExportData = viewModel::exportData,
                    onImportData = viewModel::importData,
                    onMessageDismissed = viewModel::dismissMessage
                )
            }
        }
    }
}

@Composable
private fun routeTitle(route: String): String = when (route) {
    Routes.HOME -> stringResource(R.string.home_title)
    Routes.STATS -> stringResource(R.string.stats_title)
    Routes.SETTINGS -> stringResource(R.string.settings_title)
    else -> stringResource(R.string.app_name)
}