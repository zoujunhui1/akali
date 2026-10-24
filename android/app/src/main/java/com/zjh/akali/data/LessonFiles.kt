package com.zjh.akali.data

import java.io.File

/** 课程文件的位置：<root>/lessons/<id>/audio.mp3 */
class LessonFiles(private val root: File) {
    private val lessonsDir get() = File(root, "lessons")

    fun dir(id: Long) = File(lessonsDir, id.toString())

    fun audio(id: Long) = File(dir(id), "audio.mp3")

    fun deleteAudio(id: Long) {
        audio(id).delete()
    }

    fun deleteAll(id: Long) {
        dir(id).deleteRecursively()
    }

    /** 词汇主题的文件：<root>/vocab/<code>/（page-N.jpg、audio/…） */
    fun vocabDir(code: String) = File(File(root, "vocab"), code)

    fun deleteVocab(code: String) {
        vocabDir(code).deleteRecursively()
    }

    fun totalBytes(): Long =
        listOf(lessonsDir, File(root, "vocab")).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
}
