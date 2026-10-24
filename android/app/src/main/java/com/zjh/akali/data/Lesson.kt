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
    /** 飞书词表导出的标记（vocab.json 原文），没有则为空 */
    val vocab: String? = null,
    /** 服务器上这课音频的校验值和大小（0 = 服务器上还没有音频） */
    val remoteAudioSha: String? = null,
    val remoteAudioSize: Long = 0,
) {
    val code: String get() = "%s-%03d".format(level, number)
}
