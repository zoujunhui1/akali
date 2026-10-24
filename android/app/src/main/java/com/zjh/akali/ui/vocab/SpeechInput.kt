package com.zjh.akali.ui.vocab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private const val ATTEMPTS = 4
private const val MIN_HOLD_MS = 500L

/** 语音服务报“不支持这种语言(12)”或“语言包不可用(13)”时，换下一种语言设置再试；用户已取消或试完了就不再试。 */
internal fun shouldTryNextLanguage(error: Int, attempt: Int, discarded: Boolean): Boolean =
    !discarded && (error == 12 || error == 13) && attempt + 1 < ATTEMPTS

/**
 * 按住说话：按下开始听，松开后把识别出的文字交给 [onText]（调用方直接发送）；
 * 手指滑出按钮上方松开则取消。中文和英文都能识别。
 */
class SpeechInput internal constructor(private val context: Context) {
    var listening by mutableStateOf(false)
        private set

    /** 手指已经滑到取消区，现在松手会取消 */
    var willCancel by mutableStateOf(false)

    /** 已松手，在等识别结果 */
    var processing by mutableStateOf(false)
        private set

    internal var onText: (String) -> Unit = {}
    internal var onMessage: (String) -> Unit = {}
    internal var askPermission: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null
    private var pressedAt = 0L
    private var discard = false

    // 这台手机的语音服务不一定接受某种语言设置，记住哪一种能用，下次直接从它开始
    private var firstAttempt = 0

    fun press() {
        if (listening || processing) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission()
            return
        }
        discard = false
        pressedAt = SystemClock.elapsedRealtime()
        begin(firstAttempt)
    }

    fun release(cancel: Boolean) {
        willCancel = false
        if (!listening) return
        if (cancel) {
            discard = true
            destroy()
        } else if (SystemClock.elapsedRealtime() - pressedAt < MIN_HOLD_MS) {
            discard = true
            destroy()
            onMessage("按住麦克风说话，说完再松开")
        } else {
            processing = true
            recognizer?.stopListening()
        }
    }

    internal fun begin(attempt: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            destroy()
            onMessage("这台手机没有语音识别服务，请用键盘上的麦克风")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                val dropped = discard
                firstAttempt = attempt
                destroy()
                if (dropped) return
                if (text.isNullOrBlank()) onMessage("没听清，再试一次") else onText(text)
            }

            override fun onError(error: Int) {
                if (shouldTryNextLanguage(error, attempt, discard)) {
                    firstAttempt = attempt + 1
                    begin(attempt + 1)
                    return
                }
                val dropped = discard
                destroy()
                if (!dropped) onMessage(describe(error))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            when (attempt) {
                0 -> if (Build.VERSION.SDK_INT >= 33) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-US", "zh-CN"))
                }
                1 -> {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("zh-CN"))
                }
                2 -> {} // 用手机的系统语言
                else -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            }
        }
        listening = true
        r.startListening(intent)
    }

    internal fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listening = false
        processing = false
        willCancel = false
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再试一次"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要网络，请检查网络后重试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别服务没有麦克风权限：请到 设置 → 应用 → Google 语音服务 → 权限，允许麦克风"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，稍后再试"
        else -> "语音识别出错了（$error）"
    }
}

@Composable
fun rememberSpeechInput(onText: (String) -> Unit, onMessage: (String) -> Unit): SpeechInput {
    val context = LocalContext.current
    val input = remember { SpeechInput(context) }
    input.onText = onText
    input.onMessage = onMessage
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        input.onMessage(if (granted) "可以了，按住麦克风说话" else "需要录音权限才能语音输入")
    }
    input.askPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(Unit) { onDispose { input.destroy() } }
    return input
}
