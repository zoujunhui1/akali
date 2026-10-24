package com.zjh.akali.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 用手机自带的语音朗读英文（点词、点句子的喇叭）。第一次用才初始化。 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null

    fun speak(text: String, rate: Float = 0.9f) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ready) {
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "akali")
            return
        }
        waiting = text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    ready = true
                    waiting?.let { speak(it, rate) }
                    waiting = null
                }
            }
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
