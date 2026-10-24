package com.zjh.akali.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncJsonTest {
    private val json = """
        {"rev":42,"lessons":[
          {"code":"B2-036","level":"B2","number":36,"title":"Eulices Dream Job","source_url":"https://elllo.org/x.htm",
           "audio":{"size":1300000,"sha256":"abc123"},"rev":40,"deleted":false,"created_at":1700000000123,
           "transcript":"Hi, my name is Eulices.",
           "timestamps":{"version":1,"sentences":[{"paragraph":0,"text":"Hi.","start":0.5,"end":1.2}]},
           "vocab":{"version":1,"items":[{"sentence":0,"text":"Hi","type":"word","en":"hi","zh":"你好"}]}},
          {"code":"C1-006","level":"C1","number":6,"title":"No extras","source_url":null,"audio":null,"rev":41,"deleted":false,"transcript":"Text."},
          {"code":"B2-001","level":"B2","number":1,"title":"Gone","source_url":null,"audio":null,"rev":42,"deleted":true}
        ]}
    """.trimIndent()

    @Test
    fun parsesLessonsAndRevision() {
        val p = SyncJson.parse(json)
        assertEquals(42L, p.rev)
        assertEquals(3, p.lessons.size)
        val full = p.lessons[0]
        assertEquals("B2-036", full.code)
        assertEquals(1_300_000L, full.audioSize)
        assertEquals("abc123", full.audioSha)
        assertEquals(1700000000123L, full.createdAt)
        assertEquals("https://elllo.org/x.htm", full.sourceUrl)
    }

    @Test
    fun timestampsAndVocabCanBeReadByTheAppParsers() {
        val full = SyncJson.parse(json).lessons[0]
        assertEquals("Hi.", Timestamps.parse(full.timestamps).single().text)
        assertEquals(500L, Timestamps.parse(full.timestamps).single().startMs)
        assertEquals("你好", VocabMarks.parse(full.vocab).single().zh)
    }

    @Test
    fun missingPartsBecomeNullOrZero() {
        val bare = SyncJson.parse(json).lessons[1]
        assertNull(bare.sourceUrl)
        assertNull(bare.audioSha)
        assertEquals(0L, bare.audioSize)
        assertNull(bare.timestamps)
        assertNull(bare.vocab)
        assertFalse(bare.deleted)
        assertTrue(SyncJson.parse(json).lessons[2].deleted)
    }
}
