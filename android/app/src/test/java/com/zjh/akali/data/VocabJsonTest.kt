package com.zjh.akali.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VocabJsonTest {
    private val sample = """{"rev":7,"lessons":[
        {"code":"V01-17","unit":1,"number":17,"title":"Colors","pages":[31],
         "entries":[{"label":"1","text":"red","page":31,"audio":"audio/31-1.mp3"},{"label":"2","text":"yellow","page":31,"audio":"audio/31-2.mp3"}],
         "bundle":{"size":335000,"sha256":"abc"},"rev":5,"deleted":false,"created_at":1700000000000},
        {"code":"V01-18","unit":1,"number":18,"title":"Prepositions","pages":[32],"entries":[],
         "bundle":null,"rev":6,"deleted":true,"created_at":0}]}"""

    @Test
    fun parsesSyncPayload() {
        val p = VocabSyncJson.parse(sample)
        assertEquals(7L, p.rev)
        val colors = p.lessons[0]
        assertEquals("V01-17", colors.code)
        assertEquals(335000L, colors.bundleSize)
        assertEquals("abc", colors.bundleSha)
        assertEquals(listOf(31), VocabJson.pages(colors.pages))
        assertEquals(listOf("red", "yellow"), VocabJson.entries(colors.entries).map { it.text })
        val gone = p.lessons[1]
        assertTrue(gone.deleted)
        assertNull(gone.bundleSha)
        assertEquals(0L, gone.bundleSize)
    }

    @Test
    fun notesParseAndMerge() {
        assertEquals(emptyMap<String, EntryNote>(), VocabJson.notes(null))
        assertEquals(emptyMap<String, EntryNote>(), VocabJson.notes(""))
        val one = VocabJson.putNote(null, "31-1", EntryNote("红色", "the color of blood", "She wears a red dress.", "她穿着红裙子。"))
        val two = VocabJson.putNote(one, "31-2", EntryNote("黄色", "the color of a lemon", "A yellow bird.", "一只黄鸟。"))
        val notes = VocabJson.notes(two)
        assertEquals(setOf("31-1", "31-2"), notes.keys)
        assertEquals("She wears a red dress.", notes.getValue("31-1").example)
        // replacing one key leaves the other alone
        val again = VocabJson.notes(VocabJson.putNote(two, "31-1", EntryNote("红", "d", "e", "z")))
        assertEquals("红", again.getValue("31-1").zh)
        assertEquals("黄色", again.getValue("31-2").zh)
        // a damaged entry is skipped instead of breaking the page
        assertEquals(setOf("ok"), VocabJson.notes("""{"ok":{"zh":"a","definition":"b","example":"c","example_zh":"d"},"bad":{"zh":"a"}}""").keys)
        assertEquals("31-1", VocabEntry("1", "red", 31, "audio/31-1.mp3").key)
    }

    @Test
    fun marksJsonRoundTrip() {
        val parsed = MarksJson.parse("""{"rev":9,"marks":[{"code":"V01-17","key":"31-1","state":"favorite"},{"code":"V01-17","key":"31-2","state":"none"}]}""")
        assertEquals(9L, parsed.rev)
        assertEquals(listOf(RemoteMark("V01-17", "31-1", "favorite"), RemoteMark("V01-17", "31-2", "none")), parsed.marks)
        val body = org.json.JSONObject(MarksJson.body(parsed.marks))
        assertEquals(2, body.getJSONArray("marks").length())
        assertEquals("31-1", body.getJSONArray("marks").getJSONObject(0).getString("key"))
        assertEquals(MarkState.FAVORITE, MarkState.fromWire("favorite"))
        assertEquals(MarkState.NONE, MarkState.fromWire("something-else"))
    }

    private fun zip(file: File, vararg names: String) {
        ZipOutputStream(file.outputStream()).use { z ->
            names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() }
        }
    }

    @Test
    fun unzipKeepsFilesInsideTheTargetFolder() {
        val tmp = File.createTempFile("vocab", "").also { it.delete(); it.mkdirs() }
        try {
            val ok = File(tmp, "ok.zip").also { zip(it, "page-1.jpg", "audio/1-1.mp3") }
            VocabRepository.unzip(ok, File(tmp, "out"))
            assertTrue(File(tmp, "out/audio/1-1.mp3").exists())

            val evil = File(tmp, "evil.zip").also { zip(it, "../escape.txt") }
            try {
                VocabRepository.unzip(evil, File(tmp, "out2"))
                fail("should refuse a path that leaves the folder")
            } catch (e: IOException) {
                assertTrue(!File(tmp, "escape.txt").exists())
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
