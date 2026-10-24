package com.zjh.akali.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.zjh.akali.data.PlayMode
import com.zjh.akali.data.Prefs
import com.zjh.akali.data.Sentence
import com.zjh.akali.data.Timestamps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class QueueItem(val lessonId: Long, val title: String, val file: File)

data class PlayerUiState(
    val connected: Boolean = false,
    val currentLessonId: Long? = null,
    /** 想播放（包括课间 2 秒停顿期间） */
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val mode: PlayMode = PlayMode.LOOP_ALL,
    val speed: Float = 1f,
    /** 单句循环是否开启 */
    val loopSentence: Boolean = false,
)

/** 界面这一侧的播放器遥控器：通过 MediaController 连接 PlaybackService。 */
class PlayerController(private val context: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState(mode = prefs.playMode, speed = prefs.speed))
    val state: StateFlow<PlayerUiState> = _state

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    /** 当前这课的逐句时间戳，由界面在课程变化时设置；没有则为空。 */
    private var sentences: List<Sentence> = emptyList()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            applyMode(c, prefs.playMode)
            c.setPlaybackSpeed(prefs.speed)
            publish(c)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(context))

        scope.launch {
            while (true) {
                delay(120)
                val c = controller ?: continue
                if (c.isPlaying) _state.update { it.copy(positionMs = c.currentPosition) }
            }
        }
    }

    private fun publish(p: Player) {
        val lessonId = p.currentMediaItem?.mediaId?.toLongOrNull()
        _state.update {
            val changed = lessonId != it.currentLessonId
            if (changed) sentences = emptyList() // 换课：循环已由服务清掉，句子等界面重新设置
            it.copy(
                loopSentence = it.loopSentence && !changed,
                connected = true,
                currentLessonId = lessonId,
                playWhenReady = p.playWhenReady && p.playbackState != Player.STATE_ENDED,
                isPlaying = p.isPlaying,
                positionMs = p.currentPosition,
                durationMs = p.duration.coerceAtLeast(0),
                speed = p.playbackParameters.speed,
            )
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.addLast(block)
    }

    private fun applyMode(c: MediaController, mode: PlayMode) {
        c.shuffleModeEnabled = mode == PlayMode.SHUFFLE
        c.repeatMode = when (mode) {
            PlayMode.LOOP_ONE -> Player.REPEAT_MODE_ONE
            PlayMode.LOOP_ALL, PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
        }
    }

    /** 用整个队列开始播放，从 startIndex 那课起。 */
    fun playQueue(items: List<QueueItem>, startIndex: Int) = withController { c ->
        val mediaItems = items.map {
            MediaItem.Builder()
                .setMediaId(it.lessonId.toString())
                .setUri(it.file.toUri())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist("ELLLO").build())
                .build()
        }
        c.setMediaItems(mediaItems, startIndex, 0)
        c.prepare()
        c.play()
    }

    fun togglePlay() = withController { c ->
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun seekTo(ms: Long) = withController {
        var target = ms
        if (_state.value.loopSentence) {
            // 循环开着时，拖到哪就循环哪一句；落在两句之间的空隙就取下一句
            var i = Timestamps.indexAt(sentences, ms)
            if (i >= 0 && ms > sentences[i].endMs && i + 1 < sentences.size) i++
            sentences.getOrNull(i)?.let { s ->
                target = if (ms !in s.startMs..s.endMs) s.startMs else ms
                sendLoop(it, s)
            }
        }
        it.seekTo(target)
        publish(it)
    }

    fun setSentences(list: List<Sentence>) {
        sentences = list
    }

    private fun sendLoop(c: MediaController, s: Sentence?) {
        val args = Bundle().apply {
            putLong(ARG_START_MS, s?.startMs ?: 0L)
            putLong(ARG_END_MS, s?.endMs ?: 0L)
        }
        c.sendCustomCommand(SessionCommand(COMMAND_LOOP_RANGE, Bundle.EMPTY), args)
    }

    /** 开/关单句循环：开启时循环当前正在播的这一句。 */
    fun toggleSentenceLoop() = withController { c ->
        if (_state.value.loopSentence) {
            sendLoop(c, null)
            _state.update { it.copy(loopSentence = false) }
            return@withController
        }
        val s = sentences.getOrNull(Timestamps.indexAt(sentences, c.currentPosition)) ?: return@withController
        if (c.currentPosition >= s.endMs - 100) c.seekTo(s.startMs)
        sendLoop(c, s)
        _state.update { it.copy(loopSentence = true) }
        publish(c)
    }

    /** 从第 index 句开头播放；循环开着就改循环这一句。 */
    fun playSentence(index: Int) = withController { c ->
        val s = sentences.getOrNull(index) ?: return@withController
        if (_state.value.loopSentence) sendLoop(c, s)
        c.seekTo(s.startMs)
        c.play()
        publish(c)
    }

    /** delta = -1 上一句（这句已播过一会儿则先回到本句开头），+1 下一句。 */
    fun stepSentence(delta: Int) = withController { c ->
        val i = Timestamps.indexAt(sentences, c.currentPosition)
        if (i < 0) return@withController
        val target = when {
            delta < 0 && c.currentPosition - sentences[i].startMs > 1500 -> i
            else -> (i + delta).coerceIn(0, sentences.lastIndex)
        }
        playSentence(target)
    }

    fun next() = withController {
        it.seekToNextMediaItem()
        it.play()
    }

    fun previous() = withController {
        it.seekToPreviousMediaItem()
        it.play()
    }

    fun setMode(mode: PlayMode) {
        prefs.playMode = mode
        _state.update { it.copy(mode = mode) }
        withController { applyMode(it, mode) }
    }

    fun setSpeed(speed: Float) {
        prefs.speed = speed
        _state.update { it.copy(speed = speed) }
        withController { it.setPlaybackSpeed(speed) }
    }

    /** 课程被清除/删除前调用：正在播放的要先停掉并移出队列，避免读到被删的文件。 */
    fun release(lessonIds: Set<Long>) = withController { c ->
        for (i in c.mediaItemCount - 1 downTo 0) {
            if (c.getMediaItemAt(i).mediaId.toLongOrNull() in lessonIds) c.removeMediaItem(i)
        }
        if (c.mediaItemCount == 0) c.stop()
    }
}
