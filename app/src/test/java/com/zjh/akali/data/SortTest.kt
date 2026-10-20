package com.zjh.akali.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SortTest {
    private fun lesson(id: Long, level: String, number: Int, addedAt: Long) =
        Lesson(id, level, number, "t", null, addedAt, "", true)

    private val lessons = listOf(
        lesson(1, "C1", 6, addedAt = 300),
        lesson(2, "B2", 50, addedAt = 100),
        lesson(3, "B2", 7, addedAt = 200),
        lesson(4, "C1", 33, addedAt = 400),
    )

    private fun codes(order: SortOrder) = lessons.sortedFor(order).map { it.code }

    @Test
    fun numberAscendingUsesNumbersNotStrings() {
        assertEquals(listOf("B2-007", "B2-050", "C1-006", "C1-033"), codes(SortOrder.NUMBER_ASC))
    }

    @Test
    fun numberDescending() {
        assertEquals(listOf("C1-033", "C1-006", "B2-050", "B2-007"), codes(SortOrder.NUMBER_DESC))
    }

    @Test
    fun recentlyAddedFirst() {
        assertEquals(listOf("C1-033", "C1-006", "B2-007", "B2-050"), codes(SortOrder.ADDED_DESC))
    }

    @Test
    fun playModeCyclesThroughAll() {
        var m = PlayMode.LOOP_ALL
        val seen = mutableListOf(m)
        repeat(PlayMode.entries.size) { m = m.next(); seen += m }
        assertEquals(PlayMode.LOOP_ALL, seen.last())
        assertEquals(PlayMode.entries.size, seen.dropLast(1).toSet().size)
    }
}
