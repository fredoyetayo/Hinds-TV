package com.hindstv.live

/**
 * What the household watches, learned from live TV: minutes per programme title (normalised, so
 * "Match of the Day" and "MATCH OF THE DAY" count together). Recommendations come from the titles
 * watched most. Pure, so it is unit-tested; kept small (the top [MAX] titles).
 */
class Taste(private val minutes: MutableMap<String, Int> = mutableMapOf()) {
    fun add(title: String, mins: Int = 1) {
        val key = key(title)
        if (key.length < 3) return
        minutes[key] = (minutes[key] ?: 0) + mins
        if (minutes.size > MAX) minutes.entries.minByOrNull { it.value }?.let { minutes.remove(it.key) }
    }

    /** Titles watched for at least [minMinutes], most watched first. */
    fun liked(minMinutes: Int = LIKED_MINUTES): List<String> =
        minutes.entries.filter { it.value >= minMinutes }.sortedByDescending { it.value }.map { it.key }

    fun minutesFor(title: String): Int = minutes[key(title)] ?: 0

    fun encode(): String = minutes.entries.joinToString("\n") { "${it.value}\t${it.key}" }

    companion object {
        const val MAX = 60
        const val LIKED_MINUTES = 15

        fun decode(text: String?): Taste = Taste(
            text.orEmpty().lineSequence().mapNotNull { line ->
                val count = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
                line.substringAfter('\t', "").takeIf { it.isNotBlank() }?.let { it to count }
            }.toMap().toMutableMap(),
        )

        /** "Live: Match of the Day (S12E3)" → "match of the day": live/new/repeat markers and episode numbers go. */
        fun key(title: String): String = title.lowercase()
            .replace(Regex("""\((s\d+\s*e\d+|new|repeat|hd|live)\)|\bs\d+\s*e\d+\b"""), " ")
            .replace(Regex("""^(live|new|premiere)\s*[:\-–]\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

/** Something on now or soon that the household might want. */
data class Pick(val channelId: String, val title: String, val start: Long, val stop: Long, val liked: Boolean)

/**
 * Home's "For you": programmes whose titles you watch most (on now first, then coming up), then
 * what's on now on the channels you use. Pure, so it is unit-tested.
 */
object Recommender {
    data class Slot(val channelId: String, val title: String, val start: Long, val stop: Long)

    fun picks(
        nowSec: Long,
        likedTitles: List<String>,
        airing: List<Slot>,
        upcoming: List<Slot>,
        myChannels: List<String>,
        excludeChannel: String?,
        limit: Int = 12,
    ): List<Pick> {
        val liked = likedTitles.toSet()
        val rank = likedTitles.withIndex().associate { it.value to it.index }
        val mine = myChannels.withIndex().associate { it.value to it.index }
        val out = LinkedHashMap<String, Pick>()
        fun take(s: Slot, isLiked: Boolean) {
            if (s.channelId == excludeChannel || out.size >= limit) return
            out.putIfAbsent("${s.channelId}@${s.start}", Pick(s.channelId, s.title, s.start, s.stop, isLiked))
        }
        // Liked titles on now, your channels first; then liked titles later today, soonest first.
        airing.filter { Taste.key(it.title) in liked }
            .sortedWith(compareBy({ rank[Taste.key(it.title)] }, { mine[it.channelId] ?: Int.MAX_VALUE }))
            .forEach { take(it, true) }
        upcoming.filter { it.start > nowSec && Taste.key(it.title) in liked }
            .sortedWith(compareBy({ it.start }, { mine[it.channelId] ?: Int.MAX_VALUE }))
            .forEach { take(it, true) }
        // Then what's on now on the channels you watch.
        airing.filter { it.channelId in mine }.sortedBy { mine[it.channelId] }.forEach { take(it, false) }
        return out.values.toList()
    }
}
