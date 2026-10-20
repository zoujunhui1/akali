package com.zjh.akali.elllo

/** 课程标识，如 B2-036-Eulices-Dream-Job → level=B2, number=36, title="Eulices Dream Job" */
data class LessonSlug(val level: String, val number: Int, val title: String) {
    val code: String get() = "%s-%03d".format(level, number)
}

private val slugRegex = Regex("""^([ABC][12])-(\d{1,3})(?:[-_](.*))?$""", RegexOption.IGNORE_CASE)

/** 从文件夹名或页面文件名解析课程标识；不是课程名则返回 null。 */
fun parseSlug(name: String): LessonSlug? {
    val base = name.substringAfterLast('/').removeSuffix(".htm").removeSuffix(".html")
    val m = slugRegex.find(base.trim()) ?: return null
    val level = m.groupValues[1].uppercase()
    val number = m.groupValues[2].toInt()
    val rest = m.groupValues[3]
    val title = prettify(rest).ifBlank { "%s-%03d".format(level, number) }
    return LessonSlug(level, number, title)
}

/** 连字符换空格；全大写的名字转成首字母大写，其余保持原样。 */
private fun prettify(rest: String): String {
    val words = rest.split('-', '_').filter { it.isNotBlank() }
    val allCaps = rest == rest.uppercase()
    return words.joinToString(" ") { w ->
        if (allCaps) w.lowercase().replaceFirstChar { it.uppercase() } else w.replaceFirstChar { it.uppercase() }
    }
}
