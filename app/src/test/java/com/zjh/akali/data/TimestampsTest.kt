package com.zjh.akali.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampsTest {
    private val json = """{"version":1,"sentences":[
        {"paragraph":0,"text":"Hello.","start":0.0,"end":2.2},
        {"paragraph":0,"text":"My name is Stacy.","start":2.5,"end":4.3},
        {"paragraph":1,"text":"Bye.","start":5.04,"end":6.0}]}"""

    @Test
    fun parsesSentencesToMilliseconds() {
        val s = Timestamps.parse(json)
        assertEquals(3, s.size)
        assertEquals(Sentence(0, "Hello.", 0, 2200), s[0])
        assertEquals(Sentence(1, "Bye.", 5040, 6000), s[2])
    }

    @Test
    fun badOrMissingJsonGivesEmptyList() {
        assertTrue(Timestamps.parse(null).isEmpty())
        assertTrue(Timestamps.parse("").isEmpty())
        assertTrue(Timestamps.parse("not json").isEmpty())
        assertTrue(Timestamps.parse("""{"sentences":[{"text":"x"}]}""").isEmpty())
    }

    @Test
    fun indexAtPicksLastSentenceThatHasStarted() {
        val s = Timestamps.parse(json)
        assertEquals(0, Timestamps.indexAt(s, 0))
        assertEquals(0, Timestamps.indexAt(s, 2400)) // 两句之间的空隙算前一句
        assertEquals(1, Timestamps.indexAt(s, 2500))
        assertEquals(2, Timestamps.indexAt(s, 99_000))
        assertEquals(-1, Timestamps.indexAt(emptyList(), 100))
    }
}
