package com.hindstv.data.source

import android.util.Xml
import com.hindstv.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * Channels from an M3U/M3U8 playlist URL, or a single stream link (HLS `.m3u8`, `.ts`, …) shown
 * as one channel. Guide data comes from an XMLTV URL: the user's, or the playlist's `url-tvg`.
 */
class PlaylistSource(
    private val http: OkHttpClient,
    val profile: SourceProfile.Playlist,
    /** XMLTV URL discovered in the playlist header on the last sync. */
    private val discoveredEpgUrl: () -> String?,
) : ChannelSource {

    override val userAgent: String = USER_AGENT

    override suspend fun load(): SourceContent = withContext(Dispatchers.IO) {
        val url = profile.url.trim()
        if (url.toHttpUrlOrNull() == null) throw PlaylistException("That doesn't look like a web link.")
        http.newCall(request(url)).await().use { response ->
            if (!response.isSuccessful) throw PlaylistException("The link returned an error (HTTP ${response.code}).")
            val source = response.body.source()
            source.request(PROBE_BYTES)
            val head = source.buffer.snapshot(minOf(source.buffer.size, PROBE_BYTES).toInt()).utf8()
            val start = head.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            when {
                start.startsWith("#EXTM3U") || start.startsWith("#EXTINF") ->
                    // A playlist of segments/variants is one HLS stream, not a channel list.
                    if ("#EXT-X-" in head) singleStream(url) else playlist(source)
                start.startsWith("<") -> throw PlaylistException("That link is a web page, not a playlist or stream.")
                else -> singleStream(url) // a video stream (TS, MP4…)
            }
        }
    }

    private fun singleStream(url: String) = SourceContent(
        genres = emptyList(),
        channels = listOf(
            SourceChannel(id = "stream", name = M3uParser.nameFromUrl(url), number = 1, cmd = url, logo = null, genreId = "", epgId = null),
        ),
    )

    private fun playlist(source: BufferedSource): SourceContent {
        val parsed = M3uParser.parse(generateSequence { source.readUtf8Line() })
        if (parsed.entries.isEmpty()) throw PlaylistException("The playlist has no channels.")
        val genres = LinkedHashMap<String, SourceGenre>()
        val videoCategories = LinkedHashMap<String, VideoCategory>()
        val usedIds = HashSet<String>()
        val channels = ArrayList<SourceChannel>()
        val videos = ArrayList<VideoItem>()
        val radio = ArrayList<RadioStation>()
        for ((index, e) in parsed.entries.withIndex()) {
            val group = e.group?.trim().orEmpty()
            val tvgId = e.attributes["tvg-id"]?.ifBlank { null }
            val logo = e.attributes["tvg-logo"]?.ifBlank { null }
            // Stable across refreshes (favourites and resume positions are keyed by it) without
            // depending on tokenised URLs.
            var id = "m:${tvgId ?: e.name}|$group"
            var n = 2
            while (!usedIds.add(id)) id = "m:${tvgId ?: e.name}|$group#${n++}"
            when (M3uParser.kind(e)) {
                M3uParser.Kind.Video -> {
                    val categoryId = "v:$group"
                    videoCategories.getOrPut(categoryId) { VideoCategory(categoryId, group.ifEmpty { "Videos" }) }
                    videos += VideoItem(id = id, categoryId = categoryId, title = e.name, poster = logo, cmd = e.url)
                }
                M3uParser.Kind.Radio -> radio += RadioStation(id, e.name, radio.size + 1, e.url, logo)
                M3uParser.Kind.Live -> {
                    val genreId = if (group.isEmpty()) "" else "g:$group"
                    if (group.isNotEmpty()) genres.getOrPut(genreId) { SourceGenre(genreId, group) }
                    channels += SourceChannel(
                        id = id,
                        name = e.name,
                        number = e.attributes["tvg-chno"]?.toIntOrNull() ?: (index + 1),
                        cmd = e.url,
                        logo = logo,
                        genreId = genreId,
                        epgId = tvgId ?: e.attributes["tvg-name"],
                        hasArchive = e.attributes.containsKey("catchup") || e.attributes.containsKey("tvg-rec"),
                    )
                }
            }
        }
        return SourceContent(
            genres = genres.values.toList(),
            channels = channels,
            epgUrl = parsed.epgUrl,
            videoCategories = videoCategories.values.toList(),
            videos = videos,
            radio = radio,
        )
    }

    override suspend fun streamUrl(cmd: String): String = cmd

    override suspend fun loadEpg(
        channels: List<EpgChannelRef>,
        fromSec: Long,
        toSec: Long,
        onBatch: suspend (List<Programme>) -> Unit,
    ) {
        val epgUrl = profile.epgUrl.trim().ifEmpty { discoveredEpgUrl()?.trim().orEmpty() }
        if (epgUrl.toHttpUrlOrNull() == null) return
        withContext(Dispatchers.IO) {
            http.newCall(request(epgUrl)).await().use { response ->
                if (!response.isSuccessful) throw PlaylistException("The TV guide link returned HTTP ${response.code}.")
                val raw = BufferedInputStream(response.body.byteStream(), 64 * 1024)
                raw.mark(2)
                val gzip = raw.read() == 0x1f && raw.read() == 0x8b
                raw.reset()
                val input = if (gzip) GZIPInputStream(raw, 64 * 1024) else raw
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                for (batch in XmltvParser(fromSec, toSec).parse(parser, channels).chunked(1_000)) onBatch(batch)
            }
        }
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    private companion object {
        const val PROBE_BYTES = 8_192L
        const val USER_AGENT = "HindsTV/0.3 (Linux; Android; Fire TV)"
    }
}
