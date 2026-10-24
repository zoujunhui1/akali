package com.zjh.akali.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 服务器上的一课（同步用）。已删除的课只有编号，没有内容。 */
data class RemoteLesson(
    val level: String,
    val number: Int,
    val title: String,
    val sourceUrl: String?,
    val audioSize: Long,
    val audioSha: String?,
    val deleted: Boolean,
    val createdAt: Long,
    val transcript: String,
    val timestamps: String?,
    val vocab: String?,
) {
    val code: String get() = "%s-%03d".format(level, number)
}

data class SyncPayload(val rev: Long, val lessons: List<RemoteLesson>)

/** 服务器上的一个词汇主题（同步用）。已删除的只有编号。 */
data class RemoteVocab(
    val code: String,
    val unit: Int,
    val number: Int,
    val title: String,
    val pages: String,
    val entries: String,
    val bundleSize: Long,
    val bundleSha: String?,
    val deleted: Boolean,
    val createdAt: Long,
    val notes: String? = null,
)

data class VocabPayload(val rev: Long, val lessons: List<RemoteVocab>)

/** 服务器上的一个词条标记（state 是 "mastered"、"favorite" 或 "none"）。 */
data class RemoteMark(val code: String, val key: String, val state: String)

data class MarksPayload(val rev: Long, val marks: List<RemoteMark>)

interface ServerApi {
    /** 版本号 [since] 之后有变化的课（含已删除的）。 */
    suspend fun changes(since: Long): SyncPayload

    /** 下载一课的音频到 [dest]（先写临时文件，完成后改名）。校验值不对会抛 IOException 并且不留文件。 */
    suspend fun downloadAudio(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit = {})

    /** 词汇主题在版本号 [since] 之后的变化（含已删除的）。 */
    suspend fun vocabChanges(since: Long): VocabPayload

    /** 下载一个词汇主题的文件包（zip）到 [dest]，校验值不对会抛 IOException 并且不留文件。 */
    suspend fun downloadVocabBundle(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit = {})

    /** 让服务器用 AI 解释一个词条（第一次生成，之后服务器直接给存好的；[force] 重新生成）。 */
    suspend fun explainEntry(code: String, key: String, force: Boolean = false): EntryNote

    /** 和 AI 聊这个主题，返回 AI 的回复。 */
    suspend fun chat(code: String, messages: List<ChatMessage>): String

    /** 上传词条标记，返回服务器的新版本号。 */
    suspend fun putMarks(marks: List<RemoteMark>): Long

    /** 版本号 [since] 之后变化的标记（含取消的）。 */
    suspend fun marksChanges(since: Long): MarksPayload
}

object MarksJson {
    fun parse(json: String): MarksPayload {
        val root = JSONObject(json)
        val arr = root.getJSONArray("marks")
        return MarksPayload(
            root.getLong("rev"),
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                RemoteMark(o.getString("code"), o.getString("key"), o.getString("state"))
            },
        )
    }

    fun body(marks: List<RemoteMark>): String = JSONObject().put(
        "marks",
        JSONArray(marks.map { JSONObject().put("code", it.code).put("key", it.key).put("state", it.state) }),
    ).toString()
}

object SyncJson {
    fun parse(json: String): SyncPayload {
        val root = JSONObject(json)
        val arr = root.getJSONArray("lessons")
        val lessons = List(arr.length()) {
            val o = arr.getJSONObject(it)
            val audio = o.optJSONObject("audio")
            RemoteLesson(
                level = o.getString("level"),
                number = o.getInt("number"),
                title = o.getString("title"),
                sourceUrl = if (o.isNull("source_url")) null else o.getString("source_url"),
                audioSize = audio?.optLong("size") ?: 0L,
                audioSha = audio?.optString("sha256")?.takeIf { s -> s.isNotEmpty() },
                deleted = o.optBoolean("deleted"),
                createdAt = o.optLong("created_at", 0L),
                transcript = o.optString("transcript"),
                timestamps = o.optJSONObject("timestamps")?.toString(),
                vocab = o.optJSONObject("vocab")?.toString(),
            )
        }
        return SyncPayload(root.getLong("rev"), lessons)
    }
}

object VocabSyncJson {
    fun parse(json: String): VocabPayload {
        val root = JSONObject(json)
        val arr = root.getJSONArray("lessons")
        val lessons = List(arr.length()) {
            val o = arr.getJSONObject(it)
            val bundle = o.optJSONObject("bundle")
            RemoteVocab(
                code = o.getString("code"),
                unit = o.getInt("unit"),
                number = o.getInt("number"),
                title = o.getString("title"),
                pages = o.getJSONArray("pages").toString(),
                entries = o.getJSONArray("entries").toString(),
                bundleSize = bundle?.optLong("size") ?: 0L,
                bundleSha = bundle?.optString("sha256")?.takeIf { s -> s.isNotEmpty() },
                deleted = o.optBoolean("deleted"),
                createdAt = o.optLong("created_at", 0L),
                notes = o.optJSONObject("notes")?.toString(),
            )
        }
        return VocabPayload(root.getLong("rev"), lessons)
    }
}

class HttpServerApi(
    baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val downloader: Downloader = Downloader(),
) : ServerApi {
    private val base = baseUrl.trimEnd('/')
    private val auth get() = mapOf("Authorization" to "Bearer $token")

    override suspend fun changes(since: Long): SyncPayload = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/v1/lessons?since=$since&content=1").header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("服务器口令不对，请检查构建时配置的口令")
            if (!resp.isSuccessful) throw IOException("同步失败（服务器返回 ${resp.code}）")
            SyncJson.parse(resp.body.string())
        }
    }

    override suspend fun downloadAudio(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit) {
        downloader.download("$base/api/v1/lessons/$code/audio", dest, onProgress, auth)
        if (sha256 != null && sha256(dest) != sha256) {
            dest.delete()
            throw IOException("下载的音频校验不通过，请重试")
        }
    }

    override suspend fun vocabChanges(since: Long): VocabPayload = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/v1/vocab?since=$since").header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("服务器口令不对，请检查构建时配置的口令")
            if (!resp.isSuccessful) throw IOException("同步词汇失败（服务器返回 ${resp.code}）")
            VocabSyncJson.parse(resp.body.string())
        }
    }

    override suspend fun downloadVocabBundle(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit) {
        downloader.download("$base/api/v1/vocab/$code/bundle", dest, onProgress, auth)
        if (sha256 != null && sha256(dest) != sha256) {
            dest.delete()
            throw IOException("下载的文件校验不通过，请重试")
        }
    }

    override suspend fun explainEntry(code: String, key: String, force: Boolean): EntryNote = withContext(Dispatchers.IO) {
        val url = "$base/api/v1/vocab/$code/entries/$key/explain" + if (force) "?force=1" else ""
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").post("".toRequestBody()).build()
        VocabJson.note(JSONObject(aiCall(req)))
    }

    override suspend fun chat(code: String, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put(
            "messages",
            JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }),
        ).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/api/v1/vocab/$code/chat").header("Authorization", "Bearer $token").post(body).build()
        JSONObject(aiCall(req)).getString("reply")
    }

    override suspend fun putMarks(marks: List<RemoteMark>): Long = withContext(Dispatchers.IO) {
        val body = MarksJson.body(marks).toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/api/v1/marks").header("Authorization", "Bearer $token").put(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("上传标记失败（服务器返回 ${resp.code}）")
            JSONObject(resp.body.string()).getLong("rev")
        }
    }

    override suspend fun marksChanges(since: Long): MarksPayload = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/v1/marks?since=$since").header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("同步标记失败（服务器返回 ${resp.code}）")
            MarksJson.parse(resp.body.string())
        }
    }

    /** AI 请求比较慢，等久一点；服务器出错时它的错误信息本来就是写给用户看的。 */
    private fun aiCall(req: Request): String {
        val slow = client.newBuilder().readTimeout(110, TimeUnit.SECONDS).build()
        slow.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (resp.isSuccessful) return text
            val message = runCatching { JSONObject(text).getString("error") }.getOrNull()
            throw IOException(message ?: "AI 请求失败（服务器返回 ${resp.code}）")
        }
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
