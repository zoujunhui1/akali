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

    fun totalBytes(): Long = lessonsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
