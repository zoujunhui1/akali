package com.zjh.akali.elllo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ElloParserTest {
    private fun html(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun parsesC1Lesson() {
        val url = "https://elllo.org/video/C1ADV/C1-033-Saif-Social-Media.htm"
        val r = ElloParser.parse(html("C1-033.htm"), url)
        assertEquals("C1", r.slug.level)
        assertEquals(33, r.slug.number)
        assertEquals("Saif Social Media", r.slug.title)
        assertEquals("https://elllo.org/Audio/1ME-C1ADV/C1-033-Saif-Social-Media.mp3", r.audioUrl)
        assertTrue(r.transcript.startsWith("Hi, my name is Saif"))
        assertTrue(r.transcript.contains("likes, shares and comments"))
        assertTrue(r.transcript.contains("\n\n"))
    }

    @Test
    fun parsesB2Lesson() {
        val url = "https://elllo.org/video/B2INT/B2-036-Eulices-Dream-Job.htm"
        val r = ElloParser.parse(html("B2-036.htm"), url)
        assertEquals("B2-036", r.slug.code)
        assertEquals("https://elllo.org/Audio/1ME-B2INT/B2-036-Eulices-Dream-Job.mp3", r.audioUrl)
        assertTrue(r.transcript.isNotBlank())
    }

    @Test
    fun ignoresVocabularyClips() {
        val r = ElloParser.parse(html("C1-034.htm"), "https://elllo.org/video/C1ADV/C1-034-Olivia-Stress-Out.htm")
        assertTrue(r.audioUrl.contains("/Audio/"))
        assertTrue(!r.audioUrl.contains("/ANA/"))
    }

    @Test
    fun missingAudioThrows() {
        try {
            ElloParser.parse("<html><div id='transcript'><p>hi</p></div></html>", "https://elllo.org/video/C1ADV/C1-033-X.htm")
            fail("should throw")
        } catch (e: ElloParseException) {
            assertTrue(e.message!!.contains("音频"))
        }
    }

    @Test
    fun missingTranscriptThrows() {
        val h = "<html><audio><source src='../../Audio/x/a.mp3'></audio></html>"
        try {
            ElloParser.parse(h, "https://elllo.org/video/C1ADV/C1-033-X.htm")
            fail("should throw")
        } catch (e: ElloParseException) {
            assertTrue(e.message!!.contains("文稿"))
        }
    }

    @Test
    fun normalizeUrl() {
        val good = "https://elllo.org/video/C1ADV/C1-033-Saif-Social-Media.htm"
        assertEquals(good, ElloParser.normalizeUrl("  $good  "))
        assertEquals(good, ElloParser.normalizeUrl("看这个 http://www.elllo.org/video/C1ADV/C1-033-Saif-Social-Media.htm?x=1#a"))
        assertEquals(good, ElloParser.normalizeUrl("elllo.org/video/C1ADV/C1-033-Saif-Social-Media.htm"))
    }

    @Test
    fun normalizeUrlRejects() {
        for (bad in listOf("", "hello", "https://example.com/video/C1ADV/C1-033-X.htm", "https://elllo.org/video/index.htm")) {
            try {
                ElloParser.normalizeUrl(bad)
                fail("should reject: $bad")
            } catch (_: ElloParseException) {
            }
        }
    }

    @Test
    fun slugParsing() {
        assertEquals("Stacy Australia First Job", parseSlug("B2-001-STACY-AUSTRALIA-FIRST-JOB")!!.title)
        assertEquals("Bo Plastic Surgery", parseSlug("B2-017-Bo-Plastic-Surgery")!!.title)
        assertEquals("Ummi MarriageOutdated", parseSlug("B2-047-Ummi-MarriageOutdated")!!.title)
        assertEquals("C1-006", parseSlug("C1-006")!!.code)
        assertEquals("C1-006", parseSlug("C1-006.mp3".removeSuffix(".mp3"))!!.code)
        assertNull(parseSlug("random-folder"))
    }
}
