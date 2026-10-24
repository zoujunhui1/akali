package com.zjh.akali.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/** 图解词典的一个主题（如 V01-17 Colors）。图片和发音在下载后放在本地文件夹里。 */
@Entity(tableName = "vocab_lessons")
data class VocabLesson(
    @PrimaryKey val code: String,
    val unit: Int,
    val number: Int,
    val title: String,
    /** 页码列表的 JSON，如 [31] */
    val pages: String,
    /** 词条列表的 JSON（label、text、page、audio） */
    val entries: String,
    /** 服务器上这一课文件包的校验值和大小（0 = 服务器上还没有文件） */
    val bundleSha: String?,
    val bundleSize: Long,
    val downloaded: Boolean,
    val addedAt: Long,
    /** AI 生成的词条释义（key -> 释义）的 JSON，还没有就是空 */
    val notes: String? = null,
) {
    val pageList: List<Int> get() = VocabJson.pages(pages)
    val entryList: List<VocabEntry> get() = VocabJson.entries(entries)
    val noteMap: Map<String, EntryNote> get() = VocabJson.notes(notes)
}

data class VocabEntry(val label: String, val text: String, val page: Int, val audio: String) {
    /** 词条在服务器上的名字，也是发音文件的名字：页码-编号 */
    val key: String get() = "$page-$label"
}

/** 一个词条的 AI 释义：中文意思、英文解释、例句和例句翻译 */
data class EntryNote(val zh: String, val definition: String, val example: String, val exampleZh: String)

data class ChatMessage(val role: String, val content: String)

object VocabJson {
    fun pages(json: String): List<Int> = JSONArray(json).let { a -> List(a.length()) { a.getInt(it) } }

    fun entries(json: String): List<VocabEntry> = JSONArray(json).let { a ->
        List(a.length()) {
            val o = a.getJSONObject(it)
            VocabEntry(o.getString("label"), o.getString("text"), o.getInt("page"), o.getString("audio"))
        }
    }

    fun note(o: JSONObject) = EntryNote(o.getString("zh"), o.getString("definition"), o.getString("example"), o.getString("example_zh"))

    fun notes(json: String?): Map<String, EntryNote> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().mapNotNull { k -> runCatching { k to note(o.getJSONObject(k)) }.getOrNull() }.toMap()
    }

    /** 把一条新释义并进已有的 notes JSON。 */
    fun putNote(json: String?, key: String, n: EntryNote): String {
        val o = if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
        o.put(key, JSONObject().put("zh", n.zh).put("definition", n.definition).put("example", n.example).put("example_zh", n.exampleZh))
        return o.toString()
    }
}
