package com.zjh.akali.ui.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingTextTest {
    private fun word(text: String, at: Int) = ReadingText.wordAt(text, at)?.let { text.substring(it.first, it.last + 1) }

    @Test
    fun joinKeepsEachSentencesPosition() {
        val p = ReadingText.join(listOf("It was dark.", "The wind blew."))
        assertEquals("It was dark. The wind blew.", p.text)
        assertEquals(listOf(0..11, 13..26), p.ranges)
        assertEquals(0, p.sentenceIndexAt(5))
        assertEquals(1, p.sentenceIndexAt(20))
        assertEquals(1, p.sentenceIndexAt(12)) // the space between belongs to the later one
    }

    @Test
    fun aTapPicksTheWholeWord() {
        val t = "The quick brown fox."
        assertEquals("quick", word(t, 6))
        assertEquals("quick", word(t, 4))
        assertEquals("quick", word(t, 8))
        assertEquals("fox", word(t, 19)) // the full stop after a word still means that word
        assertEquals("The", word(t, 0))
    }

    @Test
    fun apostrophesAndHyphensInsideAWordStayInIt() {
        val t = "I don't like well-known songs."
        assertEquals("don't", word(t, 4))
        assertEquals("well-known", word(t, 15))
        assertEquals("don’t", word("I don’t know", 4))
    }

    @Test
    fun tappingSpaceOrOnlyPunctuationFindsNothing() {
        assertNull(word("one  two", 4))
        assertNull(word("— —", 1))
        assertNull(word("", 0))
        assertEquals("quoted", word("\"quoted\" text", 1))
    }

    @Test
    fun blankReplacesWholeWordsIgnoringCase() {
        assertEquals("The ＿＿＿＿ blew. Windy day.", ReadingText.blank("The wind blew. Windy day.", "wind")) // Windy is a different word
        assertEquals("＿＿＿＿ is here and ＿＿＿＿ is there.", ReadingText.blank("Wind is here and wind is there.", "wind"))
        assertEquals("She ＿＿＿＿ the door.", ReadingText.blank("She opened the door.", "opened"))
        assertEquals("nothing to blank", ReadingText.blank("nothing to blank", "zebra"))
        assertEquals("I ＿＿＿＿ go.", ReadingText.blank("I don't go.", "don't"))
    }

    @Test
    fun readingTimeAndProgress() {
        assertEquals(1, ReadingText.minutes(50))
        assertEquals(5, ReadingText.minutes(1000))
        assertEquals(6, ReadingText.minutes(1001))
        assertEquals(0, ReadingText.percent(0, 10))
        assertEquals(100, ReadingText.percent(9, 10))
        assertEquals(0, ReadingText.percent(0, 1))
    }

    @Test
    fun singleWordOnlyWhenTheSelectionIsExactlyOneWord() {
        val t = "It was a well-known lighthouse, quiet."
        assertEquals(9..18, ReadingText.singleWord(t, 9, 19))            // well-known
        assertEquals(9..18, ReadingText.singleWord(t, 8, 20))            // spaces around do not matter
        assertEquals(20..29, ReadingText.singleWord(t, 20, 31))          // trailing comma does not matter
        assertNull(ReadingText.singleWord(t, 3, 10))                     // "was a w" is more than a word
        assertNull(ReadingText.singleWord(t, 0, 6))                      // two words
        assertNull(ReadingText.singleWord(t, 12, 15))                    // inside a word
        assertNull(ReadingText.singleWord(t, 5, 5))
    }
}
