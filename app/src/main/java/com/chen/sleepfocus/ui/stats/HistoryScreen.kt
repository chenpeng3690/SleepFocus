package com.chen.sleepfocus.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.data.db.SessionEntity

/**
 * 月份专注详情页。由统计页历史模块中点击某个月份进入，展示该月的全部专注记录，
 * 并汇总该月的总专注时间与专注次数（中断的记录不计入次数；时间是否计入中断沿用全局开关）。
 */
@Composable
fun HistoryScreen(
    monthStart: Long,
    sessions: List<SessionEntity>,
    countStoppedInTotal: Boolean,
    onDeleteSession: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthEnd = remember(monthStart) { TimeUtils.nextMonthStartOf(monthStart) }
    val monthSessions = remember(sessions, monthStart, monthEnd) {
        sessions.filter { it.startTime >= monthStart && it.startTime < monthEnd }
    }
    val totalMinutes = remember(monthSessions, countStoppedInTotal) {
        monthSessions
            .filter {
                it.status == Constants.STATUS_COMPLETED ||
                    (countStoppedInTotal && it.status == Constants.STATUS_STOPPED)
            }
            .sumOf { it.actualMinutes }
    }
    val completedCount = remember(monthSessions) {
        monthSessions.count { it.status == Constants.STATUS_COMPLETED }
    }

    // 长按某条记录后待确认删除的目标
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_cancel)
                )
            }
            Text(
                text = TimeUtils.formatMonth(monthStart),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                MonthSummaryCard(totalMinutes = totalMinutes, completedCount = completedCount)
            }

            if (monthSessions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stats_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(items = monthSessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        // 进行中的专注正在倒计时并冻结应用，不允许删除
                        onLongClick = if (session.status == Constants.STATUS_RUNNING) {
                            null
                        } else {
                            { pendingDelete = session }
                        }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(target.id)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun MonthSummaryCard(totalMinutes: Int, completedCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(20.dp)) {
            SummaryCell(
                label = stringResource(R.string.history_total_duration),
                value = TimeUtils.formatDurationText(totalMinutes),
                modifier = Modifier.weight(1f)
            )
            SummaryCell(
                label = stringResource(R.string.history_total_count),
                value = stringResource(R.string.stats_count_times, completedCount),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
