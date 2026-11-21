package com.hindstv.live

/**
 * Finds the channel showing a match: a guide programme on at kick-off whose title names both
 * teams, else an event channel named after the match. Team names differ between sources
 * ("Man City" / "Manchester City", "Spurs" / "Tottenham"), so each name becomes a few variants.
 * Pure, so it is unit-tested.
 */
object MatchFinder {
    data class Airing(val channelId: String, val title: String)

    private val aliases = mapOf(
        "man city" to listOf("manchester city", "man city"),
        "manchester city" to listOf("manchester city", "man city"),
        "man utd" to listOf("manchester united", "man utd", "man united"),
        "manchester united" to listOf("manchester united", "man utd", "man united"),
        "spurs" to listOf("tottenham", "spurs"),
        "tottenham" to listOf("tottenham", "spurs"),
        "wolves" to listOf("wolverhampton", "wolves"),
        "newcastle" to listOf("newcastle"),
        "nottm forest" to listOf("nottingham forest", "forest"),
        "atletico madrid" to listOf("atletico", "atlético"),
        "psg" to listOf("paris saint-germain", "psg", "paris sg"),
        "inter" to listOf("inter milan", "inter"),
    )
    private val fillers = setOf("fc", "afc", "cf", "sc", "the", "united", "city", "club", "real", "de", "ac")

    fun variants(team: String): List<String> {
        val t = team.lowercase().replace(Regex("""\b(fc|afc|cf)\b"""), "").trim()
        aliases[t]?.let { return it }
        val words = t.split(Regex("""\s+""")).filter { it.length > 2 && it !in fillers }
        return (listOf(t) + words).distinct()
    }

    fun find(home: String, away: String, airing: List<Airing>, channelNames: List<Airing>): String? {
        val h = variants(home)
        val a = variants(away)
        fun names(text: String): Boolean {
            val t = text.lowercase()
            return h.any { t.contains(it) } && a.any { t.contains(it) }
        }
        return airing.firstOrNull { names(it.title) }?.channelId
            ?: channelNames.firstOrNull { names(it.title) && FootballMatcher.isFixtureName(it.title) }?.channelId
    }
}
