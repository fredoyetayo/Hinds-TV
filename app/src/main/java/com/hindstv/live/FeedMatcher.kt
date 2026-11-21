package com.hindstv.live

/**
 * Finds the other feeds of a channel: providers carry the same channel several times
 * ("UK: Sky Sports Main Event FHD", "|UK| SKY SPORTS MAIN EVENT HD", "Sky Sports Main Event (Backup)").
 * Names are reduced to a key without country tags, quality labels and backup markers; numbers stay,
 * because "TNT Sports 1" and "TNT Sports 2" are different channels. Pure, so it is unit-tested.
 */
object FeedMatcher {
    data class Candidate(val id: String, val name: String, val genreId: String)

    private val countryPrefix = Regex("""^\s*(?:[|\[(]\s*[a-z]{2,4}\s*[|\])]|[a-z]{2,4}\s*[:|]|[a-z]{2}\s+-)\s*""")
    private val bracketed = Regex("""[\[(][^\])]*[\])]""")
    private val backup = Regex("""\b(?:backup|back up|bkp|bk|alt|alternative|raw|feed|multi|vip|test)\b\s*\d*""")
    private val quality = Regex(
        """\b(?:uhd|fhd|hd|sd|hq|lq|4k|8k|2160p|1080p|1080i|720p|576p|576i|hevc|h\.?26[45]|x26[45]|50\s?fps|60\s?fps|hdr)\b""",
    )
    private val superscript = Regex("[ᴴᴰᵁˢᴷ⁴ᴿᴬᵂ]+")
    private val symbols = Regex("""[^a-z0-9 ]+""")
    private val spaces = Regex("""\s+""")

    fun key(name: String): String {
        var s = name.lowercase().replace(superscript, " ")
        s = s.replace(countryPrefix, "")
        // "Sky Sports+ (Backup)": bracketed notes are labels, not part of the name.
        s = s.replace(bracketed, " ")
        s = s.replace(symbols, " ")
        s = s.replace(quality, " ").replace(backup, " ")
        return s.replace(spaces, " ").trim()
    }

    /** Other feeds of [current], best first: same group, then the provider's order. */
    fun alternatives(current: Candidate, all: List<Candidate>): List<Candidate> {
        val wanted = key(current.name)
        if (wanted.length < 3) return emptyList()
        return all.asSequence()
            .filter { it.id != current.id && key(it.name) == wanted }
            .sortedBy { if (it.genreId == current.genreId) 0 else 1 } // stable: keeps the provider's order
            .toList()
    }
}
