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

    private val httpFactory = OkHttpDataSource.Factory(http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build())
        .setUserAgent(PortalProfile.DEFAULT_USER_AGENT)

    private var built = false
    private lateinit var liveSources: DefaultMediaSourceFactory
    private lateinit var vodSources: DefaultMediaSourceFactory

    val player: ExoPlayer by lazy { built = true; build() }

    /**
     * Who is playing (live TV, videos or radio). Each screen's player listener ignores events
     * while someone else owns the one player.
     */
    var owner: Any? = null
        private set

    fun setUserAgent(userAgent: String) {
        httpFactory.setUserAgent(userAgent)
    }

    /**
     * Plays [url]. Live streams and radio are endless ([vod] false); videos have a real length so
     * they can seek and resume from [startMs].
     */
    fun play(url: String, owner: Any?, vod: Boolean = false, startMs: Long = 0) {
        this.owner = owner
        Diagnostics.add("player", "${if (vod) "video" else "stream"}: $url")
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val player = player
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
