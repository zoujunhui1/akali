package com.zjh.akali.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabMarksTest {
    private val json = """
        {"version":1,"items":[
         {"sentence":0,"text":"going through a hard time","type":"phrase","en":"go through a hard time","ipa":"","zh":"经历困难","example":"x","note":"","star":true,"mastery":"复习中"},
         {"sentence":0,"text":"hard","type":"word","en":"hard","zh":"难"},
         {"sentence":1,"text":"It is fine.","type":"sentence","en":"It is fine.","zh":"没事"},
         {"sentence":0,"text":"missing words","type":"word","en":"gone","zh":"x"}
        ]}
    """.trimIndent()

    @Test
    fun parsesAllItems() {
        val marks = VocabMarks.parse(json)
        assertEquals(4, marks.size)
        assertEquals(MarkType.Phrase, marks[0].type)
        assertTrue(marks[0].star)
        assertEquals("复习中", marks[0].mastery)
        assertEquals("", marks[1].note)
    }

    @Test
    fun badJsonGivesEmpty() {
        assertTrue(VocabMarks.parse("nope").isEmpty())
        assertTrue(VocabMarks.parse(null).isEmpty())
    }

    @Test
    fun locatesMarksInsideTheSentence() {
        val marks = VocabMarks.parse(json)
        val found = VocabMarks.forSentence(marks, 0, "I am going through a hard time now")
        assertEquals(2, found.size) // 找不到文字的那条被丢掉
        assertEquals(5..29, found[0].first)
        assertEquals(21..24, found[1].first)
        val whole = VocabMarks.forSentence(marks, 1, "It is fine.")
        assertEquals(MarkType.Sentence, whole[0].second.type)
    }
}
