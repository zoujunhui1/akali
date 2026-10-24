package com.zjh.akali.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 下载文件到本地：先写临时文件，完整后再改名，避免留下半截文件。 */
class Downloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit = {},
        headers: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载音频失败（HTTP ${resp.code}）")
                val total = resp.body.contentLength()
                resp.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("下载到的音频是空的")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("保存音频失败")
        } finally {
            tmp.delete()
        }
    }
}
