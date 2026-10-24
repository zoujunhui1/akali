package com.zjh.akali

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.ChatMessage
import com.zjh.akali.data.EntryNote
import com.zjh.akali.data.LessonFiles
import com.zjh.akali.data.LessonRepository
import com.zjh.akali.data.MarksPayload
import com.zjh.akali.data.RemoteLesson
import com.zjh.akali.data.RemoteMark
import com.zjh.akali.data.RemoteVocab
import com.zjh.akali.data.ServerApi
import com.zjh.akali.data.SyncPayload
import com.zjh.akali.data.Timestamps
import com.zjh.akali.data.VocabMarks
import com.zjh.akali.data.VocabPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

internal class FakeServer : ServerApi {
    var lessons = listOf<RemoteLesson>()
    var rev = 1L
    var failDownload = false
    val askedSince = mutableListOf<Long>()
    val audio = ByteArray(2000) { it.toByte() }

    override suspend fun changes(since: Long): SyncPayload {
        askedSince += since
        return SyncPayload(rev, lessons)
    }

    override suspend fun downloadAudio(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit) {
        if (failDownload) throw IOException("下载的音频校验不通过，请重试")
        dest.parentFile?.mkdirs()
        dest.writeBytes(audio)
    }

    var vocab = listOf<RemoteVocab>()
    var vocabRev = 1L
    var bundle = ByteArray(0)
    val askedVocabSince = mutableListOf<Long>()

    override suspend fun vocabChanges(since: Long): VocabPayload {
        askedVocabSince += since
        return VocabPayload(vocabRev, vocab)
    }

    override suspend fun downloadVocabBundle(code: String, sha256: String?, dest: File, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        dest.writeBytes(bundle)
    }

    val serverMarks = linkedMapOf<Pair<String, String>, String>()
    var marksRev = 1L
    var failMarks = false
    val pushed = mutableListOf<List<RemoteMark>>()

    override suspend fun putMarks(marks: List<RemoteMark>): Long {
        if (failMarks) throw IOException("offline")
        pushed += marks
        marks.forEach { serverMarks[it.code to it.key] = it.state }
        return ++marksRev
    }

    override suspend fun marksChanges(since: Long): MarksPayload {
        if (failMarks) throw IOException("offline")
        return MarksPayload(marksRev, serverMarks.map { RemoteMark(it.key.first, it.key.second, it.value) })
    }

    val explained = mutableListOf<String>()
    var chatReply = "Nice!"
    var chatSeen = listOf<ChatMessage>()

    override suspend fun explainEntry(code: String, key: String, force: Boolean): EntryNote {
        explained += "$code/$key/$force"
        return EntryNote("红色", "the color of blood", "She wears a red dress.", "她穿着红裙子。")
    }

    override suspend fun chat(code: String, messages: List<ChatMessage>): String {
        chatSeen = messages
        return chatReply
    }
}

class SyncTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var files: LessonFiles
    private lateinit var server: FakeServer
    private lateinit var repo: LessonRepository

    private fun remote(
        number: Int, title: String = "Lesson $number", sha: String? = "sha$number", deleted: Boolean = false,
        vocab: String? = """{"version":1,"items":[{"sentence":0,"text":"Hi","type":"word","en":"hi","zh":"你好"}]}""",
    ) = RemoteLesson(
        level = "B2", number = number, title = title, sourceUrl = "https://elllo.org/B2-$number.htm",
        audioSize = if (sha == null) 0 else 2000, audioSha = sha, deleted = deleted, createdAt = 1_700_000_000_000L + number, transcript = "Hi. Text $number.",
        timestamps = """{"version":1,"sentences":[{"paragraph":0,"text":"Hi.","start":0.5,"end":1.2}]}""", vocab = vocab,
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "sync-${System.nanoTime()}").also { it.mkdirs() }
        db = AppDatabase.create(context, null)
        files = LessonFiles(root)
        server = FakeServer()
        repo = LessonRepository(db.lessonDao(), files, server)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun firstSyncAddsLessonsWithoutAudio() = runBlocking {
        server.lessons = listOf(remote(1), remote(2))
        val r = repo.sync(0)
        assertEquals(2, r.added)
        val lessons = repo.lessons.first()
        assertEquals(2, lessons.size)
        val l = lessons.first { it.number == 1 }
        assertFalse(l.audioDownloaded)
        assertEquals(2000L, l.remoteAudioSize)
        assertEquals("sha1", l.remoteAudioSha)
        assertEquals("Hi.", Timestamps.parse(l.timestamps).single().text)
        assertEquals("你好", VocabMarks.parse(l.vocab).single().zh)
    }

    @Test
    fun laterSyncUpdatesTextButKeepsDownloadedAudio() = runBlocking {
        server.lessons = listOf(remote(1))
        repo.sync(0)
        val id = repo.lessons.first().single().id
        repo.downloadAudio(id)
        assertTrue(repo.lessons.first().single().audioDownloaded)

        server.lessons = listOf(remote(1, title = "New title", vocab = """{"version":1,"items":[]}"""))
        val removed = mutableListOf<Long>()
        val r = repo.sync(1) { removed += it }
        assertEquals(1, r.updated)
        assertEquals(0, r.removed)
        assertTrue(removed.isEmpty())
        val l = repo.lessons.first().single()
        assertEquals("New title", l.title)
        assertTrue(VocabMarks.parse(l.vocab).isEmpty())
        assertTrue(l.audioDownloaded)
        assertTrue(files.audio(id).exists())
    }

    @Test
    fun replacedAudioOnTheServerClearsTheOldLocalFile() = runBlocking {
        server.lessons = listOf(remote(1))
        repo.sync(0)
        val id = repo.lessons.first().single().id
        repo.downloadAudio(id)

        server.lessons = listOf(remote(1, sha = "different"))
        val released = mutableListOf<Long>()
        val r = repo.sync(1) { released += it }
        assertEquals(1, r.removed)
        assertEquals(listOf(id), released)
        val l = repo.lessons.first().single()
        assertFalse(l.audioDownloaded)
        assertFalse(files.audio(id).exists())
        assertEquals("different", l.remoteAudioSha)
    }

    @Test
    fun lessonsDeletedOnTheServerAreRemovedLocally() = runBlocking {
        server.lessons = listOf(remote(1), remote(2))
        repo.sync(0)
        val id1 = repo.lessons.first().first { it.number == 1 }.id
        repo.downloadAudio(id1)

        server.lessons = listOf(remote(1, deleted = true))
        val released = mutableListOf<Long>()
        val r = repo.sync(1) { released += it }
        assertEquals(1, r.removed)
        assertEquals(listOf(id1), released)
        assertEquals(listOf(2), repo.lessons.first().map { it.number })
        assertFalse(files.dir(id1).exists())
    }

    @Test
    fun emptyDatabaseAlwaysSyncsFromScratch() = runBlocking {
        server.lessons = listOf(remote(1))
        repo.sync(999)
        assertEquals(listOf(0L), server.askedSince)
        repo.sync(5)
        assertEquals(listOf(0L, 5L), server.askedSince)
    }

    @Test
    fun downloadComesFromTheServerAndFailureLeavesNothing() = runBlocking {
        server.lessons = listOf(remote(1))
        repo.sync(0)
        val id = repo.lessons.first().single().id

        server.failDownload = true
        try {
            repo.downloadAudio(id)
            fail("expected the download to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("校验"))
        }
        assertFalse(repo.lessons.first().single().audioDownloaded)

        server.failDownload = false
        repo.downloadAudio(id)
        assertTrue(repo.lessons.first().single().audioDownloaded)
        assertEquals(2000L, files.audio(id).length())
        assertNotNull(repo.audioFile(id))
    }

    @Test
    fun clearedAudioCanBeDownloadedAgain() = runBlocking {
        server.lessons = listOf(remote(1))
        repo.sync(0)
        val id = repo.lessons.first().single().id
        repo.downloadAudio(id)
        repo.clearAudio(listOf(id))
        assertFalse(files.audio(id).exists())
        assertFalse(repo.lessons.first().single().audioDownloaded)
        repo.downloadAudio(id)
        assertTrue(repo.lessons.first().single().audioDownloaded)
    }

    @Test
    fun lessonWithoutServerAudioCannotBeDownloaded() = runBlocking {
        server.lessons = listOf(remote(1, sha = null))
        repo.sync(0)
        val id = repo.lessons.first().single().id
        try {
            repo.downloadAudio(id)
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("还没有音频"))
        }
    }

    @Test
    fun addedTimeFollowsTheServerCreationTime() = runBlocking {
        server.lessons = listOf(remote(1), remote(2))
        repo.sync(0)
        assertEquals(listOf(1_700_000_000_001L, 1_700_000_000_002L), repo.lessons.first().sortedBy { it.number }.map { it.addedAt })
    }
}
