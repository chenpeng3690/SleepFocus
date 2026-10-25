package com.chen.sleepfocus.data.repo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.chen.sleepfocus.data.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * 读取已安装应用列表。
 *
 * 只枚举「有启动图标」的应用（ACTION_MAIN + CATEGORY_LAUNCHER），
 * 避免把 systemui / 输入法等无界面的系统组件暴露给用户去冻结而导致设备不可用。
 */
class AppRepository(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    val selfPackageName: String = context.packageName

    suspend fun loadInstalledApps(includeSystem: Boolean): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }

            val result = LinkedHashMap<String, AppInfo>()
            resolveInfos.forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val appInfo = activityInfo.applicationInfo ?: return@forEach
                val pkg = appInfo.packageName

                if (pkg == selfPackageName) return@forEach
                if (!result.containsKey(pkg)) {
                    val isSystem = appInfo.isSystemApp()
                    if (!includeSystem && isSystem) return@forEach
                    result[pkg] = AppInfo(
                        packageName = pkg,
                        label = appInfo.loadLabel(packageManager).toString(),
                        isSystem = isSystem,
                        icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull()
                    )
                }
            }

            val collator = Collator.getInstance(Locale.CHINA)
            result.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
        }

    /** 取单个包名的应用名，取不到时退回包名 */
    suspend fun loadLabel(packageName: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    /**
     * 判断应用是否仍安装在本机。
     *
     * 用于清理「已选择」里被用户卸载掉的包名：这些包名留在本地会虚增已选数量，
     * 冻结时还会对不存在的包执行命令并提示失败。默认查询不含已卸载但仍保留数据的包。
     */
    suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.isSuccess
    }

    suspend fun loadIcon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationIcon(info)
        }.getOrNull()
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}