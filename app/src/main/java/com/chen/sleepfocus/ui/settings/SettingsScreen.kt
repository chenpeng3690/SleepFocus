package com.chen.sleepfocus.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chen.sleepfocus.R
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.data.model.InterruptMode
import com.chen.sleepfocus.data.model.ScheduleConfig
import com.chen.sleepfocus.data.model.ThemeMode
import com.chen.sleepfocus.ui.components.MessageBanner

/**
 * 设置页状态。按「定时睡眠 → 中断专注 → 颜色切换 → 日志 → 数据」五节渲染。
 * Root 状态与权限不在这里显示（Root 状态在主页的告警条上体现）。
 */
data class SettingsUiState(
    val schedule: ScheduleConfig = ScheduleConfig(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val interruptMode: InterruptMode = InterruptMode.ALLOW,
    /** 是否有专注正在进行中；进行中时禁止修改中断专注选项 */
    val isRunning: Boolean = false,
    /** 是否记录执行日志，默认关闭 */
    val logEnabled: Boolean = false,
    val logFilePath: String = "",
    val message: String? = null
)

private enum class TimeField { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onScheduleEnabledChanged: (Boolean) -> Unit,
    onScheduleTimeChanged: (startMinuteOfDay: Int, endMinuteOfDay: Int) -> Unit,
    onInterruptModeChanged: (InterruptMode) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onLogEnabledChanged: (Boolean) -> Unit,
    onClearStats: () -> Unit,
    onExportData: (Uri) -> Unit,
    onImportData: (Uri) -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingTime by remember { mutableStateOf<TimeField?>(null) }
    var showClearStatsDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportData) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportData) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------------- 定时睡眠 ----------------
        item(key = "schedule_title") {
            SectionTitle(stringResource(R.string.settings_schedule_section))
        }
        item(key = "schedule_card") {
            SettingCard {
                SettingRow(
                    title = stringResource(R.string.settings_schedule_enabled),
                    trailing = {
                        Switch(
                            checked = uiState.schedule.enabled,
                            onCheckedChange = onScheduleEnabledChanged
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = stringResource(R.string.settings_schedule_start),
                    trailing = {
                        TextButton(
                            onClick = { editingTime = TimeField.START },
                            enabled = uiState.schedule.enabled
                        ) {
                            Text(formatMinuteOfDay(uiState.schedule.startMinuteOfDay))
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = stringResource(R.string.settings_schedule_end),
                    trailing = {
                        TextButton(
                            onClick = { editingTime = TimeField.END },
                            enabled = uiState.schedule.enabled
                        ) {
                            Text(formatMinuteOfDay(uiState.schedule.endMinuteOfDay))
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_schedule_tip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------------- 中断专注 ----------------
        item(key = "interrupt_title") {
            SectionTitle(stringResource(R.string.settings_interrupt_section))
        }
        item(key = "interrupt_card") {
            SettingCard {
                Text(
                    text = stringResource(R.string.settings_interrupt_desc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.interruptMode == InterruptMode.ALLOW,
                        onClick = { onInterruptModeChanged(InterruptMode.ALLOW) },
                        enabled = !uiState.isRunning,
                        label = { Text(stringResource(R.string.settings_interrupt_allow)) }
                    )
                    FilterChip(
                        selected = uiState.interruptMode == InterruptMode.DELAY,
                        onClick = { onInterruptModeChanged(InterruptMode.DELAY) },
                        enabled = !uiState.isRunning,
                        label = { Text(stringResource(R.string.settings_interrupt_delay)) }
                    )
                    FilterChip(
                        selected = uiState.interruptMode == InterruptMode.DISALLOW,
                        onClick = { onInterruptModeChanged(InterruptMode.DISALLOW) },
                        enabled = !uiState.isRunning,
                        label = { Text(stringResource(R.string.settings_interrupt_disallow)) }
                    )
                }
            }
        }

        // ---------------- 颜色切换 ----------------
        item(key = "theme_title") {
            SectionTitle(stringResource(R.string.settings_theme_section))
        }
        item(key = "theme_card") {
            SettingCard {
                Text(
                    text = stringResource(R.string.settings_theme_desc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.themeMode == ThemeMode.SYSTEM,
                        onClick = { onThemeModeChanged(ThemeMode.SYSTEM) },
                        label = { Text(stringResource(R.string.settings_theme_system)) }
                    )
                    FilterChip(
                        selected = uiState.themeMode == ThemeMode.LIGHT,
                        onClick = { onThemeModeChanged(ThemeMode.LIGHT) },
                        label = { Text(stringResource(R.string.settings_theme_light)) }
                    )
                    FilterChip(
                        selected = uiState.themeMode == ThemeMode.DARK,
                        onClick = { onThemeModeChanged(ThemeMode.DARK) },
                        label = { Text(stringResource(R.string.settings_theme_dark)) }
                    )
                }
            }
        }

        // ---------------- 日志 ----------------
        item(key = "logs_title") {
            SectionTitle(stringResource(R.string.logs_title))
        }
        item(key = "logs_card") {
            SettingCard {
                SettingRow(
                    title = stringResource(R.string.settings_log_enabled),
                    trailing = {
                        Switch(
                            checked = uiState.logEnabled,
                            onCheckedChange = onLogEnabledChanged
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.logs_path_desc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = uiState.logFilePath,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ---------------- 数据 ----------------
        item(key = "data_title") {
            SectionTitle(stringResource(R.string.settings_data_section))
        }
        item(key = "data_card") {
            SettingCard {
                Text(
                    text = stringResource(R.string.settings_data_backup_desc),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportLauncher.launch(defaultExportFileName()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_export_data))
                    }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_import_data))
                    }
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { showClearStatsDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_clear_stats))
                }
            }
        }

        uiState.message?.let { message ->
            item(key = "message") {
                MessageBanner(message = message, onDismiss = onMessageDismissed)
            }
        }

        item(key = "bottom_space") { Spacer(Modifier.height(8.dp)) }
    }

    editingTime?.let { field ->
        val initial = if (field == TimeField.START) {
            uiState.schedule.startMinuteOfDay
        } else {
            uiState.schedule.endMinuteOfDay
        }
        TimePickerDialog(
            initialMinuteOfDay = initial,
            onDismiss = { editingTime = null },
            onConfirm = { minuteOfDay ->
                editingTime = null
                if (field == TimeField.START) {
                    onScheduleTimeChanged(minuteOfDay, uiState.schedule.endMinuteOfDay)
                } else {
                    onScheduleTimeChanged(uiState.schedule.startMinuteOfDay, minuteOfDay)
                }
            }
        )
    }

    if (showClearStatsDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_stats),
            message = stringResource(R.string.settings_clear_stats_confirm),
            onDismiss = { showClearStatsDialog = false },
            onConfirm = {
                showClearStatsDialog = false
                onClearStats()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp)
    )
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

private fun formatMinuteOfDay(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** 导出时的默认文件名，例如 sleepfocus_backup_20261011_153012.json */
private fun defaultExportFileName(): String =
    "sleepfocus_backup_${TimeUtils.formatFileStamp(System.currentTimeMillis())}.json"