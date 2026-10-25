package com.chen.sleepfocus.root

import com.chen.sleepfocus.data.model.RootCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 单条 root 命令的执行结果。
 *
 * @param success 进程退出码为 0
 * @param output  合并后的 stdout + stderr
 * @param exitCode 退出码，超时或启动失败时为 -1
 * @param error   失败原因（成功时为 null）
 */
data class CommandResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
    val error: String? = null
)

/**
 * 通过 su 执行 shell 命令。
 *
 * 说明：不做长期常驻的 root shell（需要读写管道且易僵死），
 * 而是每次 `su -c "..."` 执行；批量场景把多条命令用 ` ; ` 拼成一条脚本，只付一次 su 开销。
 */
class RootShell {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val LONG_TIMEOUT_MS = 60_000L
    }

    /** 执行 `su -c <command>` */
    suspend fun execSu(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return@withContext CommandResult(
                success = false,
                output = "",
                exitCode = -1,
                error = "无法启动 su 进程：${t.message ?: t.javaClass.simpleName}"
            )
        }

        // 独立线程读取输出，避免管道写满导致子进程阻塞
        val outputBuffer = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        outputBuffer.append(line).append('\n')
                        line = br.readLine()
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            false
        }

        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { reader.join(500) }
            return@withContext CommandResult(
                success = false,
                output = outputBuffer.toString().trim(),
                exitCode = -1,
                error = "命令执行超时（${timeoutMs}ms）"
            )
        }

        runCatching { reader.join(500) }
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        val output = outputBuffer.toString().trim()
        CommandResult(
            success = exit == 0,
            output = output,
            exitCode = exit,
            error = if (exit == 0) null else "exit=$exit${if (output.isEmpty()) "" else " · $output"}"
        )
    }

    /**
     * 检测 root：`su -c id` 输出包含 `uid=0` 即视为已授权。
     */
    suspend fun checkRoot(timeoutMs: Long = 10_000L): RootCheckResult {
        val result = execSu("id", timeoutMs)
        val output = result.output
        return when {
            result.success && output.contains("uid=0") ->
                RootCheckResult(true, output.replace('\n', ' ').trim())

            output.contains("uid=0") ->
                RootCheckResult(true, output.replace('\n', ' ').trim())

            result.error?.contains("无法启动 su") == true ->
                RootCheckResult(false, "未找到 su 可执行文件，设备可能未 Root")

            else ->
                RootCheckResult(false, result.error ?: "su 返回内容异常：${output.ifBlank { "（空）" }}")
        }
    }

    /** 批量执行：把多条命令拼成一条脚本，减少 su 调用次数 */
    suspend fun execSuBatch(
        commands: List<String>,
        timeoutMs: Long = LONG_TIMEOUT_MS
    ): CommandResult {
        if (commands.isEmpty()) return CommandResult(true, "", 0)
        return execSu(commands.joinToString(separator = " ; "), timeoutMs)
    }
}