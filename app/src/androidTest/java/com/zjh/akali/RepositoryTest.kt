package com.zjh.akali

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import com.zjh.akali.data.AddResult
import com.zjh.akali.data.AppDatabase
import com.zjh.akali.data.LessonFiles
import com.zjh.akali.data.LessonRepository
import com.zjh.akali.elllo.ElloClient
import com.zjh.akali.elllo.ElloParseException
import com.zjh.akali.importer.FolderImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class RepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var files: LessonFiles

    private val pageUrl = "https://elllo.org/video/C1ADV/C1-033-Saif-Social-Media.htm"
    private val html = """
        <html><body>
        <audio><source src="../../Audio/1ME-C1ADV/C1-033-Saif-Social-Media.mp3"></audio>
        <audio><source src="../../ANA/C1VOME/C1-033-01-limited-time.mp3"></audio>
        <div id="transcript"><p>Hello there.</p><p>Second <span>paragraph</span>.</p></div>
        </body></html>
    """.trimIndent()
    private val audio = ByteArray(5000) { it.toByte() }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "test-${System.nanoTime()}").also { it.mkdirs() }
        db = AppDatabase.create(context, null)
        files = LessonFiles(root)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun client(audioStatus: Int = 200) = ElloClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val (code, bytes, type) = when {
                url.endsWith(".htm") -> Triple(200, html.toByteArray(), "text/html")
                url.endsWith("C1-033-Saif-Social-Media.mp3") -> Triple(audioStatus, audio, "audio/mpeg")
                else -> Triple(404, ByteArray(0), "text/plain")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(bytes.toResponseBody(type.toMediaType())).build()
        }.build(),
    )

    private fun repo(audioStatus: Int = 200) = LessonRepository(db.lessonDao(), files, client(audioStatus), now = { 1000L })

    @Test
    fun addFromUrlStoresLessonAudioAndTranscript() = runBlocking {
        val r = repo().addFromUrl("  $pageUrl  ")
        assertTrue(r is AddResult.Added)
        val lesson = (r as AddResult.Added).lesson
        assertEquals("C1-033", lesson.code)
        assertEquals("Saif Social Media", lesson.title)
        assertEquals("Hello there.\n\nSecond paragraph.", lesson.transcript)
        assertTrue(lesson.audioDownloaded)
        assertEquals(pageUrl, lesson.sourceUrl)
        assertEquals(audio.size.toLong(), files.audio(lesson.id).length())
        assertEquals(1, repo().lessons.first().size)
    }

    @Test
    fun addingTwiceReportsAlreadyExists() = runBlocking {
        repo().addFromUrl(pageUrl)
        assertTrue(repo().addFromUrl(pageUrl) is AddResult.AlreadyExists)
        assertEquals(1, db.lessonDao().observeAll().first().size)
    }

    @Test
    fun failedAudioDownloadLeavesNothingBehind() = runBlocking {
        try {
            repo(audioStatus = 404).addFromUrl(pageUrl)
            fail("should throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
        }
        assertEquals(0, db.lessonDao().observeAll().first().size)
        assertEquals(0L, files.totalBytes())
    }

    @Test
    fun invalidLinkIsRejected() = runBlocking {
        try {
            repo().addFromUrl("https://example.com/x.htm")
            fail("should throw")
        } catch (_: ElloParseException) {
        }
    }

    @Test
    fun clearAudioKeepsEntryAndRedownloadRestoresIt() = runBlocking {
        val r = repo()
        val lesson = (r.addFromUrl(pageUrl) as AddResult.Added).lesson
        r.clearAudio(listOf(lesson.id))
        val cleared = db.lessonDao().get(lesson.id)!!
        assertFalse(cleared.audioDownloaded)
        assertEquals(lesson.transcript, cleared.transcript)
        assertFalse(files.audio(lesson.id).exists())

        r.redownload(lesson.id)
        assertTrue(db.lessonDao().get(lesson.id)!!.audioDownloaded)
        assertEquals(audio.size.toLong(), files.audio(lesson.id).length())
    }

    @Test
    fun addingClearedLessonAgainRedownloadsInsteadOfDuplicating() = runBlocking {
        val r = repo()
        val lesson = (r.addFromUrl(pageUrl) as AddResult.Added).lesson
        r.clearAudio(listOf(lesson.id))
        assertTrue(r.addFromUrl(pageUrl) is AddResult.Redownloaded)
        assertEquals(1, db.lessonDao().observeAll().first().size)
    }

    @Test
    fun redownloadWithoutSourceUrlFails() = runBlocking {
        val r = repo()
        r.insertImported(com.zjh.akali.elllo.parseSlug("B2-001-Test")!!, null, "t", 1L, audio.inputStream())
        val id = db.lessonDao().find("B2", 1)!!.id
        r.clearAudio(listOf(id))
        try {
            r.redownload(id)
            fail("should throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("没有来源链接"))
        }
    }

    @Test
    fun deleteRemovesRowsAndFiles() = runBlocking {
        val r = repo()
        val lesson = (r.addFromUrl(pageUrl) as AddResult.Added).lesson
        r.deleteLessons(listOf(lesson.id))
        assertNull(db.lessonDao().get(lesson.id))
        assertFalse(files.dir(lesson.id).exists())
    }

    @Test
    fun importSkipsDuplicatesAndReportsBadFolders() = runBlocking {
        val src = File(root, "src").also { it.mkdirs() }
        File(src, "B2-001-STACY-FIRST-JOB").apply {
            mkdirs()
            File(this, "B2-001-STACY-FIRST-JOB.mp3").writeBytes(audio)
            File(this, "transcript.txt").writeText("Hi Stacy\n")
            File(this, "url.txt").writeText("https://elllo.org/video/B2INT/B2-001-Stacy.htm\n")
            File(this, "extra.mp4").writeBytes(ByteArray(10))
        }
        File(src, "C1-006-Olivia-Sign").apply {
            mkdirs()
            File(this, "C1-006.mp3").writeBytes(audio)
            File(this, "transcript.txt").writeText("Hi Olivia")
        }
        File(src, "random-folder").apply { mkdirs(); File(this, "a.mp3").writeBytes(audio) }
        File(src, "B2-009-No-Audio").apply { mkdirs(); File(this, "transcript.txt").writeText("x") }

        val importer = FolderImporter(context, repo())
        val first = importer.import(DocumentFile.fromFile(src))
        assertEquals(2, first.imported)
        assertEquals(0, first.skipped)
        assertEquals(2, first.failed.size)

        val stacy = db.lessonDao().find("B2", 1)!!
        assertEquals("Stacy First Job", stacy.title)
        assertEquals("Hi Stacy", stacy.transcript)
        assertEquals("https://elllo.org/video/B2INT/B2-001-Stacy.htm", stacy.sourceUrl)
        assertTrue(stacy.audioDownloaded)
        assertNotNull(db.lessonDao().find("C1", 6))
        assertNull(db.lessonDao().find("C1", 6)!!.sourceUrl)

        val second = importer.import(DocumentFile.fromFile(src))
        assertEquals(0, second.imported)
        assertEquals(2, second.skipped)
    }
}
