package com.zjh.akali.data

import androidx.room.Entity

/** 学习者给词条打的标记：普通、已收藏（不熟，要复习）、已会。 */
enum class MarkState(val wire: String, val label: String) {
    NONE("none", "普通"),
    FAVORITE("favorite", "已收藏"),
    MASTERED("mastered", "已会");

    companion object {
        fun fromWire(s: String): MarkState = entries.firstOrNull { it.wire == s } ?: NONE
    }
}

/**
 * 一个词条的标记。[pending] 表示还没上传到服务器（离线时先存在手机上，联网后补传）。
 * 标记回到“普通”也留一行，这样才能把“取消标记”同步给服务器和别的设备。
 */
@Entity(tableName = "entry_marks", primaryKeys = ["code", "entryKey"])
data class EntryMark(
    val code: String,
    val entryKey: String,
    val state: String,
    val pending: Boolean,
) {
    val markState: MarkState get() = MarkState.fromWire(state)
}
