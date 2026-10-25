package com.chen.sleepfocus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chen.sleepfocus.MainActivity
import com.chen.sleepfocus.R
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.core.TimeUtils
import com.chen.sleepfocus.focus.FocusAlert
import com.chen.sleepfocus.focus.FocusNotifier

/**
 * 通知渠道与三类通知：进行中（前台服务）、结果、异常提醒。
 */
class Notifications(private val context: Context) : FocusNotifier {

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val ongoing = NotificationChannel(
            Constants.CHANNEL_ONGOING,
            context.getString(R.string.channel_ongoing_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_ongoing_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val result = NotificationChannel(
            Constants.CHANNEL_RESULT,
            context.getString(R.string.channel_result_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_result_desc)
        }

        val alert = NotificationChannel(
            Constants.CHANNEL_ALERT,
            context.getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alert_desc)
        }

        notificationManager.createNotificationChannels(listOf(ongoing, result, alert))
    }

    /** 通知权限是否可用（Android 13+ 需运行时授予 POST_NOTIFICATIONS） */
    fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CONTENT,
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 准备阶段占位通知：检查 root、冻结应用可能耗时数秒，
     * 必须先在 5 秒内 startForeground，否则系统会抛异常。
     */
    fun buildPreparing(): Notification =
        NotificationCompat.Builder(context, Constants.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notify_preparing_title))
            .setContentText(context.getString(R.string.notify_preparing_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent())
            .build()

    /** 构建进行中的常驻通知，含「停止并解冻」按钮 */
    fun buildOngoing(remainingMillis: Long, frozenCount: Int): Notification {
        

        val stopIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, FocusService::class.java).setAction(Constants.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, Constants.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(context.getString(R.string.notify_ongoing_title))
            .setContentText(
                context.getString(
                    R.string.notify_ongoing_text,
                    TimeUtils.formatCountdown(remainingMillis),
                    frozenCount
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent())
            .addAction(0, context.getString(R.string.home_stop), stopIntent)
            .build()
    }

    override fun updateOngoing(remainingMillis: Long, frozenCount: Int) {
        if (!canPostNotifications()) return
        runCatching {
            notificationManager.notify(
                Constants.NOTIFY_ID_ONGOING,
                buildOngoing(remainingMillis, frozenCount)
            )
        }
    }

    override fun cancelOngoing() {
        runCatching { notificationManager.cancel(Constants.NOTIFY_ID_ONGOING) }
    }

    // ---------------- FocusNotifier：控制器只产出语义，文案在这里映射 ----------------

    override fun notifyCompleted(actualMinutes: Int) =
        notifyResult(
            context.getString(R.string.notify_completed_title),
            context.getString(R.string.notify_completed_text, actualMinutes)
        )

    override fun notifyStopped(actualMinutes: Int) =
        notifyResult(
            context.getString(R.string.notify_stopped_title),
            context.getString(R.string.notify_stopped_text, actualMinutes)
        )

    override fun notifyRecovered(remainingMillis: Long) =
        notifyResult(
            context.getString(R.string.notify_recovered_title),
            context.getString(
                R.string.notify_recovered_text,
                TimeUtils.formatCountdown(remainingMillis)
            )
        )

    override fun notifyAlert(alert: FocusAlert, detail: String?) {
        val title = when (alert) {
            FocusAlert.ROOT_MISSING -> context.getString(R.string.notify_root_error_title)
            FocusAlert.FREEZE_PARTIAL_FAILED -> context.getString(R.string.notify_partial_fail_title)
            FocusAlert.NO_APPS_SELECTED,
            FocusAlert.FREEZE_ALL_FAILED,
            FocusAlert.SERVICE_ERROR -> context.getString(R.string.notify_freeze_all_fail_title)
        }
        val text = when (alert) {
            FocusAlert.NO_APPS_SELECTED ->
                context.getString(R.string.msg_no_app_selected)

            FocusAlert.ROOT_MISSING ->
                context.getString(R.string.msg_root_required) + (detail?.let { "\n$it" } ?: "")

            FocusAlert.FREEZE_ALL_FAILED ->
                context.getString(R.string.notify_freeze_all_fail_text)

            FocusAlert.FREEZE_PARTIAL_FAILED ->
                context.getString(R.string.notify_partial_fail_text, detail.orEmpty())

            FocusAlert.SERVICE_ERROR ->
                detail ?: context.getString(R.string.common_unknown)
        }
        notifyAlert(title, text)
    }

    /** 完成 / 提前停止的结果通知 */
    fun notifyResult(title: String, text: String) {
        if (!canPostNotifications()) return
        

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

        runCatching { notificationManager.notify(Constants.NOTIFY_ID_RESULT, notification) }
    }

    /** 异常提醒：Root 失效、恢复失败等，必须让用户看到，不能静默 */
    fun notifyAlert(title: String, text: String) {
        if (!canPostNotifications()) return
        

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent())
            .build()

        runCatching { notificationManager.notify(Constants.NOTIFY_ID_ALERT, notification) }
    }

    private companion object {
        const val REQUEST_CONTENT = 3001
        const val REQUEST_STOP = 3002
    }
}