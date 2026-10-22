package com.zjh.akali.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lessons", indices = [Index(value = ["level", "number"], unique = true)])
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: String,
    val number: Int,
    val title: String,
    /** 导入的课程如果没有 url.txt 则为空 */
    val sourceUrl: String?,
    val addedAt: Long,
    val transcript: String,
    val audioDownloaded: Boolean,
    /** 逐句时间戳（timestamps.json 原文），没有则为空 */
    val timestamps: String? = null,
) {
    val code: String get() = "%s-%03d".format(level, number)
}
