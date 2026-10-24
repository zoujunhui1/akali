package com.zjh.akali

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.ChatMessage
import com.zjh.akali.data.LessonFiles
import com.zjh.akali.data.RemoteVocab
import com.zjh.akali.data.VocabRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VocabSyncTest {
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var server: FakeServer
    private lateinit var repo: VocabRepository

    private fun remote(number: Int, sha: String? = "sha$number", deleted: Boolean = false) = RemoteVocab(
        code = "V01-%02d".format(number), unit = 1, number = number, title = "Topic $number", pages = "[3$number]",
        entries = """[{"label":"1","text":"red","page":3$number,"audio":"audio/3$number-1.mp3"}]""",
        bundleSize = if (sha == null) 0 else 100, bundleSha = sha, deleted = deleted, createdAt = 1_700_000_000_000L + number,
    )

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            names.forEach { n ->
                z.putNextEntry(ZipEntry(n))
                z.write(n.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "vocab-${System.nanoTime()}").also { it.mkdirs() }
        db = AppDatabase.create(context, null)
        server = FakeServer()
        repo = VocabRepository(db.vocabDao(), LessonFiles(root), server)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun syncAddsTopicsThenDownloadUnpacksFiles() = runBlocking {
        server.vocab = listOf(remote(1), remote(2, sha = null))
        server.bundle = zip("page-31.jpg", "audio/31-1.mp3")
        assertEquals(2, repo.sync(0).added)
        val topic = repo.observe("V01-01").first()!!
        assertFalse(topic.downloaded)
        assertEquals("red", topic.entryList.single().text)
        assertEquals(listOf(31), topic.pageList)

        repo.download("V01-01")
        assertTrue(repo.observe("V01-01").first()!!.downloaded)
        assertEquals("audio/31-1.mp3", File(repo.dir("V01-01"), "audio/31-1.mp3").readText())
        assertTrue(File(repo.dir("V01-01"), "page-31.jpg").exists())
        assertFalse("the zip itself is not kept", File(root, "vocab/V01-01.zip").exists())

        repo.clear("V01-01")
        assertFalse(repo.observe("V01-01").first()!!.downloaded)
        assertFalse(repo.dir("V01-01").exists())
    }

    @Test
    fun replacedFilesAreDroppedAndDeletedTopicsRemoved() = runBlocking {
        server.vocab = listOf(remote(1), remote(2))
        server.bundle = zip("page-31.jpg")
        repo.sync(0)
        repo.download("V01-01")

        // same files: stays downloaded; new sha: back to "not downloaded"; deleted: gone
        server.vocab = listOf(remote(1))
        assertEquals(1, repo.sync(1).updated)
        assertTrue(repo.observe("V01-01").first()!!.downloaded)
        server.vocab = listOf(remote(1, sha = "other"), remote(2, deleted = true))
        val r = repo.sync(2)
        assertEquals(1, r.updated)
        assertEquals(1, r.removed)
        assertFalse(repo.observe("V01-01").first()!!.downloaded)
        assertFalse(repo.dir("V01-01").exists())
        assertEquals(null, repo.observe("V01-02").first())
    }

    @Test
    fun explainSavesTheNoteLocallyAndSyncBringsOthersNotes() = runBlocking {
        server.vocab = listOf(remote(1))
        repo.sync(0)
        assertTrue(repo.observe("V01-01").first()!!.noteMap.isEmpty())

        val note = repo.explain("V01-01", "31-1")
        assertEquals("红色", note.zh)
        assertEquals(listOf("V01-01/31-1/false"), server.explained)
        assertEquals("红色", repo.observe("V01-01").first()!!.noteMap.getValue("31-1").zh)

        // a note made on another device arrives with the next sync and does not wipe the local one
        server.vocab = listOf(
            remote(1).copy(notes = """{"31-1":{"zh":"红色","definition":"d","example":"e","example_zh":"z"},"31-2":{"zh":"黄色","definition":"d","example":"e","example_zh":"z"}}"""),
        )
        repo.sync(1)
        assertEquals(setOf("31-1", "31-2"), repo.observe("V01-01").first()!!.noteMap.keys)

        repo.explain("V01-01", "31-2", force = true)
        assertEquals("V01-01/31-2/true", server.explained.last())
    }

    @Test
    fun chatSendsTheWholeConversation() = runBlocking {
        server.vocab = listOf(remote(1))
        repo.sync(0)
        server.chatReply = "What color is your shirt?"
        val history = listOf(ChatMessage("user", "I like red."))
        assertEquals("What color is your shirt?", repo.chat("V01-01", history))
        assertEquals(history, server.chatSeen)
    }

    @Test
    fun badBundleLeavesNothingBehind() = runBlocking {
        server.vocab = listOf(remote(1))
        repo.sync(0)
        server.bundle = zip("../escape.txt")
        try {
            repo.download("V01-01")
            org.junit.Assert.fail("should refuse an unsafe zip")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertFalse(repo.observe("V01-01").first()!!.downloaded)
        assertFalse(File(root, "escape.txt").exists())
        assertFalse(repo.dir("V01-01").exists())
    }
}
