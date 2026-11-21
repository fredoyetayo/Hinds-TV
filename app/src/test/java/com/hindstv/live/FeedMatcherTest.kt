package com.hindstv.live

import com.hindstv.live.FeedMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedMatcherTest {
    @Test
    fun `country tags, quality and backup labels don't change the key`() {
        val key = FeedMatcher.key("Sky Sports Main Event")
        listOf(
            "UK: Sky Sports Main Event FHD",
            "|UK| SKY SPORTS MAIN EVENT HD",
            "[UK] Sky Sports Main Event (Backup)",
            "Sky Sports Main Event 4K",
            "UK - Sky Sports Main Event HEVC",
            "Sky Sports Main Event ᴴᴰ",
            "Sky Sports Main Event Backup 2",
        ).forEach { assertEquals(it, key, FeedMatcher.key(it)) }
    }

    @Test
    fun `numbered channels stay different`() {
        assert(FeedMatcher.key("TNT Sports 1 HD") != FeedMatcher.key("TNT Sports 2 HD"))
    }

    @Test
    fun `alternatives come from the same group first, in the provider's order`() {
        val current = Candidate("1", "UK: Sky Sports Premier League HD", "uk-sport")
        val all = listOf(
            current,
            Candidate("2", "Sky Sports Premier League (Backup)", "backups"),
            Candidate("3", "UK: Sky Sports Premier League FHD", "uk-sport"),
            Candidate("4", "UK: Sky Sports Football HD", "uk-sport"),
            Candidate("5", "|UK| SKY SPORTS PREMIER LEAGUE 4K", "uk-4k"),
        )
        assertEquals(listOf("3", "2", "5"), FeedMatcher.alternatives(current, all).map { it.id })
    }

    @Test
    fun `very short names have no alternatives`() {
        val current = Candidate("1", "HD", "g")
        assertEquals(emptyList<Candidate>(), FeedMatcher.alternatives(current, listOf(current, Candidate("2", "FHD", "g"))))
    }
}
