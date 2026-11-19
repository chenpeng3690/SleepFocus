package com.chen.sleepfocus.ui.plans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.chen.sleepfocus.data.model.SleepPlan
import com.chen.sleepfocus.ui.components.ConfirmDialog
import com.chen.sleepfocus.ui.components.SectionTitle
import com.chen.sleepfocus.ui.components.SettingCard
import com.chen.sleepfocus.ui.components.SettingRow
import com.chen.sleepfocus.ui.components.TimePickerDialog
import com.chen.sleepfocus.ui.components.formatMinuteOfDay

/**
 * 计划列表页：像闹钟列表一样展示多条计划，每条计划有自己的时间与冻结应用。
 */
@Composable
fun PlansScreen(
    plans: List<SleepPlan>,
    onAddPlan: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    onPlanEnabledChanged: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (plans.isEmpty()) {
            item(key = "empty") { EmptyPlansCard() }
        } else {
            items(items = plans, key = { it.id }) { plan ->
                PlanCard(
                    plan = plan,
                    onClick = { onOpenPlan(plan.id) },
                    onEnabledChanged = { onPlanEnabledChanged(plan.id, it) }
                )
            }
        }

        item(key = "add") {
            Button(
                onClick = onAddPlan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.plans_add))
            }
        }

        item(key = "bottom_space") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EmptyPlansCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.plans_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.plans_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlanCard(
    plan: SleepPlan,
    onClick: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatMinuteOfDay(plan.startMinuteOfDay),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = " → ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMinuteOfDay(plan.endMinuteOfDay),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = TimeUtils.formatDurationText(plan.durationMinutes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                val days = daySummary(plan.activeDays)
                Text(
                    text = if (plan.packages.isEmpty()) {
                        stringResource(R.string.plans_card_desc_empty, days)
                    } else {
                        stringResource(R.string.plans_card_desc, days, plan.packages.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (plan.packages.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(
                checked = plan.enabled,
                onCheckedChange = onEnabledChanged
            )
        }
    }
}

private enum class TimeField { START, END }

/**
 * 计划编辑页：修改开始 / 解冻时间与重复星期、启停计划、选择要冻结的应用。
 * 所有改动即时写入并重排闹钟，因此没有额外的「保存」按钮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditScreen(
    plan: SleepPlan,
    onStartTimeChanged: (Int) -> Unit,
    onEndTimeChanged: (Int) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onDayToggled: (Int) -> Unit,
    onManageApps: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingField by remember { mutableStateOf<TimeField?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        PlanTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle(stringResource(R.string.plans_section_time))
            SettingCard {
                SettingRow(
                    title = stringResource(R.string.plans_start_time),
                    trailing = {
                        TextButton(onClick = { editingField = TimeField.START }) {
                            Text(formatMinuteOfDay(plan.startMinuteOfDay))
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = stringResource(R.string.plans_end_time),
                    subtitle = if (plan.isCrossDay) {
                        stringResource(R.string.plans_cross_day)
                    } else {
                        null
                    },
                    trailing = {
                        TextButton(onClick = { editingField = TimeField.END }) {
                            Text(formatMinuteOfDay(plan.endMinuteOfDay))
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = stringResource(R.string.plans_enabled),
                    trailing = {
                        Switch(
                            checked = plan.enabled,
                            onCheckedChange = onEnabledChanged
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.plans_repeat),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DAY_ORDER.forEach { day ->
                        FilterChip(
                            selected = day in plan.activeDays,
                            onClick = { onDayToggled(day) },
                            label = { Text(stringResource(dayLabelRes(day))) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (plan.activeDays.size <= 1) {
                    Text(
                        text = stringResource(R.string.plans_days_required),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = stringResource(R.string.plans_time_tip),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionTitle(stringResource(R.string.plans_section_apps))
            SettingCard {
                SettingRow(
                    title = stringResource(R.string.plans_selected_apps),
                    subtitle = if (plan.packages.isEmpty()) {
                        stringResource(R.string.plans_no_apps)
                    } else {
                        stringResource(R.string.plans_selected_count, plan.packages.size)
                    },
                    trailing = {
                        TextButton(onClick = onManageApps) {
                            Text(stringResource(R.string.plans_select_apps))
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showDeleteDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.plans_delete))
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    editingField?.let { field ->
        TimePickerDialog(
            initialMinuteOfDay = if (field == TimeField.START) {
                plan.startMinuteOfDay
            } else {
                plan.endMinuteOfDay
            },
            onDismiss = { editingField = null },
            onConfirm = { minuteOfDay ->
                editingField = null
                if (field == TimeField.START) {
                    onStartTimeChanged(minuteOfDay)
                } else {
                    onEndTimeChanged(minuteOfDay)
                }
            }
        )
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.plans_delete_title),
            message = stringResource(R.string.plans_delete_message),
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            }
        )
    }
}

/** 周一 … 周日，与 java.time.DayOfWeek.value 对齐 */
private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 7)

private fun dayLabelRes(day: Int): Int = when (day) {
    1 -> R.string.plans_day_mon
    2 -> R.string.plans_day_tue
    3 -> R.string.plans_day_wed
    4 -> R.string.plans_day_thu
    5 -> R.string.plans_day_fri
    6 -> R.string.plans_day_sat
    else -> R.string.plans_day_sun
}

@Composable
private fun daySummary(days: Set<Int>): String {
    val active = if (days.isEmpty()) SleepPlan.ALL_DAYS else days
    return when (active) {
        SleepPlan.ALL_DAYS -> stringResource(R.string.plans_days_everyday)
        SleepPlan.WORKDAYS -> stringResource(R.string.plans_days_workdays)
        SleepPlan.WEEKEND -> stringResource(R.string.plans_days_weekend)
        else -> {
            val labels = ArrayList<String>(active.size)
            for (day in DAY_ORDER) {
                if (day in active) labels.add(stringResource(dayLabelRes(day)))
            }
            labels.joinToString("、")
        }
    }
}

@Composable
private fun PlanTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_cancel)
            )
        }
        Text(
            text = stringResource(R.string.plans_edit_title),
            style = MaterialTheme.typography.headlineSmall
        )
    }
}
