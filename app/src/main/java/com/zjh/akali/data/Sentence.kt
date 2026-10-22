package com.zjh.akali.data

import org.json.JSONObject

/** 文稿中的一句话及它在音频里的起止时间。 */
data class Sentence(val paragraph: Int, val text: String, val startMs: Long, val endMs: Long)

object Timestamps {
    /** 解析课程文件夹里的 timestamps.json；格式不对时返回空列表。 */
    fun parse(json: String?): List<Sentence> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(json).getJSONArray("sentences")
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                Sentence(
                    paragraph = o.getInt("paragraph"),
                    text = o.getString("text"),
                    startMs = Math.round(o.getDouble("start") * 1000),
                    endMs = Math.round(o.getDouble("end") * 1000),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 当前位置所在的句子：最后一个起点不晚于 positionMs 的句子；还没到第一句时取第一句。 */
    fun indexAt(sentences: List<Sentence>, positionMs: Long): Int {
        if (sentences.isEmpty()) return -1
        val i = sentences.indexOfLast { it.startMs <= positionMs }
        return if (i < 0) 0 else i
    }
}
