package com.hindstv.ui

import com.hindstv.data.pushRecent
import com.hindstv.ui.home.nextEpisodeNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLogicTest {
    @Test
    fun `recent channels are newest first without duplicates`() {
        assertEquals(listOf("b", "a", "c"), pushRecent(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("d", "a"), pushRecent(listOf("a"), "d"))
        assertEquals(listOf("x"), pushRecent(emptyList(), "x"))
    }

    @Test
    fun `recent channels are capped`() {
        val full = (1..20).map { it.toString() }
        val next = pushRecent(full, "new")
        assertEquals(20, next.size)
        assertEquals("new", next.first())
        assertEquals("19", next.last())
    }

    @Test
    fun `next episode is the next number the portal listed`() {
        assertEquals(4, nextEpisodeNumber(3, "1,2,3,4,5"))
        assertEquals(7, nextEpisodeNumber(3, "1, 3, 7"))
        assertNull(nextEpisodeNumber(5, "1,2,3,4,5"))
        assertNull(nextEpisodeNumber(null, "1,2"))
        assertNull(nextEpisodeNumber(1, ""))
    }
}
