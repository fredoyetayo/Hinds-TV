package com.hindstv.data.source

import org.xmlpull.v1.XmlPullParser
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Streams an XMLTV guide (`<channel>` then `<programme>` elements) through a pull parser, keeping
 * only programmes for our channels inside the time window. Never builds a DOM: guides can be
 * hundreds of MB.
 *
 * Channels are matched by XMLTV id against the playlist's `tvg-id`, falling back to the guide's
 * display names against our channel names (case-insensitive).
 */
class XmltvParser(private val fromSec: Long, private val toSec: Long) {

    fun parse(parser: XmlPullParser, channels: List<EpgChannelRef>): Sequence<Programme> = sequence {
        val byEpgId = HashMap<String, MutableList<String>>()
        val byName = HashMap<String, MutableList<String>>()
        for (c in channels) {
            c.epgId?.takeIf { it.isNotBlank() }?.let { byEpgId.getOrPut(it.lowercase()) { ArrayList() } += c.channelId }
            byName.getOrPut(c.name.lowercase()) { ArrayList() } += c.channelId
        }
        // XMLTV channel id -> our channel ids
        val resolved = HashMap<String, List<String>>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val names = readChannelNames(parser)
                        if (id != null) {
                            val ours = byEpgId[id.lowercase()]
                                ?: names.firstNotNullOfOrNull { byName[it.lowercase()] }
                            if (ours != null) resolved[id] = ours
                        }
                    }
                    "programme" -> {
                        val channel = parser.getAttributeValue(null, "channel")
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        val ours = channel?.let { resolved[it] ?: byEpgId[it.lowercase()] }
                        if (ours != null && start != null && stop != null && stop > fromSec && start < toSec) {
                            val (title, desc) = readProgrammeText(parser)
                            if (title != null) {
                                for (id in ours) yield(Programme(id, start, stop, title, desc))
                            }
                        } else {
                            skipElement(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun readChannelNames(parser: XmlPullParser): List<String> {
        val names = ArrayList<String>(2)
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                parser.nextText()?.trim()?.takeIf { it.isNotEmpty() }?.let(names::add)
            }
        }
        return names
    }

    private fun readProgrammeText(parser: XmlPullParser): Pair<String?, String?> {
        var title: String? = null
        var desc: String? = null
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title == null) title = parser.nextText()?.trim()
                    "desc" -> if (desc == null) desc = parser.nextText()?.trim()?.take(MAX_DESCRIPTION)
                }
            }
        }
        return title?.ifEmpty { null } to desc?.ifEmpty { null }
    }

    private fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    companion object {
        private const val MAX_DESCRIPTION = 400

        /** XMLTV time: `YYYYMMDDhhmmss [+-]hhmm` (offset optional, UTC if absent). Epoch seconds. */
        fun parseTime(value: String?): Long? {
            val s = value?.trim() ?: return null
            if (s.length < 12 || !s.substring(0, 12).all(Char::isDigit)) return null
            return try {
                val dt = LocalDateTime.of(
                    s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                    s.substring(8, 10).toInt(), s.substring(10, 12).toInt(),
                    if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0,
                )
                val tz = s.substringAfter(' ', "").trim()
                val offset = if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                    val sign = if (tz[0] == '-') -1 else 1
                    ZoneOffset.ofHoursMinutes(sign * tz.substring(1, 3).toInt(), sign * tz.substring(3, 5).toInt())
                } else {
                    ZoneOffset.UTC
                }
                dt.toEpochSecond(offset)
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}
