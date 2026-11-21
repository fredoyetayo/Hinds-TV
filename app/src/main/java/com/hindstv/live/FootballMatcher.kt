package com.hindstv.live

/**
 * Spots football in the guide: competitions and football shows by name, or "Team v Team" titles
 * with a football word nearby. Other sports that also use "v" (rugby, cricket, darts…) are left out.
 * Pure, so it is unit-tested.
 */
object FootballMatcher {
    private val competitions = listOf(
        "football", "soccer", "premier league", "champions league", "europa league", "conference league",
        "efl", "sky bet championship", "league one", "league two", "fa cup", "carabao cup", "league cup",
        "community shield", "la liga", "laliga", "serie a", "bundesliga", "ligue 1", "eredivisie",
        "scottish premiership", "spfl", "primeira liga", "saudi pro league", "mls", "world cup", "euro 20",
        "nations league", "copa del rey", "copa america", "copa libertadores", "afcon", "africa cup of nations",
        "wsl", "women's super league", "match of the day", "super sunday", "monday night football",
        "friday night football", "soccer saturday", "goals show", "matchday live",
    )
    private val otherSports = listOf(
        "rugby", "cricket", "darts", "snooker", "tennis", "golf", "boxing", "ufc", "nfl", "american football",
        "nba", "basketball", "nhl", "hockey", "baseball", "mlb", "f1", "formula 1", "motogp", "racing",
        "netball", "wrestling", "wwe", "aussie rules", "afl", "gaelic", "hurling", "volleyball", "handball",
        "athletics", "cycling", "horse", "greyhound",
    )
    private val fixture = Regex("""\b[\p{L}.'&-]{2,}(?: [\p{L}.'&-]+){0,3} (?:v|vs\.?) [\p{L}.'&-]{2,}""", RegexOption.IGNORE_CASE)

    fun isFootball(title: String, description: String? = null, channelName: String? = null): Boolean {
        val t = title.lowercase()
        val d = description.orEmpty().lowercase()
        val all = "$t $d"
        if (otherSports.any { containsWord(t, it) }) return false
        if (competitions.any { containsWord(t, it) }) return true
        if (!fixture.containsMatchIn(title)) return false
        // A bare "Arsenal v Chelsea": football if the description or channel says so, and no other sport does.
        if (otherSports.any { containsWord(d, it) }) return false
        val context = "$all ${channelName.orEmpty().lowercase()}"
        return competitions.any { containsWord(context, it) } || containsWord(context, "sport") ||
            containsWord(context, "sports") || containsWord(context, "kick-off") || containsWord(context, "kick off")
    }

    /**
     * Event channels named after the match ("EPL 03: Newcastle v Arsenal 17:30"): football when the
     * name is a fixture with a football word, and no other sport. Many such channels have no guide.
     */
    fun isFixtureName(name: String, minuteOfDay: Int? = null): Boolean {
        val n = name.lowercase()
        if (!fixture.containsMatchIn(name) || otherSports.any { containsWord(n, it) }) return false
        val football = competitions.any { containsWord(n, it) } || listOf("epl", "ucl", "uel", "pl", "efl", "spl").any { containsWord(n, it) }
        if (!football || minuteOfDay == null) return football
        // "… 17:30": only from half an hour before kick-off until the match is surely over. Without a
        // time there's no telling whether it's on (event channels keep their names all week), so no.
        val times = kickOff.findAll(name).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList()
        if (times.isEmpty()) return false
        return times.any { k ->
            val since = ((minuteOfDay - k) % DAY + DAY) % DAY // minutes since kick-off, wrapping at midnight
            since <= MATCH_MINUTES || since >= DAY - LEAD_MINUTES
        }
    }

    private val kickOff = Regex("""(?<!\d)([01]?\d|2[0-3])[:.]([0-5]\d)(?!\d)""")
    private const val DAY = 24 * 60
    private const val LEAD_MINUTES = 30
    private const val MATCH_MINUTES = 150

    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = i == 0 || !text[i - 1].isLetterOrDigit()
            val end = i + word.length
            val after = end >= text.length || !text[end].isLetterOrDigit()
            if (before && after) return true
            from = i + 1
        }
    }
}
