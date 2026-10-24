package com.zjh.akali.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException

/** 一次同步的结果：新增、更新、移除（服务器上已删除或音频被换掉）的课数，以及同步到的版本号。 */
data class SyncResult(val added: Int, val updated: Int, val removed: Int, val rev: Long)

class LessonRepository(
    private val dao: LessonDao,
    private val files: LessonFiles,
    private val server: ServerApi? = null,
) {
    val lessons: Flow<List<Lesson>> = dao.observeAll()

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
            val addedAt = r.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            when {
                r.deleted -> if (local != null) {
                    dao.delete(listOf(local.id))
                    files.deleteAll(local.id)
                }
                local == null -> {
                    dao.insert(
                        Lesson(
                            level = r.level, number = r.number, title = r.title, sourceUrl = r.sourceUrl,
                            addedAt = addedAt, transcript = r.transcript, audioDownloaded = false,
                            timestamps = r.timestamps, vocab = r.vocab, remoteAudioSha = r.audioSha, remoteAudioSize = r.audioSize,
                        ),
                    )
                    added++
                }
                else -> {
                    dao.updateFromServer(
                        local.id, r.title, r.sourceUrl, r.transcript, r.timestamps, r.vocab, r.audioSha, r.audioSize, addedAt,
                    )
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

    /** 从服务器下载一课的音频（第一次下载，或清除后重新下载）。 */
    suspend fun downloadAudio(id: Long, onProgress: (Float) -> Unit = {}) {
        val lesson = dao.get(id) ?: return
        val api = server ?: throw IOException("没有配置服务器")
        if (lesson.remoteAudioSize <= 0) throw IOException("服务器上这一课还没有音频")
        api.downloadAudio(lesson.code, lesson.remoteAudioSha, files.audio(id), onProgress)
        dao.setAudioDownloaded(id, true)
    }

    suspend fun clearAudio(ids: List<Long>) = withContext(Dispatchers.IO) {
        ids.forEach { files.deleteAudio(it) }
        ids.forEach { dao.setAudioDownloaded(it, false) }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { files.totalBytes() }

    fun audioFile(id: Long) = files.audio(id)
}
