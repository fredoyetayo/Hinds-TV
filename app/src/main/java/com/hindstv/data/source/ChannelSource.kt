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
    /** Videos listed up front (playlists). Portals browse videos online instead; see [ChannelSource.hasOnlineVideos]. */
    val videoCategories: List<VideoCategory> = emptyList(),
    val videos: List<VideoItem> = emptyList(),
    val radio: List<RadioStation> = emptyList(),
)

data class VideoCategory(val id: String, val title: String, val censored: Boolean = false)

/** A film, lesson or series from the provider's video library (Stalker VOD or playlist entry). */
data class VideoItem(
    val id: String,
    val categoryId: String,
    val title: String,
    val description: String? = null,
    val poster: String? = null,
    val year: String? = null,
    val durationMin: Int = 0,
    val genres: String? = null,
    val cmd: String,
    /** Series model (a): one item whose episodes are numbers, played with `series=<n>`. */
    val episodeNumbers: List<Int> = emptyList(),
    /** Series whose seasons/episodes must be fetched ([ChannelSource.videoEpisodes]). */
    val isSeries: Boolean = false,
) {
    val hasEpisodes: Boolean get() = isSeries || episodeNumbers.isNotEmpty()
}

/** Episodes of a series stored as one item with `series=[1..N]`. */
fun VideoItem.numberedEpisodes(): List<VideoEpisode> =
    episodeNumbers.map { n -> VideoEpisode(key = "e$n", title = "Episode $n", season = null, number = n, cmd = cmd, series = n) }

data class VideoPage(val items: List<VideoItem>, val hasMore: Boolean)

/** One playable episode. [key] is stable within its series and used for resume positions. */
data class VideoEpisode(
    val key: String,
    val title: String,
    val season: String?,
    val number: Int,
    val cmd: String,
    /** `series=<n>` for create_link (series model a/b); null when [cmd] names the episode itself. */
    val series: Int?,
)

data class RadioStation(val id: String, val name: String, val number: Int, val cmd: String, val logo: String?)

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

    /** True when videos are browsed page by page from the provider rather than listed in [load]. */
    val hasOnlineVideos: Boolean get() = false

    suspend fun videoCategories(): List<VideoCategory> = emptyList()

    /** [page] starts at 1. */
    suspend fun videoPage(categoryId: String, page: Int): VideoPage = VideoPage(emptyList(), hasMore = false)

    suspend fun videoEpisodes(item: VideoItem): List<VideoEpisode> = emptyList()

    /** Online video search ([hasOnlineVideos] sources only). */
    suspend fun searchVideos(query: String): List<VideoItem> = emptyList()

    /** A playable URL for a video or one of its episodes. */
    suspend fun videoUrl(cmd: String, series: Int?): String = cmd

    suspend fun radioUrl(cmd: String): String = cmd
}

class PlaylistException(message: String) : IOException(message)
