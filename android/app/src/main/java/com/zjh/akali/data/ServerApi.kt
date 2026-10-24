package com.zjh.akali.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

interface ServerApi {
    /** 版本号 [since] 之后有变化的课（含已删除的）。 */
    suspend fun changes(since: Long): SyncPayload

    /** 下载一课的音频到 [dest]（先写临时文件，完成后改名）。校验值不对会抛 IOException 并且不留文件。 */
    suspend fun downloadAudio(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit = {})
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
