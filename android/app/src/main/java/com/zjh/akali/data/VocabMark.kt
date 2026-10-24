package com.zjh.akali.data

import org.json.JSONObject

enum class MarkType { Word, Phrase, Sentence }

/** 飞书词表里的一条，落在第 [sentence] 句的 [text] 这段文字上。 */
data class VocabMark(
    val sentence: Int,
    val text: String,
    val type: MarkType,
    val en: String,
    val ipa: String,
    val zh: String,
    val example: String,
    val note: String,
    val star: Boolean,
    val mastery: String,
)

object VocabMarks {
    /** 解析课程文件夹里的 vocab.json；格式不对时返回空列表。 */
    fun parse(json: String?): List<VocabMark> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(json).getJSONArray("items")
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                VocabMark(
                    sentence = o.getInt("sentence"),
                    text = o.getString("text"),
                    type = when (o.getString("type")) {
                        "word" -> MarkType.Word
                        "phrase" -> MarkType.Phrase
                        else -> MarkType.Sentence
                    },
                    en = o.getString("en"),
                    ipa = o.optString("ipa"),
                    zh = o.optString("zh"),
                    example = o.optString("example"),
                    note = o.optString("note"),
                    star = o.optBoolean("star"),
                    mastery = o.optString("mastery"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 某一句上的标记，按在句中的位置排序；找不到文字的丢掉。 */
    fun forSentence(marks: List<VocabMark>, index: Int, text: String): List<Pair<IntRange, VocabMark>> =
        marks.filter { it.sentence == index }.mapNotNull { m ->
            if (m.type == MarkType.Sentence) return@mapNotNull (text.indices) to m
            val at = text.indexOf(m.text, ignoreCase = true)
            if (at < 0) null else (at until at + m.text.length) to m
        }.sortedBy { it.first.first }
}
