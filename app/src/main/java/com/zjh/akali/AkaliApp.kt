package com.zjh.akali

import android.app.Application
import android.content.Context
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.LessonFiles
import com.zjh.akali.data.LessonRepository
import com.zjh.akali.data.Prefs
import com.zjh.akali.elllo.ElloClient
import com.zjh.akali.importer.FolderImporter
import com.zjh.akali.playback.PlayerController

/** 手写的简单依赖容器，不用 Hilt */
class AppContainer(context: Context) {
    val prefs = Prefs(context)
    private val db = AppDatabase.create(context)
    private val files = LessonFiles(context.filesDir)
    val repository = LessonRepository(db.lessonDao(), files, ElloClient())
    val importer = FolderImporter(context, repository)
    val player = PlayerController(context, prefs)
}

class AkaliApp : Application() {
    val container by lazy { AppContainer(this) }
}
