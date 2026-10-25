package com.chen.sleepfocus.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chen.sleepfocus.R
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.data.model.InterruptMode
import com.chen.sleepfocus.data.model.RootStatus
import com.chen.sleepfocus.ui.components.MessageBanner
import kotlinx.coroutines.delay

/**
 * 主页状态。由 MainViewModel 组装，页面只负责渲染与回调。
 */
data class HomeUiState(
    val isRunning: Boolean = false,
    val remainingMillis: Long = 0L,
    val plannedMinutes: Int = Constants.DEFAULT_MINUTES,
    /** 用户保存的自定义时长，按添加顺序，最多 [Constants.MAX_CUSTOM_MINUTES] 个 */
    val customMinutes: List<Int> = emptyList(),
    /** 自定义名额是否还有剩余；满 4 个后「自定义」入口禁用，需长按删除 */
    val canAddCustomMinutes: Boolean = true,
    val selectedCount: Int = 0,
    val todayMinutes: Int = 0,
    val todayCompleted: Int = 0,
    val rootStatus: RootStatus = RootStatus.UNKNOWN,
    val rootDetail: String? = null,
    /** 中断专注模式，决定专注进行中能否提前停止并解冻 */
    val interruptMode: InterruptMode = InterruptMode.ALLOW,
    val message: String? = null
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onPresetSelected: (Int) -> Unit,
    onCustomMinutesSelected: (Int) -> Unit,
    onCustomMinutesRemoved: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onManageApps: () -> Unit,
    onMessageDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var customText by rememberSaveable { mutableStateOf("") }
    var customErrorText by rememberSaveable { mutableStateOf<String?>(null) }
    var showStopDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CountdownCard(uiState)

        TodayCard(uiState)

        if (uiState.rootStatus == RootStatus.DENIED) {
            RootWarningBanner(uiState.rootDetail)
        }

        DurationPicker(
            plannedMinutes = uiState.plannedMinutes,
            customMinutes = uiState.customMinutes,
            canAddCustom = uiState.canAddCustomMinutes,
            enabled = !uiState.isRunning,
            onMinutesSelected = onPresetSelected,
            onCustomRemoved = onCustomMinutesRemoved,
            onCustomClick = { showCustomDialog = true }
        )

        SelectedAppsCard(state = uiState, onManageApps = onManageApps)

        uiState.message?.let { message ->
            MessageBanner(message = message, onDismiss = onMessageDismissed)
        }

        if (uiState.isRunning) {
            if (uiState.interruptMode == InterruptMode.DISALLOW) {
                // 不允许中断：按钮置灰，文案提示无法中断
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(stringResource(R.string.home_stop_disallowed))
                }
            } else {
                OutlinedButton(
                    onClick = { showStopDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(stringResource(R.string.home_stop))
                }
            }
        } else {
            Button(
                onClick = onStart,
                enabled = uiState.rootStatus != RootStatus.DENIED,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(stringResource(R.string.home_start_with_minutes, uiState.plannedMinutes))
            }
        }
    }

    if (showStopDialog) {
        if (uiState.interruptMode == InterruptMode.DELAY) {
            StopDelayDialog(
                onDismiss = { showStopDialog = false },
                onConfirm = {
                    showStopDialog = false
                    onStop()
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showStopDialog = false },
                title = { Text(stringResource(R.string.stop_dialog_title)) },
                text = { Text(stringResource(R.string.stop_dialog_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showStopDialog = false
                            onStop()
                        }
                    ) {
                        Text(stringResource(R.string.stop_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStopDialog = false }) {
                        Text(stringResource(R.string.stop_dialog_cancel))
                    }
                }
            )
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = {
                showCustomDialog = false
                customText = ""
                customErrorText = null
            },
            title = { Text(stringResource(R.string.home_custom_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = it.filter { ch -> ch.isDigit() }.take(3)
                            customErrorText = null
                        },
                        singleLine = true,
                        isError = customErrorText != null,
                        label = { Text(stringResource(R.string.home_custom_minutes)) },
                        placeholder = { Text(stringResource(R.string.home_custom_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    customErrorText?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minutes = customText.toIntOrNull()
                        if (minutes == null ||
                            minutes < Constants.MIN_MINUTES ||
                            minutes > Constants.MAX_MINUTES
                        ) {
                            customErrorText = context.getString(R.string.msg_invalid_minutes)
                        } else {
                            showCustomDialog = false
                            customText = ""
                            customErrorText = null
                            onCustomMinutesSelected(minutes)
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCustomDialog = false
                        customText = ""
                        customErrorText = null
                    }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/**
 * 「延迟」模式的中断确认弹窗：
 * 确认按钮上显示倒计时（如「确认(60秒)」），倒计时结束后按钮才可点击；取消则继续专注。
 */
@Composable
private fun StopDelayDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var remainingSeconds by rememberSaveable { mutableStateOf(Constants.INTERRUPT_DELAY_SECONDS) }

    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds -= 1
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stop_dialog_title)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = remainingSeconds <= 0
            ) {
                Text(
                    if (remainingSeconds > 0) {
                        stringResource(R.string.stop_dialog_confirm_counting, remainingSeconds)
                    } else {
                        stringResource(R.string.stop_dialog_confirm_ready)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.stop_dialog_cancel))
            }
        }
    )
}

@Composable
private fun CountdownCard(state: HomeUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (state.isRunning) {
                    TimeUtils.formatCountdown(state.remainingMillis)
                } else {
                    "--:--"
                },
                style = MaterialTheme.typography.displayLarge,
                color = if (state.isRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (state.isRunning) {
                    stringResource(R.string.home_frozen_count, state.selectedCount)
                } else {
                    stringResource(R.string.home_countdown_idle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TodayCard(state: HomeUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.home_today_card_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(
                    label = stringResource(R.string.home_today_minutes),
                    value = TimeUtils.formatDurationText(state.todayMinutes),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = stringResource(R.string.home_today_finished),
                    value = stringResource(R.string.stats_count_times, state.todayCompleted),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun RootWarningBanner(detail: String?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.notify_root_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail ?: stringResource(R.string.msg_root_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationPicker(
    plannedMinutes: Int,
    customMinutes: List<Int>,
    canAddCustom: Boolean,
    enabled: Boolean,
    onMinutesSelected: (Int) -> Unit,
    onCustomRemoved: (Int) -> Unit,
    onCustomClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.home_preset_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 固定预设（仅 15 分钟，也是默认选项）
                Constants.PRESET_MINUTES.forEach { minutes ->
                    DurationChip(
                        text = stringResource(R.string.home_minutes_chip, minutes),
                        selected = plannedMinutes == minutes,
                        enabled = enabled,
                        onClick = { onMinutesSelected(minutes) }
                    )
                }
                // 用户添加的自定义时长，长按删除
                customMinutes.forEach { minutes ->
                    DurationChip(
                        text = stringResource(R.string.home_minutes_chip, minutes),
                        selected = plannedMinutes == minutes,
                        enabled = enabled,
                        onClick = { onMinutesSelected(minutes) },
                        onLongClick = { onCustomRemoved(minutes) }
                    )
                }
                // 「自定义」只是入口，本身不是一个时长选项
                DurationChip(
                    text = stringResource(R.string.home_custom_chip),
                    selected = false,
                    enabled = enabled && canAddCustom,
                    onClick = onCustomClick
                )
            }
            val hint = when {
                !canAddCustom -> stringResource(R.string.home_duration_full, Constants.MAX_CUSTOM_MINUTES)
                customMinutes.isNotEmpty() -> stringResource(R.string.home_duration_hint)
                else -> null
            }
            hint?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 时长芯片。
 *
 * 没有直接用 FilterChip，是因为它内部自带的点击手势会吞掉长按事件；
 * 这里用 Surface + combinedClickable 自己实现，才能同时支持点击选中与长按删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DurationChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun SelectedAppsCard(state: HomeUiState, onManageApps: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_selected_apps),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.selectedCount == 0) {
                        stringResource(R.string.home_selected_apps_empty)
                    } else {
                        stringResource(R.string.home_selected_apps_count, state.selectedCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onManageApps, enabled = !state.isRunning) {
                Text(stringResource(R.string.home_manage_apps))
            }
        }
    }
}

