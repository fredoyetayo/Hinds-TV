package com.hindstv.data.source

/**
 * Parses extended M3U playlists line by line (never the whole file as one string):
 * `#EXTM3U url-tvg="…"`, `#EXTINF:-1 tvg-id="…" tvg-logo="…" group-title="…",Name`, `#EXTGRP:`,
 * then the stream URL. Bare URL lines without `#EXTINF` are accepted too.
 */
object M3uParser {
    data class Entry(
        val name: String,
        val url: String,
        val attributes: Map<String, String>,
        val group: String?,
    )

    data class Playlist(val header: Map<String, String>, val entries: List<Entry>) {
        val epgUrl: String?
            get() = (header["url-tvg"] ?: header["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifEmpty { null }
    }

    fun parse(lines: Sequence<String>): Playlist {
        var header = emptyMap<String, String>()
        val entries = ArrayList<Entry>()
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> header = parseAttributes(line.removePrefix("#EXTM3U"))
                line.startsWith("#EXTINF:") -> {
                    pendingInfo = line.removePrefix("#EXTINF:")
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:") -> pendingGroup = line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#") -> Unit // #EXTVLCOPT and other directives
                else -> {
                    entries += toEntry(pendingInfo, pendingGroup, line)
                    pendingInfo = null
                    pendingGroup = null
                }
            }
        }
        return Playlist(header, entries)
    }

    private fun toEntry(info: String?, extGroup: String?, url: String): Entry {
        if (info == null) return Entry(nameFromUrl(url), url, emptyMap(), extGroup)
        val comma = indexOfUnquotedComma(info)
        val attrsPart = if (comma >= 0) info.substring(0, comma) else info
        val title = if (comma >= 0) info.substring(comma + 1).trim() else ""
        val attrs = parseAttributes(attrsPart)
        val name = title.ifEmpty { attrs["tvg-name"] ?: nameFromUrl(url) }
        return Entry(name, url, attrs, attrs["group-title"]?.ifBlank { null } ?: extGroup)
    }

    /** `key="value with spaces, commas" key2=value2` → map (keys lower-cased). */
    internal fun parseAttributes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            val keyStart = i
            while (i < n && text[i] != '=' && !text[i].isWhitespace()) i++
            val key = text.substring(keyStart, i)
            if (i >= n || text[i] != '=') continue
            i++ // '='
            val value = if (i < n && (text[i] == '"' || text[i] == '\'')) {
                val quote = text[i++]
                val start = i
                while (i < n && text[i] != quote) i++
                text.substring(start, i).also { i++ }
            } else {
                val start = i
                while (i < n && !text[i].isWhitespace()) i++
                text.substring(start, i)
            }
            if (key.isNotEmpty()) out[key.lowercase()] = value.trim()
        }
        return out
    }

    private fun indexOfUnquotedComma(s: String): Int {
        var quote: Char? = null
        for ((i, c) in s.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == ',' -> return i
            }
        }
        return -1
    }

    enum class Kind { Live, Video, Radio }

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "flv", "mpg", "mpeg")
    private val audioExtensions = setOf("mp3", "aac", "ogg", "oga", "opus", "flac", "m4a", "wav")
    private val radioGroup = Regex("""\bradio\b""", RegexOption.IGNORE_CASE)

    /**
     * Playlists mix live channels, videos and radio. Explicit markers win (`radio="true"`, Xtream
     * `/movie/` and `/series/` paths), then the file extension, then a "Radio" group name.
     */
    fun kind(entry: Entry): Kind {
        if (entry.attributes["radio"].equals("true", ignoreCase = true)) return Kind.Radio
        val path = entry.url.substringBefore('?').substringBefore('#').lowercase()
        if ("/movie/" in path || "/series/" in path) return Kind.Video
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext in videoExtensions) return Kind.Video
        if (ext in audioExtensions) return Kind.Radio
        if (entry.group != null && radioGroup.containsMatchIn(entry.group)) return Kind.Radio
        return Kind.Live
    }

    fun nameFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').substringBeforeLast('.').ifBlank { "Stream" }
}
