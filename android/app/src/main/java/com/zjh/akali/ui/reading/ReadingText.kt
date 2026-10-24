package com.zjh.akali.ui.reading

/** 一段文字：句子用空格连起来，[ranges] 是每个句子在 [text] 里的位置。 */
class ParagraphText(val text: String, val ranges: List<IntRange>) {
    fun sentenceIndexAt(offset: Int): Int = ranges.indexOfFirst { offset in it }.takeIf { it >= 0 } ?: ranges.lastIndex.coerceAtLeast(0)
}

/** 阅读页里的文字处理：拼段落、找点到的词、做挖空句子。都是纯函数，方便测试。 */
object ReadingText {
    fun join(sentences: List<String>): ParagraphText {
        val ranges = mutableListOf<IntRange>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty()) sb.append(' ')
            ranges.add(sb.length until sb.length + s.length)
            sb.append(s)
        }
        return ParagraphText(sb.toString(), ranges)
    }

    private fun isWordChar(text: String, i: Int): Boolean {
        val c = text[i]
        if (c.isLetterOrDigit()) return true
        // 词中间的撇号和连字符算词的一部分：don't、well-known；两头的不算
        if ((c == '\'' || c == '’' || c == '-') && i > 0 && i < text.lastIndex) {
            return text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
        }
        return false
    }

    /** 点到 [offset] 位置的词的范围；点在空格或标点上返回 null。 */
    fun wordAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var at = offset.coerceIn(0, text.lastIndex)
        if (!isWordChar(text, at)) {
            // 点在词尾紧挨着的标点上，也算点中了前面的词
            if (at > 0 && isWordChar(text, at - 1) && !text[at].isWhitespace()) at -= 1 else return null
        }
        var start = at
        var end = at
        while (start > 0 && isWordChar(text, start - 1)) start--
        while (end < text.lastIndex && isWordChar(text, end + 1)) end++
        return start..end
    }

    /** 选中的 [start, end) 恰好是一个词（两头的空格和标点不算）时返回这个词的范围，否则 null。 */
    fun singleWord(text: String, start: Int, end: Int): IntRange? {
        if (text.isEmpty() || start >= end) return null
        var from = start.coerceIn(0, text.length)
        var to = end.coerceIn(0, text.length)
        while (from < to && !text[from].isLetterOrDigit()) from++
        while (to > from && !text[to - 1].isLetterOrDigit()) to--
        if (from >= to) return null
        val word = wordAt(text, from) ?: return null
        return if (word.first == from && word.last == to - 1) word else null
    }

    /** 把句子里的 [word]（整词、不分大小写）换成下划线，生词本复习用。 */
    fun blank(sentence: String, word: String): String {
        if (word.isBlank()) return sentence
        val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return pattern.replace(sentence, "＿＿＿＿")
    }

    /** 估算阅读时间（分钟，至少 1）。按每分钟 200 词。 */
    fun minutes(wordCount: Int): Int = ((wordCount + 199) / 200).coerceAtLeast(1)

    /** 读到第几段（0 开始）换成百分比；[total] 是总段数。 */
    fun percent(progress: Int, total: Int): Int = if (total <= 1) 0 else (progress * 100 / (total - 1)).coerceIn(0, 100)
}
