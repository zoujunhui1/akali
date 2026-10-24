package com.zjh.akali

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.ArticleRepository
import com.zjh.akali.data.NotebookRepository
import com.zjh.akali.data.NotebookState
import com.zjh.akali.data.RemoteArticle
import com.zjh.akali.data.RemoteWord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ReadingSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var server: FakeServer
    private lateinit var articles: ArticleRepository
    private lateinit var notebook: NotebookRepository

    private fun remote(code: String, title: String = "Title $code", deleted: Boolean = false) = RemoteArticle(
        code = code, title = title, source = "made up", body = """[["One.","Two."],["Three."]]""",
        wordCount = 3, deleted = deleted, createdAt = 1_700_000_000_000L,
    )

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.create(context, null)
        server = FakeServer()
        articles = ArticleRepository(db.articleDao(), server)
        notebook = NotebookRepository(db.notebookDao(), server)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun articlesSyncAndKeepTheReadingPlaceWhenUpdated() = runBlocking {
        server.articles = listOf(remote("R0001"), remote("R0002"))
        val first = articles.sync(0)
        assertEquals(2, first.added)
        val list = articles.articles.first()
        assertEquals(2, list.size)
        assertEquals(listOf(listOf("One.", "Two."), listOf("Three.")), list.first { it.code == "R0001" }.paragraphs)

        articles.saveProgress("R0001", 1)
        server.articles = listOf(remote("R0001", title = "New title"))
        val second = articles.sync(1)
        assertEquals(1, second.updated)
        val updated = articles.articles.first().first { it.code == "R0001" }
        assertEquals("New title", updated.title)
        assertEquals("the place you stopped reading is kept", 1, updated.progress)
    }

    @Test
    fun articlesDeletedOnTheServerDisappearAndAnEmptyPhoneStartsFromScratch() = runBlocking {
        server.articles = listOf(remote("R0001"))
        articles.sync(0)
        server.articles = listOf(remote("R0001", deleted = true))
        assertEquals(1, articles.sync(1).removed)
        assertTrue(articles.articles.first().isEmpty())
        server.askedArticleSince.clear()
        articles.sync(99) // nothing stored, so the phone asks from the beginning whatever it remembers
        assertEquals(listOf(0L), server.askedArticleSince)
    }

    @Test
    fun aWordIsKeptOnThePhoneAtOnceAndUploadedOnSync() = runBlocking {
        val id = notebook.add("exhausted", "Everyone was exhausted.", "R0001", "筋疲力尽")
        assertEquals("exhausted", notebook.words.first().single().word)
        assertTrue(db.notebookDao().find(id)!!.pending)
        assertTrue("nothing is sent before a sync", server.pushedWords.isEmpty())

        val rev = notebook.sync(0)
        assertEquals(server.notebookRev, rev)
        assertEquals("Everyone was exhausted.", server.pushedWords.single().single().sentence)
        assertFalse(db.notebookDao().find(id)!!.pending)
        notebook.sync(rev)
        assertEquals("no second upload when nothing changed", 1, server.pushedWords.size)
    }

    @Test
    fun offlineWordsStayOnThePhoneAndGoUpLater() = runBlocking {
        val id = notebook.add("blew", "The wind blew.", "R0001", "吹")
        server.failNotebook = true
        try {
            notebook.sync(0)
            org.junit.Assert.fail("sync should fail while offline")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(1, notebook.words.first().size)
        assertTrue(db.notebookDao().find(id)!!.pending)
        server.failNotebook = false
        notebook.sync(0)
        assertEquals("blew", server.serverWords.getValue(id).word)
    }

    @Test
    fun removingOrMasteringAWordIsSyncedAndRemovedWordsLeaveTheList() = runBlocking {
        val a = notebook.add("alpha", "Alpha is first.", "R0001", "甲")
        val b = notebook.add("beta", "Beta is second.", "R0001", "乙")
        notebook.sync(0)
        notebook.setState(a, NotebookState.MASTERED)
        notebook.setState(b, NotebookState.NONE)
        notebook.sync(1)
        assertEquals("mastered", server.serverWords.getValue(a).state)
        assertEquals("none", server.serverWords.getValue(b).state)
        assertEquals(listOf("alpha"), notebook.words.first().map { it.word })
    }

    @Test
    fun wordsFromAnotherPhoneArriveAndDoNotOverwriteLocalChanges() = runBlocking {
        server.serverWords["server-word-0001"] = RemoteWord("server-word-0001", "gamma", "Gamma came.", "R0002", "丙", "learning")
        notebook.sync(0)
        assertEquals(listOf("gamma"), notebook.words.first().map { it.word })

        // this phone marks it known but has not synced; the server still says "learning"
        notebook.setState("server-word-0001", NotebookState.MASTERED)
        server.failNotebook = true
        try { notebook.sync(1) } catch (e: IOException) { /* offline */ }
        server.failNotebook = false
        server.serverWords["server-word-0001"] = RemoteWord("server-word-0001", "gamma", "Gamma came.", "R0002", "丙", "learning")
        notebook.sync(1)
        assertEquals("mastered", server.serverWords.getValue("server-word-0001").state)
    }
}
