package com.chen.sleepfocus.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chen.sleepfocus.R
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.data.db.SessionEntity
import com.chen.sleepfocus.data.model.DailyFocus
import com.chen.sleepfocus.data.model.MonthFocus
import com.chen.sleepfocus.ui.theme.StatCompleted
import com.chen.sleepfocus.ui.theme.StatRunning
import com.chen.sleepfocus.ui.theme.StatStopped

/** 柱状图可选的时间范围（天） */
private const val RANGE_WEEK_DAYS = 7
private const val RANGE_MONTH_DAYS = 30

/**
 * 统计页状态。
 */
data class StatsUiState(
    val todayMinutes: Int = 0,
    val weekMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val completedCount: Int = 0,
    val stoppedCount: Int = 0,
    /** 近 7 天每日专注 */
    val daily: List<DailyFocus> = emptyList(),
    /** 近 30 天每日专注 */
    val dailyMonth: List<DailyFocus> = emptyList(),
    /** 全部月份汇总，按月倒序，第一项为当前月 */
    val months: List<MonthFocus> = emptyList()
)

@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onOpenMonth: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // 历史模块默认只展示当前月，点「查看更多」后展开其余月份
    var showAllMonths by rememberSaveable { mutableStateOf(false) }
    val currentMonth = uiState.months.firstOrNull()
    val otherMonths = uiState.months.drop(1)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { DurationSummaryCard(uiState) }

        item { CountSummaryCard(uiState) }

        item { FocusChartCard(daily7 = uiState.daily, daily30 = uiState.dailyMonth) }

        item {
            Text(
                text = stringResource(R.string.stats_history),
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (currentMonth == null) {
            item {
                Text(
                    text = stringResource(R.string.stats_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            item(key = "current_month") {
                MonthRow(month = currentMonth, onClick = { onOpenMonth(currentMonth.monthStart) })
            }

            if (showAllMonths) {
                items(items = otherMonths, key = { it.monthStart }) { month ->
                    MonthRow(month = month, onClick = { onOpenMonth(month.monthStart) })
                }
            } else if (otherMonths.isNotEmpty()) {
                item(key = "more_history") {
                    TextButton(
                        onClick = { showAllMonths = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.stats_history_more))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun DurationSummaryCard(state: StatsUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(20.dp)) {
            SummaryCell(
                label = stringResource(R.string.stats_today),
                value = TimeUtils.formatDurationText(state.todayMinutes),
                modifier = Modifier.weight(1f)
            )
            SummaryCell(
                label = stringResource(R.string.stats_week),
                value = TimeUtils.formatDurationText(state.weekMinutes),
                modifier = Modifier.weight(1f)
            )
            SummaryCell(
                label = stringResource(R.string.stats_total),
                value = TimeUtils.formatDurationText(state.totalMinutes),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CountSummaryCard(state: StatsUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(20.dp)) {
            SummaryCell(
                label = stringResource(R.string.stats_finished_count),
                value = stringResource(R.string.stats_count_times, state.completedCount),
                modifier = Modifier.weight(1f)
            )
            SummaryCell(
                label = stringResource(R.string.stats_stopped_count),
                value = stringResource(R.string.stats_count_times, state.stoppedCount),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 专注柱状图卡片：顶部用「近 7 天 / 近 30 天」分段切换，底部是日期坐标轴。
 * 柱状图自身沿用原有绘制方式与配色（primary / surfaceVariant），此处只调整外围布局。
 */
@Composable
private fun FocusChartCard(daily7: List<DailyFocus>, daily30: List<DailyFocus>) {
    var rangeDays by rememberSaveable { mutableIntStateOf(RANGE_WEEK_DAYS) }
    val daily = if (rangeDays == RANGE_WEEK_DAYS) daily7 else daily30

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            RangeSelector(
                selectedDays = rangeDays,
                onSelect = { rangeDays = it }
            )
            Spacer(Modifier.height(20.dp))
            FocusBarChart(daily = daily)
        }
    }
}

@Composable
private fun RangeSelector(
    selectedDays: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        RangeOption(
            label = stringResource(R.string.stats_range_7days),
            selected = selectedDays == RANGE_WEEK_DAYS,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(RANGE_WEEK_DAYS) }
        )
        RangeOption(
            label = stringResource(R.string.stats_range_30days),
            selected = selectedDays == RANGE_MONTH_DAYS,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(RANGE_MONTH_DAYS) }
        )
    }
}

@Composable
private fun RangeOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 专注柱状图。用 Compose Canvas 手绘，不引第三方图表库。
 *
 * 只画柱子，日期坐标轴放在下方用 Text 渲染 —— 这样不用在 DrawScope 里测量文字，
 * 既少踩 API 版本的坑，也能让数字随主题字体缩放。
 */
@Composable
private fun FocusBarChart(daily: List<DailyFocus>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val maxMinutes = (daily.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            if (daily.isEmpty()) return@Canvas

            val slotWidth = size.width / daily.size
            val barWidth = slotWidth * 0.5f
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
            val minBarHeight = 3.dp.toPx()
            val topPadding = 6.dp.toPx()
            val usableHeight = size.height - topPadding

            daily.forEachIndexed { index, day ->
                val ratio = day.minutes.toFloat() / maxMinutes
                val barHeight = (usableHeight * ratio).coerceAtLeast(minBarHeight)
                val left = slotWidth * index + (slotWidth - barWidth) / 2f
                val top = size.height - barHeight

                drawRoundRect(
                    color = if (day.minutes > 0) barColor else emptyColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner
                )
            }
        }

        if (daily.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = TimeUtils.formatDateSlash(daily.first().dayStart),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = TimeUtils.formatDateSlash(daily.last().dayStart),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 历史专注的月份行：左侧月份标签，右侧该月「专注时长 · 次数」与进入箭头。
 */
@Composable
private fun MonthRow(month: MonthFocus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = TimeUtils.formatMonth(month.monthStart),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    R.string.history_month_summary,
                    TimeUtils.formatDurationText(month.minutes),
                    month.count
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: SessionEntity,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val (statusText, statusColor) = when (session.status) {
        Constants.STATUS_RUNNING ->
            stringResource(R.string.stats_status_running) to StatRunning

        Constants.STATUS_COMPLETED ->
            stringResource(R.string.stats_status_completed) to StatCompleted

        else ->
            stringResource(R.string.stats_status_stopped) to StatStopped
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TimeUtils.formatDateTime(session.startTime),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(text = statusText, color = statusColor)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    text = stringResource(R.string.stats_plan_duration, session.plannedMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.stats_actual_duration, session.actualMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            session.endTime?.let { end ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = TimeUtils.formatDateTime(end),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.width(64.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
        )
    }
}
