package com.hindstv.data.source

import com.hindstv.data.stalker.PortalProfile
import java.io.IOException

/** What the user connected: a Stalker/MAC portal, or an M3U playlist / single stream link. */
sealed interface SourceProfile {
    data class Stalker(val portal: PortalProfile) : SourceProfile

    /** [epgUrl] is an optional XMLTV guide; blank means "use the playlist's own url-tvg". */
    data class Playlist(val url: String, val epgUrl: String = "") : SourceProfile
}

data class SourceGenre(val id: String, val title: String, val censored: Boolean = false)

data class SourceChannel(
    val id: String,
    val name: String,
    val number: Int,
    /** Stalker: the portal `cmd`; playlists: the stream URL itself. */
    val cmd: String,
    val logo: String?,
    val genreId: String,
    /** Key used to match guide data (Stalker channel id or XMLTV `tvg-id`). */
    val epgId: String?,
    val censored: Boolean = false,
    val useTmpLink: Boolean = false,
    val hasArchive: Boolean = false,
    val archiveHours: Int = 0,
)

data class SourceContent(
    val genres: List<SourceGenre>,
    val channels: List<SourceChannel>,
    /** XMLTV URL advertised by a playlist header (`url-tvg` / `x-tvg-url`). */
    val epgUrl: String? = null,
)

/** One guide entry for one of our channels. Times are epoch seconds. */
data class Programme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/** A channel as the guide importer needs it. */
data class EpgChannelRef(val channelId: String, val epgId: String?, val name: String)

/**
 * A provider of channels, stream URLs and guide data. Implementations do their own threading
 * (callers may be on the main thread) and throw [IOException]s that `ui/Errors.kt` can explain.
 */
interface ChannelSource {
    /** User-Agent for stream requests. */
    val userAgent: String

    suspend fun load(): SourceContent

    /** A playable URL for a channel's [SourceChannel.cmd], resolved at play time. */
    suspend fun streamUrl(cmd: String): String

    /**
     * Guide data for [channels] overlapping [fromSec]..[toSec], delivered in batches so large
     * guides are never held in memory at once.
     */
    suspend fun loadEpg(
        channels: List<EpgChannelRef>,
        fromSec: Long,
        toSec: Long,
        onBatch: suspend (List<Programme>) -> Unit,
    )
}

class PlaylistException(message: String) : IOException(message)
