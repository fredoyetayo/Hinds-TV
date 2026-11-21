package com.hindstv.live

import com.hindstv.live.MatchFinder.Airing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchFinderTest {
    @Test
    fun `finds the guide programme naming both teams, whatever the spelling`() {
        val airing = listOf(
            Airing("news", "Sky News Today"),
            Airing("sspl", "Live: Arsenal v Manchester City"),
            Airing("tnt", "Live Champions League: Real Madrid v PSV Eindhoven"),
        )
        assertEquals("sspl", MatchFinder.find("Arsenal", "Man City", airing, emptyList()))
        assertEquals("tnt", MatchFinder.find("Real Madrid", "PSV", airing, emptyList()))
    }

    @Test
    fun `falls back to an event channel named after the match`() {
        val names = listOf(Airing("e1", "EPL 01: Spurs v Leeds"), Airing("e2", "EPL 02: Newcastle v Arsenal 17:30"))
        assertEquals("e2", MatchFinder.find("Newcastle", "Arsenal", emptyList(), names))
    }

    @Test
    fun `nothing matching gives null`() {
        assertNull(MatchFinder.find("Arsenal", "Dortmund", listOf(Airing("x", "Arsenal Fan TV")), emptyList()))
    }
}
