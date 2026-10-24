package com.zjh.akali.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** 生词本里一个词的状态。NONE 是“已从生词本删掉”，留一行为了把删除同步给服务器和别的设备。 */
enum class NotebookState(val wire: String) {
    LEARNING("learning"),
    MASTERED("mastered"),
    NONE("none");

    companion object {
        fun fromWire(s: String): NotebookState = entries.firstOrNull { it.wire == s } ?: LEARNING
    }
}

/**
 * 阅读时收进生词本的词：[word] 是文章里的原样（如 blew），[sentence] 是它所在的句子，
 * [meaning] 是当时看到的中文意思。[pending] 表示还没上传服务器。
 */
@Entity(tableName = "notebook_words")
data class NotebookWord(
    @PrimaryKey val id: String,
    val word: String,
    val sentence: String,
    val articleCode: String,
    val meaning: String,
    val state: String,
    val pending: Boolean,
    val addedAt: Long,
) {
    val notebookState: NotebookState get() = NotebookState.fromWire(state)
}

data class RemoteWord(val id: String, val word: String, val sentence: String, val article: String, val meaning: String, val state: String)

data class NotebookPayload(val rev: Long, val words: List<RemoteWord>)

object NotebookJson {
    fun parse(json: String): NotebookPayload {
        val root = JSONObject(json)
        val arr = root.getJSONArray("words")
        return NotebookPayload(
            root.getLong("rev"),
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                RemoteWord(o.getString("id"), o.getString("word"), o.getString("sentence"), o.optString("article"), o.optString("meaning"), o.getString("state"))
            },
        )
    }

    fun body(words: List<RemoteWord>): String = JSONObject().put(
        "words",
        JSONArray(
            words.map {
                JSONObject().put("id", it.id).put("word", it.word).put("sentence", it.sentence)
                    .put("article", it.article).put("meaning", it.meaning).put("state", it.state)
            },
        ),
    ).toString()
}

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebook_words WHERE state <> 'none' ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<NotebookWord>>

    @Query("SELECT * FROM notebook_words WHERE id = :id")
    suspend fun find(id: String): NotebookWord?

    @Query("SELECT * FROM notebook_words WHERE pending = 1")
    suspend fun pending(): List<NotebookWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: NotebookWord)

    @Query("UPDATE notebook_words SET pending = 0 WHERE id = :id AND state = :state")
    suspend fun markSynced(id: String, state: String)
}

class NotebookRepository(
    private val dao: NotebookDao,
    private val server: ServerApi? = null,
) {
    private val lock = Mutex()

    val words: Flow<List<NotebookWord>> = dao.observeAll()

    /** 把词收进生词本，先存在手机上（界面马上变），同步时再上传。返回新词的 id。 */
    suspend fun add(word: String, sentence: String, articleCode: String, meaning: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.upsert(NotebookWord(id, word, sentence, articleCode, meaning, NotebookState.LEARNING.wire, pending = true, addedAt = System.currentTimeMillis()))
        id
    }

    suspend fun setState(id: String, state: NotebookState) = withContext(Dispatchers.IO) {
        val w = dao.find(id) ?: return@withContext
        dao.upsert(w.copy(state = state.wire, pending = true))
    }

    /** 先上传还没上传的，再取服务器上 [since] 之后的变化；还没上传的本地改动不会被服务器上的旧状态盖掉。 */
    suspend fun sync(since: Long): Long = lock.withLock {
        withContext(Dispatchers.IO) {
            val api = server ?: throw IOException("没有配置服务器")
            dao.pending().chunked(500).forEach { batch ->
                api.putNotebook(batch.map { RemoteWord(it.id, it.word, it.sentence, it.articleCode, it.meaning, it.state) })
                batch.forEach { dao.markSynced(it.id, it.state) }
            }
            val payload = api.notebookChanges(if (since < 0) 0 else since)
            for (r in payload.words) {
                val local = dao.find(r.id)
                if (local?.pending == true) continue
                dao.upsert(
                    NotebookWord(
                        r.id, r.word, r.sentence, r.article, r.meaning, r.state, pending = false,
                        addedAt = local?.addedAt ?: System.currentTimeMillis(),
                    ),
                )
            }
            payload.rev
        }
    }
}
