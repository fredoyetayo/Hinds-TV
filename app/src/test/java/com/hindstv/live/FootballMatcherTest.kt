package com.hindstv.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballMatcherTest {
    @Test
    fun `competitions and football shows count`() {
        listOf(
            "Live Premier League: Arsenal v Chelsea",
            "Champions League Live",
            "EFL: Leeds v Sunderland",
            "Match of the Day",
            "LaLiga: Real Madrid v Barcelona",
            "Monday Night Football",
            "FA Cup Third Round",
        ).forEach { assertTrue(it, FootballMatcher.isFootball(it)) }
    }

    @Test
    fun `a bare fixture needs football context`() {
        assertTrue(FootballMatcher.isFootball("Arsenal v Chelsea", "Live coverage of the north London clash, kick-off 4.30pm."))
        assertTrue(FootballMatcher.isFootball("Arsenal v Chelsea", null, "Sky Sports Main Event"))
        assertFalse(FootballMatcher.isFootball("Arsenal v Chelsea", null, "BBC One"))
    }

    @Test
    fun `other sports and other programmes don't`() {
        listOf(
            "Live Rugby Union: England v Wales",
            "Live Test Cricket: England v India",
            "World Darts Championship",
            "NFL: Chiefs v Bills",
            "EastEnders",
            "Kramer vs Kramer",
        ).forEach { assertFalse(it, FootballMatcher.isFootball(it)) }
        assertFalse(FootballMatcher.isFootball("England v Wales", "Six Nations rugby from Twickenham", "Sky Sports Action"))
    }
}

class FixtureNameTest {
    @Test
    fun `event channels named after a match`() {
        assertTrue(FootballMatcher.isFixtureName("EPL 03: Newcastle v Arsenal 17:30"))
        assertTrue(FootballMatcher.isFixtureName("UK | Premier League: Spurs vs Leeds"))
        assertFalse(FootballMatcher.isFixtureName("SKY SPORTS PREMIER LEAGUE HD"))
        assertFalse(FootballMatcher.isFixtureName("RUGBY 01: England v Wales"))
        assertFalse(FootballMatcher.isFixtureName("Kramer vs Kramer"))
    }

    @Test
    fun `a kick-off time in the name limits it to around the match`() {
        val name = "EPL 03: Newcastle v Arsenal 17:30"
        assertFalse(FootballMatcher.isFixtureName(name, minuteOfDay = 16 * 60 + 50))
        assertTrue(FootballMatcher.isFixtureName(name, minuteOfDay = 17 * 60 + 5))
        assertTrue(FootballMatcher.isFixtureName(name, minuteOfDay = 19 * 60 + 50))
        assertFalse(FootballMatcher.isFixtureName(name, minuteOfDay = 20 * 60 + 5))
        assertTrue(FootballMatcher.isFixtureName("EPL: Spurs v Leeds 23:45", minuteOfDay = 30)) // past midnight
        assertFalse(FootballMatcher.isFixtureName("EPL: Spurs v Leeds", minuteOfDay = 9 * 60)) // no time: can't tell
        assertTrue(FootballMatcher.isFixtureName("EPL: Spurs v Leeds")) // naming a match is still a match (Watch now)
    }
}
