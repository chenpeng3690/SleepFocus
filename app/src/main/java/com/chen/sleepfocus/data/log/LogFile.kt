package com.chen.sleepfocus.data.log

import android.content.Context
import com.chen.sleepfocus.core.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 操作日志落盘。
 *
 * 日志不再进数据库，而是追加写入应用内部目录下的一个文本文件，路径直接展示在设置页，
 * 方便用 root 或文件管理器查看。是否记录由 [isEnabled] 决定（默认关闭）。
 *
 * 增长控制：只有超过 [MAX_BYTES] 时才整理一次，整理后只保留最近 [KEEP_LINES] 行。
 */
class LogFile(
    private val context: Context,
    private val isEnabled: () -> Boolean
) {

    private companion object {
        const val FILE_NAME = "sleepfocus_log.txt"

        /** 超过该大小触发一次整理 */
        const val MAX_BYTES = 512 * 1024L

        /** 整理后保留的最近行数 */
        const val KEEP_LINES = 1_000
    }

    /** 串行化写入，避免多个调用方（服务 / 广播 / 界面）同时追加时互相覆盖 */
    private val writeMutex = Mutex()

    val file: File get() = File(context.filesDir, FILE_NAME)

    /** 展示给用户的日志文件绝对路径 */
    val path: String get() = file.absolutePath

    suspend fun append(
        action: String,
        packageName: String?,
        success: Boolean,
        message: String?
    ) = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext
        writeMutex.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(formatLine(action, packageName, success, message))
                trimIfTooLarge()
            }
        }
    }

    private fun formatLine(
        action: String,
        packageName: String?,
        success: Boolean,
        message: String?
    ): String = buildString {
        append(TimeUtils.formatDateTimeSeconds(System.currentTimeMillis()))
        append("  ")
        append(if (success) "成功" else "失败")
        append("  ")
        append(action)
        packageName?.let { append("  ").append(it) }
        message?.let { append("  ").append(it) }
        append('\n')
    }

    /** 文件过大时重写为「最近 KEEP_LINES 行」 */
    private fun trimIfTooLarge() {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val kept = file.readLines().takeLast(KEEP_LINES)
        file.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
    }
}