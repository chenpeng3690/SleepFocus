package com.chen.sleepfocus.root

import android.content.Context
import com.chen.sleepfocus.core.Constants
import com.chen.sleepfocus.data.model.RootCheckResult
import com.chen.sleepfocus.focus.PackageFreezer
import com.chen.sleepfocus.focus.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批量冻结 / 解冻的结果。失败的包名会带原因写入日志。
 */
data class BatchResult(
    val succeeded: List<String>,
    val failed: List<String>
) {
    val allSucceeded: Boolean get() = failed.isEmpty()
    val total: Int get() = succeeded.size + failed.size
}

/**
 * 应用冻结控制器（全程 root 执行）。
 *
 * 冻结：`am force-stop`（杀掉已在运行的进程） + `pm suspend`
 * 解冻：`pm unsuspend`
 *
 * 用 `pm suspend` 而不是 `pm disable-user`：
 * `disable-user` 会让包从 PackageManager 的可用列表里消失，启动器收到移除广播后把图标删掉，
 * 解冻时再重新插入 —— 桌面布局（位置、文件夹、页签）因此被打乱。
 * `suspend` 只把包置为系统的「已暂停」状态：图标留在启动器原位、点击被系统拦截无法启动、
 * 进程被终止、通知被抑制，`unsuspend` 后原位恢复，启动器不需要重排。
 */
class PackageController(
    private val context: Context,
    private val rootShell: RootShell,
    private val focusRepository: SessionStore
) : PackageFreezer {

    override suspend fun checkRoot(): RootCheckResult {
        val result = rootShell.checkRoot()
        focusRepository.log(
            action = Constants.LOG_ACTION_ROOT_CHECK,
            packageName = null,
            success = result.granted,
            message = result.detail
        )
        return result
    }

    /** 冻结一批应用，返回逐包的成功/失败结果 */
    override suspend fun freezeAll(packages: List<String>): BatchResult {
        val targets = packages.distinct().filter { it.isNotBlank() }
        if (targets.isEmpty()) return BatchResult(emptyList(), emptyList())

        // 1) 先杀进程：suspend 能阻止新进程启动，但已经在跑的进程需要显式收尾
        rootShell.execSuBatch(targets.map { "am force-stop $it" })

        // 2) 批量挂起
        rootShell.execSuBatch(targets.map { "pm suspend --user 0 $it" })

        // 3) 逐个向系统确认；未生效的再用不带 --user 的形式兜底
        //    （个别 ROM 的 pm 不接受 --user，也有的只认带 --user 的形式）
        var suspended = filterSuspended(targets)
        val pending = targets.filterNot { it in suspended }
        if (pending.isNotEmpty()) {
            rootShell.execSuBatch(pending.map { "pm suspend $it" })
            suspended = filterSuspended(targets)
        }

        val succeeded = targets.filter { it in suspended }
        val failed = targets.filterNot { it in suspended }

        succeeded.forEach {
            focusRepository.log(action = Constants.LOG_ACTION_FREEZE, packageName = it, success = true)
        }
        failed.forEach {
            focusRepository.log(
                action = Constants.LOG_ACTION_FREEZE,
                packageName = it,
                success = false,
                message = "pm suspend 未生效（已尝试带 / 不带 --user 两种形式）"
            )
        }
        return BatchResult(succeeded, failed)
    }

    /** 解冻一批应用 */
    override suspend fun unfreezeAll(packages: List<String>): BatchResult {
        val targets = packages.distinct().filter { it.isNotBlank() }
        if (targets.isEmpty()) return BatchResult(emptyList(), emptyList())

        rootShell.execSuBatch(targets.map { "pm unsuspend --user 0 $it" })

        var stillSuspended = filterSuspended(targets)
        if (stillSuspended.isNotEmpty()) {
            rootShell.execSuBatch(stillSuspended.map { "pm unsuspend $it" })
            stillSuspended = filterSuspended(targets)
        }

        val failed = targets.filter { it in stillSuspended }
        val succeeded = targets.filterNot { it in failed.toSet() }

        succeeded.forEach {
            focusRepository.log(action = Constants.LOG_ACTION_UNFREEZE, packageName = it, success = true)
        }
        failed.forEach {
            focusRepository.log(
                action = Constants.LOG_ACTION_UNFREEZE,
                packageName = it,
                success = false,
                message = "pm unsuspend 未生效（已尝试带 / 不带 --user 两种形式）"
            )
        }
        return BatchResult(succeeded, failed)
    }

    /**
     * 倒计时期间的复查：某些 ROM 在应用被点击或系统整理时会清掉暂停状态。
     * 只对「本次已冻结但当前不在暂停态」的包重新处理，无事发生时保持完全静默。
     *
     * @return 本次重新冻结成功的包名
     */
    override suspend fun reEnforce(frozenPackages: List<String>): List<String> {
        if (frozenPackages.isEmpty()) return emptyList()

        val suspended = filterSuspended(frozenPackages)
        val released = frozenPackages.filterNot { it in suspended }
        if (released.isEmpty()) return emptyList()

        rootShell.execSuBatch(
            released.map { "am force-stop $it" } + released.map { "pm suspend --user 0 $it" }
        )
        val nowSuspended = filterSuspended(released)
        val refixed = released.filter { it in nowSuspended }

        refixed.forEach {
            focusRepository.log(
                action = Constants.LOG_ACTION_REENFORCE,
                packageName = it,
                success = true,
                message = "检测到应用脱离暂停态，已再次冻结"
            )
        }
        (released - refixed.toSet()).forEach {
            focusRepository.log(
                action = Constants.LOG_ACTION_REENFORCE,
                packageName = it,
                success = false,
                message = "复查发现应用已恢复可启动状态，重新冻结失败"
            )
        }
        return refixed
    }

    /**
     * [packages] 中当前确实处于「已暂停」状态的子集。
     *
     * 读状态走公开 API [android.content.pm.PackageManager.isPackageSuspended]，不需要 root，
     * 也比解析 `dumpsys` / `pm list` 的文本输出可靠；
     * 写状态（suspend / unsuspend）没有公开 API，必须走 root shell。
     * 包已被卸载时会抛 NameNotFoundException，按「未暂停」处理。
     */
    private suspend fun filterSuspended(packages: List<String>): Set<String> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            packages.filter { packageName ->
                runCatching { pm.isPackageSuspended(packageName) }.getOrDefault(false)
            }.toSet()
        }
}