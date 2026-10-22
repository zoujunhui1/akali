package com.zjh.akali.data

import com.zjh.akali.elllo.ElloClient
import com.zjh.akali.elllo.ElloParseException
import com.zjh.akali.elllo.ElloParser
import com.zjh.akali.elllo.LessonSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

sealed interface AddResult {
    data class Added(val lesson: Lesson) : AddResult
    data class Redownloaded(val lesson: Lesson) : AddResult
    data class AlreadyExists(val lesson: Lesson) : AddResult
}

enum class ImportOutcome { Imported, Updated, Skipped }

class LessonRepository(
    private val dao: LessonDao,
    private val files: LessonFiles,
    private val client: ElloClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val lessons: Flow<List<Lesson>> = dao.observeAll()

    /** 粘贴链接添加课程。失败时抛 ElloParseException / IOException，信息可直接给用户看。 */
    suspend fun addFromUrl(input: String, onStatus: (String) -> Unit = {}, onProgress: (Float) -> Unit = {}): AddResult {
        val url = ElloParser.normalizeUrl(input)
        onStatus("正在读取课程页面…")
        val parsed = ElloParser.parse(client.fetchHtml(url), url)

        val existing = dao.find(parsed.slug.level, parsed.slug.number)
        if (existing != null && existing.audioDownloaded) return AddResult.AlreadyExists(existing)

        val lesson = existing ?: run {
            val id = dao.insert(
                Lesson(
                    level = parsed.slug.level, number = parsed.slug.number, title = parsed.slug.title,
                    sourceUrl = parsed.sourceUrl, addedAt = now(), transcript = parsed.transcript,
                    audioDownloaded = false,
                ),
            )
            dao.get(id) ?: throw IOException("保存课程失败")
        }
        onStatus("正在下载音频…")
        try {
            client.download(parsed.audioUrl, files.audio(lesson.id), onProgress)
        } catch (e: Exception) {
            if (existing == null) {
                dao.delete(listOf(lesson.id))
                files.deleteAll(lesson.id)
            }
            throw e
        }
        dao.setAudioDownloaded(lesson.id, true)
        if (existing != null && existing.sourceUrl == null) dao.setSourceUrl(existing.id, parsed.sourceUrl)
        val saved = dao.get(lesson.id)!!
        return if (existing == null) AddResult.Added(saved) else AddResult.Redownloaded(saved)
    }

    /** 清除过音频的课程重新下载；没有来源链接的无法下载。 */
    suspend fun redownload(id: Long, onProgress: (Float) -> Unit = {}) {
        val lesson = dao.get(id) ?: return
        val url = lesson.sourceUrl ?: throw IOException("这一课没有来源链接，无法重新下载")
        val parsed = ElloParser.parse(client.fetchHtml(url), url)
        client.download(parsed.audioUrl, files.audio(id), onProgress)
        dao.setAudioDownloaded(id, true)
    }

    suspend fun clearAudio(ids: List<Long>) = withContext(Dispatchers.IO) {
        ids.forEach { files.deleteAudio(it) }
        ids.forEach { dao.setAudioDownloaded(it, false) }
    }

    suspend fun deleteLessons(ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.delete(ids)
        ids.forEach { files.deleteAll(it) }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { files.totalBytes() }

    /**
     * 导入已有课程。同级别同编号已存在则不重复导入；但已存在的课如果还没有时间戳而这次带了，就补上。
     * [audio] 只在需要新建课程时才会被读取。
     */
    suspend fun insertImported(
        slug: LessonSlug,
        sourceUrl: String?,
        transcript: String,
        addedAt: Long,
        timestamps: String? = null,
        audio: () -> InputStream,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val existing = dao.find(slug.level, slug.number)
        if (existing != null) {
            if (timestamps != null && existing.timestamps == null) {
                dao.setTimestamps(existing.id, timestamps)
                return@withContext ImportOutcome.Updated
            }
            return@withContext ImportOutcome.Skipped
        }
        val id = dao.insert(
            Lesson(
                level = slug.level, number = slug.number, title = slug.title, sourceUrl = sourceUrl,
                addedAt = addedAt, transcript = transcript, audioDownloaded = false, timestamps = timestamps,
            ),
        )
        if (id < 0) return@withContext ImportOutcome.Skipped
        try {
            val dest = files.audio(id)
            dest.parentFile?.mkdirs()
            audio().use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (dest.length() == 0L) throw IOException("音频文件是空的")
        } catch (e: Exception) {
            dao.delete(listOf(id))
            files.deleteAll(id)
            throw e
        }
        dao.setAudioDownloaded(id, true)
        ImportOutcome.Imported
    }

    fun audioFile(id: Long) = files.audio(id)
}
