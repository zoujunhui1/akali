package com.zjh.akali.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** 一次词汇同步的结果：新增、更新（含文件被换掉）、移除的主题数，以及同步到的版本号。 */
data class VocabSyncResult(val added: Int, val updated: Int, val removed: Int, val rev: Long)

class VocabRepository(
    private val dao: VocabDao,
    private val files: LessonFiles,
    private val server: ServerApi? = null,
) {
    val lessons: Flow<List<VocabLesson>> = dao.observeAll()

    fun observe(code: String): Flow<VocabLesson?> = dao.observe(code)

    /** 从服务器同步词汇主题。服务器上删掉的在本地也删；文件被换过的，本地旧文件清掉，需要时再点开下载。 */
    suspend fun sync(since: Long): VocabSyncResult = withContext(Dispatchers.IO) {
        val api = server ?: throw IOException("没有配置服务器")
        val payload = api.vocabChanges(if (dao.count() == 0) 0 else since)
        var added = 0
        var updated = 0
        var removed = 0
        for (r in payload.lessons) {
            val local = dao.find(r.code)
            when {
                r.deleted -> if (local != null) {
                    dao.delete(r.code)
                    files.deleteVocab(r.code)
                    removed++
                }
                else -> {
                    val replaced = local != null && local.downloaded && local.bundleSha != r.bundleSha
                    if (replaced) files.deleteVocab(r.code)
                    dao.upsert(
                        VocabLesson(
                            code = r.code, unit = r.unit, number = r.number, title = r.title, pages = r.pages, entries = r.entries,
                            bundleSha = r.bundleSha, bundleSize = r.bundleSize,
                            downloaded = local != null && local.downloaded && !replaced,
                            addedAt = r.createdAt.takeIf { it > 0 } ?: local?.addedAt ?: System.currentTimeMillis(),
                            notes = r.notes,
                        ),
                    )
                    if (local == null) added++ else updated++
                }
            }
        }
        VocabSyncResult(added, updated, removed, payload.rev)
    }

    /** 下载并解开一个主题的文件包（页面图片和发音）。 */
    suspend fun download(code: String, onProgress: (Float) -> Unit = {}) {
        val lesson = dao.find(code) ?: return
        val api = server ?: throw IOException("没有配置服务器")
        if (lesson.bundleSize <= 0) throw IOException("服务器上这个主题还没有文件")
        val zip = File(files.vocabDir(code).parentFile, "$code.zip")
        api.downloadVocabBundle(code, lesson.bundleSha, zip, onProgress)
        withContext(Dispatchers.IO) {
            try {
                files.deleteVocab(code)
                unzip(zip, files.vocabDir(code))
            } catch (e: Exception) {
                files.deleteVocab(code)
                throw if (e is IOException) e else IOException("文件包解压失败", e)
            } finally {
                zip.delete()
            }
        }
        dao.setDownloaded(code, true)
    }

    /** 让 AI 解释一个词条，结果存到本地，界面马上能看到。 */
    suspend fun explain(code: String, key: String, force: Boolean = false): EntryNote {
        val api = server ?: throw IOException("没有配置服务器")
        val note = api.explainEntry(code, key, force)
        dao.find(code)?.let { dao.setNotes(code, VocabJson.putNote(it.notes, key, note)) }
        return note
    }

    suspend fun chat(code: String, messages: List<ChatMessage>): String {
        val api = server ?: throw IOException("没有配置服务器")
        return api.chat(code, messages)
    }

    suspend fun clear(code: String) = withContext(Dispatchers.IO) {
        files.deleteVocab(code)
        dao.setDownloaded(code, false)
    }

    fun dir(code: String): File = files.vocabDir(code)

    companion object {
        /** 解压到 [dest]；条目路径跑到 [dest] 外面（zip slip）就拒绝。 */
        fun unzip(zip: File, dest: File) {
            dest.mkdirs()
            val root = dest.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val out = File(root, entry.name).canonicalFile
                    if (out != root && !out.path.startsWith(root.path + File.separator)) throw IOException("文件包里有不安全的路径")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
        }
    }
}
