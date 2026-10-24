package com.zjh.akali.ui.vocab

import com.zjh.akali.data.MarkState
import com.zjh.akali.data.VocabLesson
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewDeckTest {
    private fun lesson(code: String, downloaded: Boolean, vararg labels: String) = VocabLesson(
        code = code, unit = 1, number = 1, title = "Topic $code", pages = "[5]",
        entries = labels.joinToString(prefix = "[", postfix = "]") { """{"label":"$it","text":"word $it","page":5,"audio":"audio/5-$it.mp3"}""" },
        bundleSha = null, bundleSize = 0, downloaded = downloaded, addedAt = 0,
    )

    @Test
    fun onlyFavoritesOfDownloadedTopicsAreInTheDeck() {
        val lessons = listOf(lesson("V01-01", true, "1", "2", "3"), lesson("V01-02", false, "1", "2"))
        val marks = mapOf(
            "V01-01/5-1" to MarkState.FAVORITE,
            "V01-01/5-2" to MarkState.MASTERED,
            "V01-02/5-1" to MarkState.FAVORITE,
            "V01-02/5-2" to MarkState.FAVORITE,
        )
        assertEquals(listOf("V01-01/5-1"), reviewDeck(lessons, marks))
        assertEquals(2, skippedFavorites(lessons, marks))
    }

    @Test
    fun deckKeepsTheOrderOfTheBook() {
        val lessons = listOf(lesson("V01-01", true, "1", "2", "3"))
        val marks = mapOf("V01-01/5-3" to MarkState.FAVORITE, "V01-01/5-1" to MarkState.FAVORITE)
        assertEquals(listOf("V01-01/5-1", "V01-01/5-3"), reviewDeck(lessons, marks))
    }

    @Test
    fun aWordMarkedKnownLeavesTheDeckNextTime() {
        val lessons = listOf(lesson("V01-01", true, "1", "2"))
        val before = mapOf("V01-01/5-1" to MarkState.FAVORITE, "V01-01/5-2" to MarkState.FAVORITE)
        val after = before + ("V01-01/5-1" to MarkState.MASTERED)
        assertEquals(2, reviewDeck(lessons, before).size)
        assertEquals(listOf("V01-01/5-2"), reviewDeck(lessons, after))
    }

    @Test
    fun noFavoritesMeansAnEmptyDeck() {
        assertEquals(emptyList<String>(), reviewDeck(listOf(lesson("V01-01", true, "1")), emptyMap()))
        assertEquals(0, skippedFavorites(listOf(lesson("V01-01", false, "1")), emptyMap()))
    }

    @Test
    fun languageRetryOnlyForLanguageErrorsAndWhileAttemptsRemain() {
        assertEquals(true, shouldTryNextLanguage(error = 13, attempt = 0, discarded = false))
        assertEquals(true, shouldTryNextLanguage(error = 12, attempt = 2, discarded = false))
        assertEquals(false, shouldTryNextLanguage(error = 13, attempt = 3, discarded = false)) // 4 种都试过了
        assertEquals(false, shouldTryNextLanguage(error = 7, attempt = 0, discarded = false)) // 没听清，不是语言问题
        assertEquals(false, shouldTryNextLanguage(error = 13, attempt = 0, discarded = true)) // 用户已经取消
    }
}
