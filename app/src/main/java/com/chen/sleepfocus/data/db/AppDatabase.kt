package com.chen.sleepfocus.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * List<String> 与 TEXT 的互转。Android 包名只允许 [a-zA-Z0-9_.]，不含逗号，故用逗号分隔即可。
 */
class StringListConverter {
    @TypeConverter
    fun fromList(value: List<String>?): String = value?.joinToString(separator = ",") ?: ""

    @TypeConverter
    fun toList(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else value.split(",")
}

/**
 * 日志已改为写入文件（见 data/log/LogFile），因此这里只剩专注表。
 * 版本 2 相比版本 1 去掉了 op_log 表，靠 fallbackToDestructiveMigration 重建。
 */
@Database(
    entities = [SessionEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
}