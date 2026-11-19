package com.chen.sleepfocus.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
 * 版本 2 相比版本 1 去掉了 op_log 表，靠 fallbackToDestructiveMigration 重建；
 * 版本 3 增加 sourcePlanId 列，保留已有专注记录。
 */
@Database(
    entities = [SessionEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        /** 2 -> 3：标记专注是否由某条睡眠计划触发，供重叠计划接管与到点解冻判断使用 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_session ADD COLUMN sourcePlanId INTEGER")
            }
        }
    }
}