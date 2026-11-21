package com.hindstv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.hindstv.core.Diagnostics
import com.hindstv.data.stalker.PortalProfile
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns the app's single ExoPlayer. Channel changes reuse it (setMediaItem + prepare) so decoders
 * and connections are reused — never release/rebuild per zap. Main thread only.
 *
 * Tuning choices (docs/RESEARCH.md §8):
 * - small live buffers and a byte cap sized for a 2 GB stick;
 * - extension renderers ON (platform decoders first, FFmpeg fills gaps like MP2 audio when the
 *   ffmpeg-audio module is present);
 * - TS extraction tolerant of open-GOP broadcast streams;
 * - live streams reported as unknown length so a bogus Content-Length can't trigger a seek to the end;
 *   videos use the plain HTTP source so they can seek.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(private val context: Context, http: OkHttpClient) {

    private val streamHttp = http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    private val httpFactory = OkHttpDataSource.Factory(streamHttp).setUserAgent(PortalProfile.DEFAULT_USER_AGENT)
    private var userAgent = PortalProfile.DEFAULT_USER_AGENT

    /** The live channel being recorded for pause/rewind, if any (see [TimeshiftSession]). */
    @Volatile private var session: TimeshiftSession? = null
    private var sessionCount = 0

    /** Where in the recording the current media item started. */
    private var itemStartOffset = 0L

    /** Live HLS with pause/rewind (see [HlsDvr]), and how far behind the edge "live" plays. */
    private var dvr: HlsDvr? = null
    private var liveGapMs = -1L

    /** Segment cache for HLS rewind: a fresh folder each run; old runs are deleted in the background. */
    private val dvrCache: SimpleCache by lazy {
        val root = context.cacheDir.resolve("hls-dvr")
        val dir = root.resolve(System.currentTimeMillis().toString())
        Thread({ root.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() } }, "HindsDvrCleanup").start()
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_TIMESHIFT_BYTES), StandaloneDatabaseProvider(context))
    }

    private var built = false
    private lateinit var liveSources: DefaultMediaSourceFactory
    private lateinit var vodSources: DefaultMediaSourceFactory
    private lateinit var timeshiftSources: DefaultMediaSourceFactory

    val player: ExoPlayer by lazy { built = true; build() }

    /**
     * Who is playing (live TV, videos or radio). Each screen's player listener ignores events
     * while someone else owns the one player.
     */
    var owner: Any? = null
        private set

    fun setUserAgent(userAgent: String) {
        this.userAgent = userAgent
        httpFactory.setUserAgent(userAgent)
    }

    /**
     * Plays [url]. Live streams and radio are endless ([vod] false); videos have a real length so
     * they can seek and resume from [startMs].
     */
    fun play(url: String, owner: Any?, vod: Boolean = false, startMs: Long = 0, timeshift: Boolean = false) {
        this.owner = owner
        Diagnostics.add("player", "${if (vod) "video" else "stream"}: $url")
        val player = player
        if (session != null || dvr != null) {
            player.stop() // let go of the old recording before its files go
            closeSession()
        }
        val recording = if (timeshift && !vod && !isHls(url)) startSession(url) else null
        if (recording != null) {
            playRecording(recording, offset = 0L)
            player.playWhenReady = true
            return
        }
        if (timeshift && !vod && isHls(url) && context.cacheDir.usableSpace / 2 >= MIN_TIMESHIFT_BYTES) {
            val hls = HlsDvr(dvrCache, httpFactory)
            dvr = hls
            liveGapMs = -1
            val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            player.setMediaSource(HlsMediaSource.Factory(hls).setAllowChunklessPreparation(true).createMediaSource(item))
            player.prepare()
            player.playWhenReady = true
            return
        }
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val source = (if (vod) vodSources else liveSources).createMediaSource(item)
        player.setMediaSource(source, startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    /** Stops playback and closes the stream connection (providers allow one stream per account). */
    fun stop() {
        if (!built) return
        player.stop()
        player.clearMediaItems()
        closeSession()
    }

    // --- Live pause and rewind -------------------------------------------------------------------

    /** True while live TV is being recorded, so it can pause and rewind. */
    val canTimeshift: Boolean get() = session != null || dvr?.rewriting == true

    /** HLS: the player's distance from the end of the window when it plays "live". */
    private fun hlsGap(): Long {
        val gap = player.duration - player.currentPosition
        if (liveGapMs < 0 && player.isPlaying && player.duration > 0) liveGapMs = gap
        return gap
    }

    /** How far behind live the picture is (grows while paused). 0 at the live edge. */
    fun behindLiveMs(): Long {
        if (dvr != null) {
            if (!built || player.duration <= 0) return 0
            val gap = hlsGap()
            return if (liveGapMs < 0) 0 else (gap - liveGapMs).coerceAtLeast(0)
        }
        val s = session ?: return 0
        if (!built) return 0
        val live = s.buffer.lastWriteAt
        val playhead = s.buffer.timeAt(itemStartOffset) + player.currentPosition
        return (live - playhead).coerceAtLeast(0)
    }

    /** How far back the recording goes from live. */
    fun recordedMs(): Long {
        if (dvr != null) return if (built) player.duration.coerceAtLeast(0) else 0
        val s = session ?: return 0
        return (s.buffer.lastWriteAt - s.buffer.oldestTime).coerceAtLeast(0)
    }

    /** Moves the picture [deltaMs] (negative rewinds). Past the live edge plays live. */
    fun seekLive(deltaMs: Long) {
        if (dvr != null) {
            val behind = behindLiveMs() - deltaMs
            if (behind <= LIVE_EDGE_MS || liveGapMs < 0) player.seekToDefaultPosition()
            else player.seekTo((player.duration - liveGapMs - behind).coerceAtLeast(0))
            return
        }
        val s = session ?: return
        val behind = behindLiveMs() - deltaMs
        if (behind <= LIVE_EDGE_MS) {
            playRecording(s, s.buffer.liveOffset)
            return
        }
        val target = s.buffer.lastWriteAt - behind.coerceAtMost(recordedMs())
        playRecording(s, s.buffer.offsetAt(target))
    }

    fun goLive() {
        if (dvr != null) {
            player.seekToDefaultPosition()
            return
        }
        val s = session ?: return
        playRecording(s, s.buffer.liveOffset)
    }

    private fun startSession(url: String): TimeshiftSession? {
        val dir = context.cacheDir.resolve("timeshift")
        dir.mkdirs()
        // Leave the stick room: at most ~600 MB (about 10 minutes of HD), never more than half the free space.
        val maxBytes = minOf(MAX_TIMESHIFT_BYTES, dir.usableSpace / 2)
        if (maxBytes < MIN_TIMESHIFT_BYTES) {
            Diagnostics.add("timeshift", "not enough free space to rewind live TV")
            return null
        }
        return TimeshiftSession(++sessionCount, url, userAgent, streamHttp, dir, maxBytes).also {
            session = it
            it.start()
        }
    }

    private fun playRecording(s: TimeshiftSession, offset: Long) {
        itemStartOffset = offset
        val item = MediaItem.fromUri("timeshift://${s.id}/$offset")
        player.setMediaSource(timeshiftSources.createMediaSource(item))
        player.prepare()
    }

    private fun closeSession() {
        dvr?.close()
        dvr = null
        session?.close()
        session = null
        itemStartOffset = 0
    }

    val hasMedia: Boolean get() = built && player.mediaItemCount > 0

    /** The audio and subtitle tracks of what is playing now. */
    fun trackChoices(): TrackChoices {
        if (!built) return TrackChoices(emptyList(), emptyList(), subtitlesOff = true)
        val groups = player.currentTracks.groups
        val audio = mutableListOf<TrackOption>()
        val subtitles = mutableListOf<TrackOption>()
        groups.forEachIndexed { g, group ->
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                val f = group.getTrackFormat(t)
                val id = "$g:$t"
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackOption(
                        id, TrackLabels.audio(f.language, f.label, f.sampleMimeType, f.channelCount, audio.size), group.isTrackSelected(t),
                    )
                    C.TRACK_TYPE_TEXT -> subtitles += TrackOption(
                        id, TrackLabels.subtitle(f.language, f.label, subtitles.size), group.isTrackSelected(t),
                    )
                }
            }
        }
        return TrackChoices(audio, subtitles, subtitlesOff = subtitles.none { it.selected })
    }

    /**
     * Picks a track by [TrackOption.id] ([id] null turns subtitles off). Its language becomes the
     * preference too, so the next channel or video starts in the same language when it has it.
     */
    fun selectTrack(type: Int, id: String?) {
        if (!built) return
        val params = player.trackSelectionParameters.buildUpon()
        if (id == null) {
            params.clearOverridesOfType(type).setTrackTypeDisabled(type, true)
        } else {
            val (g, t) = id.split(':').map { it.toInt() }
            val group = player.currentTracks.groups.getOrNull(g) ?: return
            if (group.type != type || t !in 0 until group.length) return
            params.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            group.getTrackFormat(t).language?.let {
                if (type == C.TRACK_TYPE_AUDIO) params.setPreferredAudioLanguage(it) else params.setPreferredTextLanguage(it)
            }
        }
        player.trackSelectionParameters = params.build()
    }

    private fun build(): ExoPlayer {
        val liveDataSourceFactory = DataSource.Factory { LiveDataSource(httpFactory.createDataSource()) }
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true) // seekable MP3/AAC/TS videos without an index
        liveSources = DefaultMediaSourceFactory(liveDataSourceFactory, extractors)
        vodSources = DefaultMediaSourceFactory(httpFactory, extractors)
        timeshiftSources = DefaultMediaSourceFactory(DataSource.Factory { TimeshiftDataSource { session } }, extractors)
        val mediaSourceFactory = liveSources
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                // Start the picture after half a second of media: most of a zap's wait. Live TS
                // rarely needs more; a stall falls back to the longer after-rebuffer threshold.
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val player = ExoPlayer.Builder(context, renderers, mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val http = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val detail = http?.let { "HTTP ${it.responseCode}" } ?: error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
                Diagnostics.add("player", "error ${error.errorCodeName}${detail?.let { " ($it)" }.orEmpty()}")
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val formats = tracks.groups.filter { it.isSelected }.flatMap { g -> (0 until g.length).filter(g::isTrackSelected).map { g.getTrackFormat(it) } }
                if (formats.isNotEmpty()) {
                    Diagnostics.add("player", "playing " + formats.joinToString(", ") { f ->
                        listOfNotNull(f.sampleMimeType, f.width.takeIf { it > 0 }?.let { "${it}x${f.height}" }, f.language).joinToString(" ")
                    })
                }
            }
        })
        return player
    }

    private companion object {
        const val MAX_TIMESHIFT_BYTES = 600L * 1024 * 1024
        const val MIN_TIMESHIFT_BYTES = 200L * 1024 * 1024
        /** Closer to live than this counts as live. */
        const val LIVE_EDGE_MS = 3_000L
    }

    private fun isHls(url: String): Boolean {
        val lower = url.lowercase()
        return lower.substringBefore('?').endsWith(".m3u8") || lower.contains("extension=m3u8")
    }
}

/**
 * Reports unknown length for endless live streams. Some panels send a fake Content-Length, which
 * makes the TS extractor seek to "the end" to read a duration before playback can start.
 * Not for VOD — seeking there needs the real length.
 */
@OptIn(UnstableApi::class)
private class LiveDataSource(private val upstream: DataSource) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
