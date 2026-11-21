package com.hindstv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupOrderTest {
    private val groups = listOf("news", "football", "movies", "kids", "sport-uk")

    @Test
    fun `pinned groups come out first in the provider's order`() {
        val (top, rest) = GroupOrder.pinFirst(groups, setOf("sport-uk", "football")) { it }
        assertEquals(listOf("football", "sport-uk"), top)
        assertEquals(listOf("news", "movies", "kids"), rest)
    }

    @Test
    fun `nothing pinned, or pins for groups that are gone, change nothing`() {
        assertEquals(emptyList<String>() to groups, GroupOrder.pinFirst(groups, emptySet()) { it })
        assertEquals(emptyList<String>() to groups, GroupOrder.pinFirst(groups, setOf("old-group")) { it })
    }
}
