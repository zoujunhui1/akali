package com.zjh.akali.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zjh.akali.AkaliApp
import com.zjh.akali.data.Lesson
import com.zjh.akali.data.SortOrder
import com.zjh.akali.data.sortedFor
import com.zjh.akali.playback.QueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Running : SyncUiState
    data class Done(val message: String) : SyncUiState
    data class Error(val message: String) : SyncUiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AkaliApp).container
    private val repo = container.repository
    private val vocabRepo = container.vocabRepository
    private val prefs = container.prefs

    var lastTab: String
        get() = prefs.lastTab
        set(v) { prefs.lastTab = v }
    val player = container.player

    private val sortOrder = MutableStateFlow(prefs.sortOrder)
    val sort: StateFlow<SortOrder> = sortOrder

    /** null 表示还在加载 */
    val lessons: StateFlow<List<Lesson>?> =
        combine(repo.lessons, sortOrder) { list, order -> list.sortedFor(order) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _downloading = MutableStateFlow<Set<Long>>(emptySet())
    val downloading: StateFlow<Set<Long>> = _downloading

    private val _storage = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storage

    private val _showTranscript = MutableStateFlow(prefs.showTranscript)
    val showTranscript: StateFlow<Boolean> = _showTranscript

    val serverConfigured: Boolean = container.serverApi != null

    private val _sync = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState: StateFlow<SyncUiState> = _sync

    private val _lastSyncAt = MutableStateFlow(prefs.lastSyncAt)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        if (serverConfigured) sync(auto = true)
    }

    // ---- 同步 ----

    /** 从服务器同步课程文字、时间戳、词表。自动同步（打开 app 时）失败不打扰用户，手动同步会提示原因。 */
    fun sync(auto: Boolean = false) {
        if (!serverConfigured || _sync.value is SyncUiState.Running) return
        _sync.value = SyncUiState.Running
        viewModelScope.launch {
            try {
                val r = repo.sync(prefs.syncRev) { ids -> player.release(ids.toSet()) }
                prefs.syncRev = r.rev
                val v = vocabRepo.sync(prefs.vocabRev)
                prefs.vocabRev = v.rev
                prefs.marksRev = container.markRepository.sync(prefs.marksRev)
                val a = container.articleRepository.sync(prefs.articleRev)
                prefs.articleRev = a.rev
                prefs.notebookRev = container.notebookRepository.sync(prefs.notebookRev)
                prefs.lastSyncAt = System.currentTimeMillis()
                _lastSyncAt.value = prefs.lastSyncAt
                refreshStorage()
                val changed = r.added + r.updated + r.removed + v.added + v.updated + v.removed + a.added + a.updated + a.removed
                val text = if (changed == 0) "已是最新" else "已同步：新增 ${r.added} 课，更新 ${r.updated} 课" +
                    (if (r.removed > 0) "，移除 ${r.removed} 课" else "") +
                    (if (v.added + v.updated + v.removed > 0) "；词汇主题 ${v.added + v.updated + v.removed} 个有变化" else "") +
                    (if (a.added + a.updated + a.removed > 0) "；文章 ${a.added + a.updated + a.removed} 篇有变化" else "")
                _sync.value = SyncUiState.Done(text)
                if (!auto || changed > 0) say(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = friendly(e)
                _sync.value = SyncUiState.Error(text)
                if (!auto) say(text)
            }
        }
    }

    // ---- 排序 / 选择 ----

    fun setSort(order: SortOrder) {
        prefs.sortOrder = order
        sortOrder.value = order
    }

    fun toggleSelect(id: Long) = _selection.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun toggleTranscript() {
        val v = !_showTranscript.value
        prefs.showTranscript = v
        _showTranscript.value = v
    }

    // ---- 播放 ----

    /** 返回 true 表示已经开始播放，界面可以进入播放页。 */
    fun play(lesson: Lesson): Boolean {
        if (!lesson.audioDownloaded) {
            if (lesson.remoteAudioSize > 0) download(lesson.id) else say("服务器上这一课还没有音频")
            return false
        }
        if (player.state.value.currentLessonId == lesson.id) return true
        val queue = lessons.value.orEmpty().filter { it.audioDownloaded }
        val index = queue.indexOfFirst { it.id == lesson.id }
        if (index < 0) return false
        player.playQueue(queue.map { QueueItem(it.id, "${it.code} ${it.title}", repo.audioFile(it.id)) }, index)
        return true
    }

    // ---- 下载 ----

    fun download(id: Long) {
        if (id in _downloading.value) return
        _downloading.update { it + id }
        viewModelScope.launch {
            try {
                repo.downloadAudio(id)
                refreshStorage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(friendly(e))
            } finally {
                _downloading.update { it - id }
            }
        }
    }

    // ---- 清除音频 ----

    fun clearSelectedAudio() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            player.release(ids.toSet())
            repo.clearAudio(ids)
            clearSelection()
            refreshStorage()
            say("已清除 ${ids.size} 课的音频，可随时重新下载")
        }
    }

    // ---- 存储 ----

    fun refreshStorage() {
        viewModelScope.launch { _storage.value = repo.storageBytes() }
    }

    private fun say(text: String) {
        messageChannel.trySend(text)
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "网络不可用或连接超时，请检查网络后重试"
        is IOException -> e.message ?: "网络出错"
        else -> "出错了：${e.message ?: e.javaClass.simpleName}"
    }
}
