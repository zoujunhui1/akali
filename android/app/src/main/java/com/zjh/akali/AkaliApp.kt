package com.zjh.akali

import android.app.Application
import android.content.Context
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.LessonFiles
import com.zjh.akali.data.LessonRepository
import com.zjh.akali.data.HttpServerApi
import com.zjh.akali.data.Prefs
import com.zjh.akali.data.ServerApi
import com.zjh.akali.playback.PlayerController

/** 手写的简单依赖容器，不用 Hilt */
class AppContainer(context: Context) {
    val prefs = Prefs(context)
    private val db = AppDatabase.create(context)
    private val files = LessonFiles(context.filesDir)
    /** 没有配置服务器（local.properties 里没写）时为 null，app 退回只用本地数据 */
    val serverApi: ServerApi? =
        if (BuildConfig.SERVER_URL.isNotBlank() && BuildConfig.APP_TOKEN.isNotBlank()) {
            HttpServerApi(BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN)
        } else {
            null
        }
    val repository = LessonRepository(db.lessonDao(), files, serverApi)
    val player = PlayerController(context, prefs)
}

class AkaliApp : Application() {
    val container by lazy { AppContainer(this) }
}
