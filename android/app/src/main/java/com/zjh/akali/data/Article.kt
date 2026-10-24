package com.zjh.akali.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** 一篇文章：[body] 是 [[句子, …], …]（一段一个列表）的 JSON。[progress] 是读到的段落序号。 */
@Entity(tableName = "articles")
data class Article(
    @PrimaryKey val code: String,
    val title: String,
    val source: String,
    val body: String,
    val wordCount: Int,
    val addedAt: Long,
    val progress: Int = 0,
) {
    val paragraphs: List<List<String>> get() = ArticleJson.body(body)
}

data class RemoteArticle(
    val code: String,
    val title: String,
    val source: String,
    val body: String,
    val wordCount: Int,
    val deleted: Boolean,
    val createdAt: Long,
)

data class ArticlePayload(val rev: Long, val articles: List<RemoteArticle>)

object ArticleJson {
    fun body(json: String): List<List<String>> = JSONArray(json).let { paragraphs ->
        List(paragraphs.length()) { i ->
            val sentences = paragraphs.getJSONArray(i)
            List(sentences.length()) { sentences.getString(it) }
        }
    }

    fun parse(json: String): ArticlePayload {
        val root = JSONObject(json)
        val arr = root.getJSONArray("articles")
        return ArticlePayload(
            root.getLong("rev"),
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                RemoteArticle(
                    code = o.getString("code"),
                    title = o.getString("title"),
                    source = o.optString("source"),
                    body = o.getJSONArray("body").toString(),
                    wordCount = o.optInt("word_count"),
                    deleted = o.optBoolean("deleted"),
                    createdAt = o.optLong("created_at", 0L),
                )
            },
        )
    }
}

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY code DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Article>>

    @Query("SELECT * FROM articles WHERE code = :code")
    fun observe(code: String): Flow<Article?>

    @Query("SELECT * FROM articles WHERE code = :code")
    suspend fun find(code: String): Article?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(article: Article)

    @Query("UPDATE articles SET progress = :progress WHERE code = :code")
    suspend fun setProgress(code: String, progress: Int)

    @Query("DELETE FROM articles WHERE code = :code")
    suspend fun delete(code: String)

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun count(): Int
}

data class ArticleSyncResult(val added: Int, val updated: Int, val removed: Int, val rev: Long)

class ArticleRepository(
    private val dao: ArticleDao,
    private val server: ServerApi? = null,
) {
    val articles: Flow<List<Article>> = dao.observeAll()

    fun observe(code: String): Flow<Article?> = dao.observe(code)

    /** 从服务器同步文章。服务器上删掉的在本地也删；已有文章的阅读进度保留。 */
    suspend fun sync(since: Long): ArticleSyncResult {
        val api = server ?: throw java.io.IOException("没有配置服务器")
        val payload = api.articleChanges(if (dao.count() == 0) 0 else since)
        var added = 0
        var updated = 0
        var removed = 0
        for (r in payload.articles) {
            val local = dao.find(r.code)
            if (r.deleted) {
                if (local != null) {
                    dao.delete(r.code)
                    removed++
                }
                continue
            }
            dao.upsert(
                Article(
                    code = r.code, title = r.title, source = r.source, body = r.body, wordCount = r.wordCount,
                    addedAt = r.createdAt.takeIf { it > 0 } ?: local?.addedAt ?: System.currentTimeMillis(),
                    progress = local?.progress ?: 0,
                ),
            )
            if (local == null) added++ else updated++
        }
        return ArticleSyncResult(added, updated, removed, payload.rev)
    }

    suspend fun saveProgress(code: String, paragraph: Int) = dao.setProgress(code, paragraph)
}
