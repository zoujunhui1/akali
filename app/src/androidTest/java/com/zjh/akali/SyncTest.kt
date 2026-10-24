package com.zjh.akali

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.LessonFiles
import com.zjh.akali.data.LessonRepository
import com.zjh.akali.data.RemoteLesson
import com.zjh.akali.data.ServerApi
import com.zjh.akali.data.SyncPayload
import com.zjh.akali.data.Timestamps
import com.zjh.akali.data.VocabMarks
import com.zjh.akali.elllo.ElloClient
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

private class FakeServer : ServerApi {
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
        audioSize = if (sha == null) 0 else 2000, audioSha = sha, deleted = deleted, transcript = "Hi. Text $number.",
        timestamps = """{"version":1,"sentences":[{"paragraph":0,"text":"Hi.","start":0.5,"end":1.2}]}""", vocab = vocab,
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "sync-${System.nanoTime()}").also { it.mkdirs() }
        db = AppDatabase.create(context, null)
        files = LessonFiles(root)
        server = FakeServer()
        repo = LessonRepository(db.lessonDao(), files, ElloClient(), server)
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
        repo.redownload(id)
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
        repo.redownload(id)

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
        repo.redownload(id1)

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
            repo.redownload(id)
            fail("expected the download to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("校验"))
        }
        assertFalse(repo.lessons.first().single().audioDownloaded)

        server.failDownload = false
        repo.redownload(id)
        assertTrue(repo.lessons.first().single().audioDownloaded)
        assertEquals(2000L, files.audio(id).length())
        assertNotNull(repo.audioFile(id))
    }
}
