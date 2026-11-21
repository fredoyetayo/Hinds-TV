package com.hindstv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil

/**
 * Turns a provider's short live HLS playlist into a longer one that keeps every segment since
 * playback began (up to [maxWindowS]), so the player can pause and rewind live TV. Segments are
 * kept on disk by the player's cache, so going back plays from the stick, not the provider.
 *
 * Only plain live media playlists are rewritten: master playlists, VOD/event playlists, encrypted or
 * byte-range streams pass through untouched ([merge] returns null). Pure, so it is unit-tested.
 */
@OptIn(UnstableApi::class)
class HlsWindow(private val maxWindowS: Double = 20 * 60.0, private val firstKeep: Int = 3) {
    private class Entry(val seq: Long, val duration: Double, val tags: List<String>, val uri: String, val discontinuity: Boolean, val dnum: Long)

    private val entries = ArrayDeque<Entry>()
    private var version = 3
    private var targetDuration = 6
    private var independent = false
    private var map: String? = null
    private var lastSeq = -1L

    /** Seconds of the stream the window holds. */
    @Volatile var durationS = 0.0
        private set

    /** Merges a fetched playlist. Returns the rewritten playlist and the URIs of new segments, or null to pass through. */
    fun merge(text: String, baseUrl: String): Pair<String, List<String>>? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return null
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-ENDLIST") || it.startsWith("#EXT-X-BYTERANGE") }) return null
        if (lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE") }) return null
        if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) return null

        var mediaSeq = 0L
        var dseq = 0L
        val parsed = mutableListOf<Entry>()
        var pending = mutableListOf<String>()
        var duration = -1.0
        var discontinuity = false
        var d = 0L
        var seenSegments = 0
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXT-X-VERSION:") -> version = maxOf(version, line.substringAfter(':').toIntOrNull() ?: 3)
                line.startsWith("#EXT-X-TARGETDURATION:") -> targetDuration = maxOf(1, line.substringAfter(':').toIntOrNull() ?: targetDuration)
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSeq = line.substringAfter(':').toLongOrNull() ?: 0
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") -> {
                    dseq = line.substringAfter(':').toLongOrNull() ?: 0
                    d = dseq
                }
                line == "#EXT-X-INDEPENDENT-SEGMENTS" -> independent = true
                line.startsWith("#EXT-X-MAP:") -> map = absolutiseAttribute(line, baseUrl)
                line.startsWith("#EXT-X-KEY") -> Unit // METHOD=NONE
                line == "#EXT-X-DISCONTINUITY" -> {
                    discontinuity = true
                    pending += line
                }
                line.startsWith("#EXTINF:") -> {
                    duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return null
                    pending += line
                }
                line.startsWith("#") -> if (!line.startsWith("#EXT-X-")) Unit else if (isSegmentTag(line)) pending += line
                else -> {
                    if (duration < 0) return null
                    if (discontinuity) d++
                    parsed += Entry(mediaSeq + seenSegments, duration, pending, resolve(baseUrl, line), discontinuity, d)
                    seenSegments++
                    pending = mutableListOf()
                    duration = -1.0
                    discontinuity = false
                }
            }
        }
        if (parsed.isEmpty()) return render() to emptyList()

        // The provider started again (sequence went back): start the window afresh.
        if (lastSeq >= 0 && parsed.last().seq < lastSeq - 2) {
            entries.clear()
            lastSeq = -1
        }
        val fresh = if (lastSeq < 0) parsed.takeLast(firstKeep) else parsed.filter { it.seq > lastSeq }
        val firstLoad = lastSeq < 0
        fresh.forEach { entries.addLast(it) }
        if (fresh.isNotEmpty()) lastSeq = fresh.last().seq
        var total = entries.sumOf { it.duration }
        while (entries.size > 1 && total > maxWindowS) total -= entries.removeFirst().duration
        durationS = total
        return render() to if (firstLoad) emptyList() else fresh.map { it.uri }
    }

    private fun render(): String = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:").append(version).append('\n')
        val longest = entries.maxOfOrNull { kotlin.math.ceil(it.duration).toInt() } ?: targetDuration
        append("#EXT-X-TARGETDURATION:").append(maxOf(targetDuration, longest)).append('\n')
        val first = entries.firstOrNull()
        append("#EXT-X-MEDIA-SEQUENCE:").append(first?.seq ?: 0).append('\n')
        if (first != null) {
            append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(first.dnum - if (first.discontinuity) 1 else 0).append('\n')
        }
        if (independent) append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        map?.let { append(it).append('\n') }
        for (e in entries) {
            e.tags.forEach { append(it).append('\n') }
            append(e.uri).append('\n')
        }
    }

    private fun isSegmentTag(line: String) = line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("#EXT-X-GAP") ||
        line.startsWith("#EXT-X-BITRATE")

    private fun absolutiseAttribute(line: String, baseUrl: String): String =
        Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${resolve(baseUrl, it.groupValues[1])}\"" }

    private fun resolve(base: String, ref: String): String = UriUtil.resolve(base, ref)
}
