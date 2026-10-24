package com.zjh.akali.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 字典里的一个词条。[meaning] 是几行“词性 + 中文意思”，[tag] 是考试标签代码（如 "cet4 ielts"）。 */
data class DictEntry(
    val word: String, val phonetic: String, val meaning: String, val tag: String, val rank: Int,
    /** 英文释义，一行一条；旧版字典文件没有这一项，为空 */
    val definition: String = "",
    /** 词形变化，如“过去式 ran · 现在分词 running”；旧版字典文件没有，为空 */
    val forms: String = "",
) {
    val meaningLines: List<String> get() = meaning.split("\n").filter { it.isNotBlank() }
    val definitionLines: List<String> get() = definition.split("\n").filter { it.isNotBlank() }
    val tagLabels: List<String> get() = tag.split(" ").mapNotNull { TAG_LABELS[it] }

    companion object {
        private val TAG_LABELS = mapOf(
            "zk" to "中考", "gk" to "高考", "cet4" to "四级", "cet6" to "六级",
            "ky" to "考研", "toefl" to "托福", "ielts" to "雅思", "gre" to "GRE",
        )
    }
}

/**
 * 查词结果：[entry] 是查到的词条；[base] 是它的原形词条（点 running 时是 run），没有或相同时为 null；
 * [looked] 是实际查的词形（已去掉标点、转小写）。
 */
data class DictResult(val looked: String, val entry: DictEntry?, val base: DictEntry?)

object WordForms {
    /** 去掉两头的标点、统一撇号、转小写。 */
    fun normalize(token: String): String =
        token.replace('’', '\'').trim { !it.isLetter() }.lowercase()

    /**
     * 查字典时依次试的写法：原词、去掉所有格（John's）、再按常见词尾退回原形。
     * 字典自带的“词形 → 原形”表覆盖大多数情况，这里的规则只是兜底。
     */
    fun candidates(token: String): List<String> {
        val w = normalize(token)
        if (w.isEmpty()) return emptyList()
        val out = linkedSetOf(w)
        var base = w
        if (base.endsWith("'s")) base = base.dropLast(2).also { out.add(it) }
        else if (base.endsWith("s'")) base = base.dropLast(1).also { out.add(it) }
        fun add(s: String) { if (s.length >= 2) out.add(s) }
        when {
            base.endsWith("ies") -> add(base.dropLast(3) + "y")
            base.endsWith("es") -> { add(base.dropLast(2)); add(base.dropLast(1)) }
            base.endsWith("s") && !base.endsWith("ss") -> add(base.dropLast(1))
        }
        when {
            base.endsWith("ied") -> add(base.dropLast(3) + "y")
            base.endsWith("ed") -> {
                add(base.dropLast(2)); add(base.dropLast(1))
                if (base.length > 4 && base[base.length - 3] == base[base.length - 4]) add(base.dropLast(3))
            }
            base.endsWith("ing") -> {
                add(base.dropLast(3)); add(base.dropLast(3) + "e")
                if (base.length > 5 && base[base.length - 4] == base[base.length - 5]) add(base.dropLast(4))
            }
            base.endsWith("ly") -> add(base.dropLast(2))
            base.endsWith("er") -> { add(base.dropLast(2)); add(base.dropLast(1)) }
            base.endsWith("est") -> { add(base.dropLast(3)); add(base.dropLast(2)) }
        }
        return out.toList()
    }
}

/** 手机里的字典文件（SQLite，由 Mac 上的 dict_build.py 生成，从服务器下载）。 */
class DictionaryDb(private val db: SQLiteDatabase) {
    // 之前下载的旧字典文件没有英文释义和词形两列，照样能查，只是这两项为空
    private val extra: Boolean = db.rawQuery("PRAGMA table_info(words)", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(1) else null }.any { it == "definition" }
    }

    private fun word(w: String): DictEntry? =
        db.rawQuery(
            "SELECT word, phonetic, meaning, tag, rank" + (if (extra) ", definition, forms" else "") + " FROM words WHERE word = ?",
            arrayOf(w),
        ).use { c ->
            if (!c.moveToFirst()) null
            else DictEntry(
                c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getInt(4),
                if (extra) c.getString(5).orEmpty() else "", if (extra) c.getString(6).orEmpty() else "",
            )
        }

    private fun lemma(form: String): String? =
        db.rawQuery("SELECT lemma FROM forms WHERE form = ?", arrayOf(form)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun lookup(token: String): DictResult {
        val looked = WordForms.normalize(token)
        for (candidate in WordForms.candidates(token)) {
            val entry = word(candidate)
            val base = lemma(candidate)?.let { word(it) }?.takeIf { it.word != entry?.word }
            if (entry != null || base != null) {
                // 词形本身没有词条时（ran），直接给原形的词条
                return if (entry != null) DictResult(looked, entry, base) else DictResult(looked, base, null)
            }
        }
        return DictResult(looked, null, null)
    }

    fun close() = db.close()

    companion object {
        fun open(file: File) = DictionaryDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
    }
}

data class DictState(val ready: Boolean = false, val size: Long = 0, val downloading: Boolean = false, val progress: Float = 0f)

/** 管字典文件：下载（校验后才算数）、打开、查词。 */
class DictRepository(
    root: File,
    private val server: ServerApi? = null,
) {
    private val file = File(File(root, "dict"), "dict.db")
    private val lock = Mutex()
    private var open: DictionaryDb? = null
    private val _state = MutableStateFlow(read())
    val state: StateFlow<DictState> = _state

    private fun read() = if (file.exists()) DictState(ready = true, size = file.length()) else DictState()

    /** 查词；字典还没下载时返回 null。 */
    fun lookup(token: String): DictResult? {
        val db = open ?: synchronized(this) {
            open ?: if (file.exists()) runCatching { DictionaryDb.open(file) }.getOrNull().also { open = it } else null
        } ?: return null
        return db.lookup(token)
    }

    /** 从服务器下载字典。下载完才替换旧文件；校验值不对会抛 IOException 并且不留文件。 */
    suspend fun download() = lock.withLock {
        val api = server ?: throw IOException("没有配置服务器")
        val info = api.dictInfo() ?: throw IOException("服务器上还没有字典")
        _state.value = _state.value.copy(downloading = true, progress = 0f)
        try {
            val fresh = File(file.parentFile, "dict.new")
            api.downloadDict(info.sha256, fresh) { p -> _state.value = _state.value.copy(progress = p) }
            withContext(Dispatchers.IO) {
                synchronized(this@DictRepository) {
                    open?.close()
                    open = null
                    if (file.exists()) file.delete()
                    if (!fresh.renameTo(file)) throw IOException("保存字典失败")
                }
            }
        } finally {
            _state.value = read()
        }
    }

    fun delete() {
        synchronized(this) {
            open?.close()
            open = null
            file.delete()
        }
        _state.value = read()
    }
}
