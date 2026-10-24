package com.zjh.akali.data

import android.content.Context
import android.content.SharedPreferences

enum class SortOrder(val label: String) {
    NUMBER_ASC("编号升序"),
    NUMBER_DESC("编号降序"),
    ADDED_DESC("最近添加"),
}

enum class PlayMode(val label: String) {
    LOOP_ALL("列表循环"),
    LOOP_ONE("单课循环"),
    SEQUENTIAL("顺序播放"),
    SHUFFLE("随机播放");

    fun next() = entries[(ordinal + 1) % entries.size]
}

fun List<Lesson>.sortedFor(order: SortOrder): List<Lesson> = when (order) {
    SortOrder.NUMBER_ASC -> sortedWith(compareBy<Lesson>({ it.level }, { it.number }))
    SortOrder.NUMBER_DESC -> sortedWith(compareByDescending<Lesson> { it.level }.thenByDescending { it.number })
    SortOrder.ADDED_DESC -> sortedWith(compareByDescending<Lesson> { it.addedAt }.thenByDescending { it.id })
}

/** 轻量设置：排序、播放模式、倍速、文稿是否显示 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("akali", Context.MODE_PRIVATE)

    var sortOrder: SortOrder
        get() = enumOr(SortOrder.NUMBER_ASC, sp.getString("sort", null))
        set(v) = sp.edit().putString("sort", v.name).apply()

    var playMode: PlayMode
        get() = enumOr(PlayMode.LOOP_ALL, sp.getString("mode", null))
        set(v) = sp.edit().putString("mode", v.name).apply()

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()

    var showTranscript: Boolean
        get() = sp.getBoolean("showTranscript", true)
        set(v) = sp.edit().putBoolean("showTranscript", v).apply()

    /** 已同步到的服务器版本号 */
    var syncRev: Long
        get() = sp.getLong("syncRev", 0L)
        set(v) = sp.edit().putLong("syncRev", v).apply()

    /** 词汇主题已同步到的服务器版本号 */
    var vocabRev: Long
        get() = sp.getLong("vocabRev", 0L)
        set(v) = sp.edit().putLong("vocabRev", v).apply()

    /** 词条标记已同步到的服务器版本号 */
    var marksRev: Long
        get() = sp.getLong("marksRev", 0L)
        set(v) = sp.edit().putLong("marksRev", v).apply()

    /** 上次停在哪个底部页签（list / vocab / settings） */
    var lastTab: String
        get() = sp.getString("lastTab", null) ?: "list"
        set(v) = sp.edit().putString("lastTab", v).apply()

    /** 文章已同步到的服务器版本号 */
    var articleRev: Long
        get() = sp.getLong("articleRev", 0L)
        set(v) = sp.edit().putLong("articleRev", v).apply()

    /** 生词本已同步到的服务器版本号 */
    var notebookRev: Long
        get() = sp.getLong("notebookRev", 0L)
        set(v) = sp.edit().putLong("notebookRev", v).apply()

    /** 阅读字号档位（0..4） */
    var readingFont: Int
        get() = sp.getInt("readingFont", 2).coerceIn(0, 4)
        set(v) = sp.edit().putInt("readingFont", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0L)
        set(v) = sp.edit().putLong("lastSyncAt", v).apply()

    private inline fun <reified T : Enum<T>> enumOr(default: T, name: String?): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
