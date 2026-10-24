package com.zjh.akali.ui.vocab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zjh.akali.AkaliApp
import com.zjh.akali.data.ChatMessage
import com.zjh.akali.data.MarkState
import com.zjh.akali.data.VocabLesson
import com.zjh.akali.playback.ClipPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class VocabViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AkaliApp).container
    private val repo = container.vocabRepository
    private val clips = ClipPlayer()

    /** null 表示还在加载 */
    val lessons: StateFlow<List<VocabLesson>?> = repo.lessons.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading

    val playingClip: StateFlow<String?> = clips.playing

    /** 正在让 AI 解释的词条（"主题/词条key"） */
    private val _explaining = MutableStateFlow<Set<String>>(emptySet())
    val explaining: StateFlow<Set<String>> = _explaining

    /** 词条标记，key 是 "主题/词条key"；没标记的（普通）不在里面。 */
    val marks: StateFlow<Map<String, MarkState>> = container.markRepository.marks
        .map { list -> list.filter { it.markState != MarkState.NONE }.associate { "${it.code}/${it.entryKey}" to it.markState } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _chats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    /** 各主题的聊天记录（只在 app 运行期间保留） */
    val chats: StateFlow<Map<String, List<ChatMessage>>> = _chats

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun lesson(code: String): Flow<VocabLesson?> = repo.observe(code)

    fun dir(code: String): File = repo.dir(code)

    fun download(code: String) {
        if (code in _downloading.value) return
        _downloading.update { it + code }
        viewModelScope.launch {
            try {
                repo.download(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(friendly(e))
            } finally {
                _downloading.update { it - code }
            }
        }
    }

    fun clear(code: String) {
        clips.stop()
        viewModelScope.launch {
            repo.clear(code)
            messageChannel.trySend("已清除这个主题的图片和发音，可随时重新下载")
        }
    }

    fun explain(code: String, key: String, force: Boolean = false) {
        val id = "$code/$key"
        if (id in _explaining.value) return
        _explaining.update { it + id }
        viewModelScope.launch {
            try {
                repo.explain(code, key, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(friendly(e))
            } finally {
                _explaining.update { it - id }
            }
        }
    }

    /** 先存到手机（界面马上变），再尽量同步到服务器；没网就留着，下次同步时补传。 */
    fun setMark(code: String, key: String, state: MarkState) {
        viewModelScope.launch {
            val marksRepo = container.markRepository
            marksRepo.set(code, key, state)
            try {
                container.prefs.marksRev = marksRepo.sync(container.prefs.marksRev)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 离线或服务器不可用：标记已经存在手机上，之后同步会补传
            }
        }
    }

    fun sendChat(code: String, text: String) {
        val say = text.trim()
        if (say.isEmpty() || _sending.value) return
        val history = _chats.value[code].orEmpty() + ChatMessage("user", say)
        _chats.update { it + (code to history) }
        _sending.value = true
        viewModelScope.launch {
            try {
                val reply = repo.chat(code, history)
                _chats.update { it + (code to history + ChatMessage("assistant", reply)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(friendly(e))
            } finally {
                _sending.value = false
            }
        }
    }

    fun clearChat(code: String) = _chats.update { it - code }

    /** 点词播放发音；正在听课文时先暂停课文。 */
    fun playClip(code: String, audio: String) {
        val player = container.player
        if (player.state.value.playWhenReady) player.togglePlay()
        clips.play("$code/$audio", File(repo.dir(code), audio))
    }

    override fun onCleared() {
        clips.stop()
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "网络不可用或连接超时，请检查网络后重试"
        is IOException -> e.message ?: "网络出错"
        else -> "出错了：${e.message ?: e.javaClass.simpleName}"
    }
}
