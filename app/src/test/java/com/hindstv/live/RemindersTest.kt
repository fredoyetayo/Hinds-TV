package com.hindstv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val r = Reminder("ch:7", start = 10_000, stop = 16_000, title = "Arsenal v Chelsea")

    @Test
    fun `encodes and decodes, skipping broken lines`() {
        val text = Reminder.encode(listOf(r, r.copy(channelId = "x", title = "Tab\\there")))
        val back = Reminder.decode(text + "\nbroken line")
        assertEquals(2, back.size)
        assertEquals(r, back[0])
        assertEquals(emptyList<Reminder>(), Reminder.decode(null))
    }

    @Test
    fun `due from a minute before until 15 minutes in`() {
        assertFalse(Reminder.isDue(r, 10_000 - 61))
        assertTrue(Reminder.isDue(r, 10_000 - 60))
        assertTrue(Reminder.isDue(r, 10_000 + 14 * 60))
        assertFalse(Reminder.isDue(r, 10_000 + 15 * 60))
        assertTrue(Reminder.isExpired(r, 10_000 + 15 * 60))
    }

    @Test
    fun `a short programme expires when it ends`() {
        val short = r.copy(stop = 10_300)
        assertTrue(Reminder.isExpired(short, 10_300))
    }
}
