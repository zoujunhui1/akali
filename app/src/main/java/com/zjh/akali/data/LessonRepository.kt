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

/** 一次同步的结果：新增、更新、移除（服务器上已删除或音频被换掉）的课数，以及同步到的版本号。 */
data class SyncResult(val added: Int, val updated: Int, val removed: Int, val rev: Long)

class LessonRepository(
    private val dao: LessonDao,
    private val files: LessonFiles,
    private val client: ElloClient,
    private val server: ServerApi? = null,
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

    /**
     * 从服务器同步：取 [since] 之后有变化的课。服务器上删掉的课在本地也删掉；音频被换过的课，
     * 本地旧音频清掉（需要时再点开下载）。[beforeRemove] 在真正删除前调用，用来让播放器放开这些课的文件。
     * 本地一课都没有时无论 [since] 是多少都从头同步。
     */
    suspend fun sync(since: Long, beforeRemove: suspend (List<Long>) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        val api = server ?: throw IOException("没有配置服务器")
        val payload = api.changes(if (dao.count() == 0) 0 else since)

        val gone = mutableListOf<Long>()
        val stale = mutableListOf<Long>()
        for (r in payload.lessons) {
            val local = dao.find(r.level, r.number) ?: continue
            if (r.deleted) gone += local.id
            else if (local.audioDownloaded && local.remoteAudioSha != null && r.audioSha != null && local.remoteAudioSha != r.audioSha) stale += local.id
        }
        if (gone.isNotEmpty() || stale.isNotEmpty()) beforeRemove(gone + stale)

        var added = 0
        var updated = 0
        for (r in payload.lessons) {
            val local = dao.find(r.level, r.number)
            when {
                r.deleted -> if (local != null) {
                    dao.delete(listOf(local.id))
                    files.deleteAll(local.id)
                }
                local == null -> {
                    dao.insert(
                        Lesson(
                            level = r.level, number = r.number, title = r.title, sourceUrl = r.sourceUrl,
                            addedAt = now(), transcript = r.transcript, audioDownloaded = false,
                            timestamps = r.timestamps, vocab = r.vocab, remoteAudioSha = r.audioSha, remoteAudioSize = r.audioSize,
                        ),
                    )
                    added++
                }
                else -> {
                    dao.updateFromServer(local.id, r.title, r.sourceUrl, r.transcript, r.timestamps, r.vocab, r.audioSha, r.audioSize)
                    if (local.id in stale) {
                        files.deleteAudio(local.id)
                        dao.setAudioDownloaded(local.id, false)
                    }
                    updated++
                }
            }
        }
        SyncResult(added, updated, gone.size + stale.size, payload.rev)
    }

    /** 下载（或清除后重新下载）一课的音频：服务器上有就从服务器下，没有再用来源链接去 ELLLO 下。 */
    suspend fun redownload(id: Long, onProgress: (Float) -> Unit = {}) {
        val lesson = dao.get(id) ?: return
        if (server != null && lesson.remoteAudioSize > 0) {
            server.downloadAudio(lesson.code, lesson.remoteAudioSha, files.audio(id), onProgress)
            dao.setAudioDownloaded(id, true)
            return
        }
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
     * 导入已有课程。同级别同编号已存在则不重复导入；但已存在的课如果还没有时间戳而这次带了就补上，
     * 词表标记（[vocab]）和已存的不一样就换成新的（飞书里的词会常常增加）。
     * [audio] 只在需要新建课程时才会被读取。
     */
    suspend fun insertImported(
        slug: LessonSlug,
        sourceUrl: String?,
        transcript: String,
        addedAt: Long,
        timestamps: String? = null,
        vocab: String? = null,
        audio: () -> InputStream,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val existing = dao.find(slug.level, slug.number)
        if (existing != null) {
            var changed = false
            if (timestamps != null && existing.timestamps == null) {
                dao.setTimestamps(existing.id, timestamps)
                changed = true
            }
            if (vocab != null && vocab != existing.vocab) {
                dao.setVocab(existing.id, vocab)
                changed = true
            }
            return@withContext if (changed) ImportOutcome.Updated else ImportOutcome.Skipped
        }
        val id = dao.insert(
            Lesson(
                level = slug.level, number = slug.number, title = slug.title, sourceUrl = sourceUrl,
                addedAt = addedAt, transcript = transcript, audioDownloaded = false, timestamps = timestamps, vocab = vocab,
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
