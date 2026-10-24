package com.zjh.akali.playback

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 播放一个词的发音短音频；同一时间只播一个，再点同一个就重播。 */
class ClipPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)

    /** 正在播放的那一条的 key（没有就是 null） */
    val playing: StateFlow<String?> = _playing

    fun play(key: String, file: File) {
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build(),
            )
            p.setDataSource(file.path)
            p.setOnCompletionListener { if (player === p) stop() }
            p.setOnErrorListener { _, _, _ -> if (player === p) stop(); true }
            p.prepare()
            player = p
            _playing.value = key
            p.start()
        } catch (e: Exception) {
            p.release()
            player = null
            _playing.value = null
        }
    }

    fun stop() {
        player?.release()
        player = null
        _playing.value = null
    }
}
