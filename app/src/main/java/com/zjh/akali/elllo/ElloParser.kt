package com.zjh.akali.elllo

import org.jsoup.Jsoup

data class ParsedLesson(
    val slug: LessonSlug,
    val audioUrl: String,
    val transcript: String,
    val sourceUrl: String,
)

class ElloParseException(message: String) : Exception(message)

object ElloParser {
    private val urlRegex = Regex("""https?://[^\s]+""")

    /** 从用户粘贴的文字里取出 ELLLO 链接并整理；不是 ELLLO 链接时抛异常。 */
    fun normalizeUrl(input: String): String {
        val text = input.trim()
        val raw = urlRegex.find(text)?.value
            ?: if (text.startsWith("elllo.org")) "https://$text" else throw ElloParseException("没有找到链接")
        val clean = raw.substringBefore('#').substringBefore('?').trimEnd('/', ')', '。', '，')
        val host = clean.substringAfter("://").substringBefore('/').lowercase()
        if (host != "elllo.org" && host != "www.elllo.org") throw ElloParseException("只支持 elllo.org 的课程链接")
        if (parseSlug(clean) == null) throw ElloParseException("这不是课程页面链接（形如 …/video/C1ADV/C1-033-….htm）")
        return clean.replaceFirst("http://", "https://").replace("://www.", "://")
    }

    fun parse(html: String, pageUrl: String): ParsedLesson {
        val slug = parseSlug(pageUrl) ?: throw ElloParseException("无法从链接识别课程编号")
        val doc = Jsoup.parse(html, pageUrl)

        val audioUrl = doc.select("audio source[src], audio[src]")
            .map { it.absUrl("src") }
            .firstOrNull { it.contains("/Audio/", ignoreCase = true) && it.endsWith(".mp3", ignoreCase = true) }
            ?: throw ElloParseException("页面里没有找到主音频，ELLLO 可能改版了")

        val transcript = doc.select("#transcript p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (transcript.isEmpty()) throw ElloParseException("页面里没有找到文稿，ELLLO 可能改版了")

        return ParsedLesson(slug, audioUrl, transcript, pageUrl)
    }
}
