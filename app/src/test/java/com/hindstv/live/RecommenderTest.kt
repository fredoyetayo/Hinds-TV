package com.hindstv.live

import com.hindstv.live.Recommender.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommenderTest {
    @Test
    fun `taste counts titles together and survives encoding`() {
        val t = Taste()
        t.add("Match of the Day", 10)
        t.add("LIVE: Match Of The Day", 10)
        t.add("EastEnders", 3)
        assertEquals(listOf("match of the day"), t.liked(15))
        assertEquals(20, Taste.decode(t.encode()).minutesFor("match of the day"))
    }

    @Test
    fun `liked titles first, on now before later, then your channels`() {
        val now = 1_000L
        val picks = Recommender.picks(
            nowSec = now,
            likedTitles = listOf("match of the day", "the chase"),
            airing = listOf(Slot("bbc1", "Match of the Day", 900, 2000), Slot("sky", "Sky Sports News", 900, 2000), Slot("x", "Random", 900, 2000)),
            upcoming = listOf(Slot("itv", "The Chase", 1500, 2500), Slot("bbc1", "Match of the Day", 900, 2000)),
            myChannels = listOf("sky", "bbc1"),
            excludeChannel = null,
        )
        assertEquals(listOf("bbc1" to true, "itv" to true, "sky" to false), picks.map { it.channelId to it.liked })
    }

    @Test
    fun `the channel you're watching is left out`() {
        val picks = Recommender.picks(0, emptyList(), listOf(Slot("a", "A", 0, 10)), emptyList(), listOf("a"), excludeChannel = "a")
        assertEquals(emptyList<Pick>(), picks)
    }
}
