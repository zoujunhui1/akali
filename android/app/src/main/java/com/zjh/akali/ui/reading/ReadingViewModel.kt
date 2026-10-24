package com.zjh.akali.ui.reading

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zjh.akali.AkaliApp
import com.zjh.akali.data.Article
import com.zjh.akali.data.DictResult
import com.zjh.akali.data.NotebookState
import com.zjh.akali.data.NotebookWord
import com.zjh.akali.data.SentenceHelp
import com.zjh.akali.data.WordForms
import com.zjh.akali.data.WordHelp
import com.zjh.akali.playback.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** AI 讲解的状态。结果只放在内存里，关掉讲解面板就没了，不存手机也不存服务器。 */
sealed interface Help<out T> {
    data object Idle : Help<Nothing>
    data object Loading : Help<Nothing>
    data class Ready<T>(val value: T) : Help<T>
    data class Failed(val message: String) : Help<Nothing>
}

class ReadingViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AkaliApp).container
    private val articleRepo = container.articleRepository
    private val notebookRepo = container.notebookRepository
    private val dict = container.dictRepository
    private val api = container.serverApi
    private val speaker = Speaker(app)

    val articles: StateFlow<List<Article>?> = articleRepo.articles.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val notebook: StateFlow<List<NotebookWord>> = notebookRepo.words.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val dictState = dict.state

    /**
     * 一个词的归并写法：能查到原形就用原形（ran、running 都归到 run），查不到就用小写原样。
     * 收藏的词在文章里认出来、取消收藏，都靠它。
     */
    fun keyOf(token: String): String {
        val r = dict.lookup(token) ?: return WordForms.normalize(token)
        return (r.base ?: r.entry)?.word?.lowercase() ?: r.looked
    }

    /** 现在收藏着（学习中）的词的归并写法；字典下载好后会按原形重新算。 */
    val favoriteKeys: StateFlow<Set<String>> = combine(notebook, dictState) { words, _ ->
        words.filter { it.notebookState == NotebookState.LEARNING }.map { keyOf(it.word) }.toSet()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 取消收藏：这个词（含各种变形）所有的收藏记录都去掉，所有文章里的颜色一起没了。 */
    fun unfavorite(token: String) {
        val key = keyOf(token)
        val ids = notebook.value.filter { it.notebookState != NotebookState.NONE && keyOf(it.word) == key }.map { it.id }
        viewModelScope.launch {
            ids.forEach { notebookRepo.setState(it, NotebookState.NONE) }
            messageChannel.trySend("已取消收藏")
            syncNotebook()
        }
    }

    fun article(code: String): Flow<Article?> = articleRepo.observe(code)

    private val _fontIndex = MutableStateFlow(container.prefs.readingFont)
    val fontIndex: StateFlow<Int> = _fontIndex

    /** 点一下字号按钮，在五档字号里循环。 */
    fun cycleFontSize() {
        val next = (_fontIndex.value + 1) % 5
        container.prefs.readingFont = next
        _fontIndex.value = next
    }

    private val _wordHelp = MutableStateFlow<Help<WordHelp>>(Help.Idle)
    val wordHelp: StateFlow<Help<WordHelp>> = _wordHelp
    private val _sentenceHelp = MutableStateFlow<Help<SentenceHelp>>(Help.Idle)
    val sentenceHelp: StateFlow<Help<SentenceHelp>> = _sentenceHelp

    /** 段落翻译（"文章/段落序号" -> 中文），也只在内存里，退出文章就没了。 */
    private val _translations = MutableStateFlow<Map<String, Help<String>>>(emptyMap())
    val translations: StateFlow<Map<String, Help<String>>> = _translations

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var wordJob: Job? = null
    private var sentenceJob: Job? = null

    /** 查本地字典；字典还没下载返回 null。 */
    fun lookup(token: String): DictResult? = dict.lookup(token)

    fun askWord(word: String, sentence: String) {
        wordJob?.cancel()
        _wordHelp.value = Help.Loading
        wordJob = viewModelScope.launch {
            _wordHelp.value = run(failure = { Help.Failed(it) }) { Help.Ready(needApi().readWord(word, sentence)) }
        }
    }

    fun askSentence(sentence: String) {
        sentenceJob?.cancel()
        _sentenceHelp.value = Help.Loading
        sentenceJob = viewModelScope.launch {
            _sentenceHelp.value = run(failure = { Help.Failed(it) }) { Help.Ready(needApi().readSentence(sentence)) }
        }
    }

    fun clearWordHelp() {
        wordJob?.cancel()
        _wordHelp.value = Help.Idle
    }

    fun clearSentenceHelp() {
        sentenceJob?.cancel()
        _sentenceHelp.value = Help.Idle
    }

    fun translateParagraph(key: String, text: String) {
        if (_translations.value[key] is Help.Loading) return
        _translations.update { it + (key to Help.Loading) }
        viewModelScope.launch {
            val result = run<Help<String>>(failure = { Help.Failed(it) }) { Help.Ready(needApi().translate(text)) }
            _translations.update { it + (key to result) }
        }
    }

    fun hideTranslation(key: String) = _translations.update { it - key }

    fun clearTranslations() {
        _translations.value = emptyMap()
    }

    fun addToNotebook(word: String, sentence: String, articleCode: String, meaning: String) {
        if (keyOf(word) in favoriteKeys.value) return
        viewModelScope.launch {
            notebookRepo.add(word, sentence, articleCode, meaning)
            messageChannel.trySend("已加入生词本")
            syncNotebook()
        }
    }

    fun setNotebookState(id: String, state: NotebookState) {
        viewModelScope.launch {
            notebookRepo.setState(id, state)
            syncNotebook()
        }
    }

    fun saveProgress(code: String, paragraph: Int) {
        viewModelScope.launch { articleRepo.saveProgress(code, paragraph) }
    }

    fun downloadDict() {
        if (dictState.value.downloading) return
        viewModelScope.launch {
            try {
                dict.download()
                messageChannel.trySend("字典下载好了")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(friendly(e))
            }
        }
    }

    fun deleteDict() = dict.delete()

    fun speak(text: String) = speaker.speak(text)

    private suspend fun syncNotebook() {
        try {
            container.prefs.notebookRev = notebookRepo.sync(container.prefs.notebookRev)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 离线或服务器不可用：词已经存在手机上，之后同步会补传
        }
    }

    private fun needApi() = api ?: throw IOException("没有配置服务器")

    private suspend fun <T> run(failure: (String) -> T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(friendly(e))
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> "网络不可用或连接超时，请检查网络后重试"
        is IOException -> e.message ?: "网络出错"
        else -> "出错了：${e.message ?: e.javaClass.simpleName}"
    }

    override fun onCleared() {
        speaker.shutdown()
    }
}
