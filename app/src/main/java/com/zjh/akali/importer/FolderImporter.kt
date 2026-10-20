package com.zjh.akali.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.zjh.akali.data.LessonRepository
import com.zjh.akali.elllo.parseSlug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportResult(val imported: Int, val skipped: Int, val failed: List<String>)

/**
 * 导入已有课程文件夹。每课一个子文件夹：一个 .mp3（取第一个，不依赖文件名）、
 * transcript.txt、可选的 url.txt。mp4/srt/vtt 等其他文件忽略。
 */
class FolderImporter(private val context: Context, private val repo: LessonRepository) {

    suspend fun import(tree: Uri, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: return ImportResult(0, 0, listOf("无法打开所选文件夹"))
        return import(root, onProgress)
    }

    suspend fun import(root: DocumentFile, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult =
        withContext(Dispatchers.IO) {
            val top = root.listFiles()
            val dirs = if (top.any { it.isFile && it.name.isMp3() }) listOf(root) else top.filter { it.isDirectory }.sortedBy { it.name }
            var imported = 0
            var skipped = 0
            val failed = mutableListOf<String>()
            dirs.forEachIndexed { i, dir ->
                val name = dir.name ?: "?"
                try {
                    if (importOne(dir, name)) imported++ else skipped++
                } catch (e: Exception) {
                    failed += "$name：${e.message ?: e.javaClass.simpleName}"
                }
                onProgress(i + 1, dirs.size)
            }
            ImportResult(imported, skipped, failed)
        }

    private suspend fun importOne(dir: DocumentFile, name: String): Boolean {
        val slug = parseSlug(name) ?: throw IllegalArgumentException("文件夹名不是课程编号")
        val children = dir.listFiles()
        val mp3 = children.firstOrNull { it.isFile && it.name.isMp3() } ?: throw IllegalArgumentException("没有 mp3 文件")
        val transcript = children.firstOrNull { it.name.equals("transcript.txt", ignoreCase = true) }?.let(::readText).orEmpty()
        val url = children.firstOrNull { it.name.equals("url.txt", ignoreCase = true) }?.let(::readText)?.trim()?.takeIf { it.isNotEmpty() }
        val addedAt = mp3.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val input = context.contentResolver.openInputStream(mp3.uri) ?: throw IllegalStateException("读不到音频文件")
        return repo.insertImported(slug, url, transcript.trim(), addedAt, input)
    }

    private fun readText(f: DocumentFile): String =
        context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

    private fun String?.isMp3() = this?.endsWith(".mp3", ignoreCase = true) == true
}
