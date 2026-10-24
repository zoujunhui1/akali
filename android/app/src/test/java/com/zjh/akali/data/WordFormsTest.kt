package com.zjh.akali.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFormsTest {
    @Test
    fun normalizeStripsPunctuationAndCase() {
        assertEquals("hello", WordForms.normalize("Hello,"))
        assertEquals("don't", WordForms.normalize("“Don’t"))
        assertEquals("u.s", WordForms.normalize("U.S."))
        assertEquals("", WordForms.normalize("—"))
    }

    @Test
    fun theWordItselfIsAlwaysTriedFirst() {
        assertEquals("running", WordForms.candidates("Running").first())
        assertEquals(emptyList<String>(), WordForms.candidates("..."))
    }

    @Test
    fun possessivesAndCommonEndingsFallBackToTheBaseWord() {
        assertTrue("john" in WordForms.candidates("John's"))
        assertTrue("city" in WordForms.candidates("cities"))
        assertTrue("box" in WordForms.candidates("boxes"))
        assertTrue("cat" in WordForms.candidates("cats"))
        assertTrue("study" in WordForms.candidates("studied"))
        assertTrue("walk" in WordForms.candidates("walked"))
        assertTrue("stop" in WordForms.candidates("stopped"))
        assertTrue("make" in WordForms.candidates("making"))
        assertTrue("run" in WordForms.candidates("running"))
        assertTrue("quick" in WordForms.candidates("quickly"))
    }

    @Test
    fun aWordThatEndsInDoubleSIsNotTreatedAsAPlural() {
        assertTrue("glas" !in WordForms.candidates("glass"))
    }

    @Test
    fun tagCodesBecomeChineseLabels() {
        val e = DictEntry("run", "rʌn", "n. 跑", "zk gk cet4 weird", 202)
        assertEquals(listOf("中考", "高考", "四级"), e.tagLabels)
        assertEquals(listOf("n. 跑"), e.meaningLines)
    }
}
