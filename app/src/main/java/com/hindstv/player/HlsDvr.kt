package com.hindstv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import com.hindstv.core.Diagnostics
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pause and rewind for live HLS channels. The player plays the provider's stream as usual, but:
 * - its playlist requests go through [HlsWindow], which keeps listing every segment since playback
 *   began (up to ~10 minutes) instead of the provider's last few;
 * - segments go through a disk [Cache], so going back never asks the provider again;
 * - a background prefetch keeps caching new segments while paused, so resuming later still works
 *   after the provider has dropped them.
 * Zapping speed is unchanged: nothing is fetched before the player asks for it.
 */
@OptIn(UnstableApi::class)
class HlsDvr(private val cache: Cache, private val upstream: DataSource.Factory) : HlsDataSourceFactory {
    private val window = HlsWindow()
    private val closed = AtomicBoolean(false)
    @Volatile var rewriting = false
        private set
    @Volatile private var disabled = false
    private val prefetch = Executors.newSingleThreadExecutor { Thread(it, "HindsDvrPrefetch").apply { isDaemon = true } }
    @Volatile private var writer: CacheWriter? = null

    private val cacheFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val windowS: Double get() = window.durationS

    override fun createDataSource(dataType: Int): DataSource =
        if (dataType == C.DATA_TYPE_MANIFEST) PlaylistSource() else cacheFactory.createDataSource()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        writer?.cancel()
        prefetch.shutdownNow()
    }

    private fun enqueue(uris: List<String>) {
        if (closed.get()) return
        uris.forEach { uri ->
            runCatching {
                prefetch.execute {
                    if (closed.get()) return@execute
                    try {
                        val w = CacheWriter(cacheFactory.createDataSource(), DataSpec(Uri.parse(uri)), null, null)
                        writer = w
                        w.cache()
                    } catch (_: Exception) {
                        // The player fetches it itself if it gets there; nothing to report.
                    } finally {
                        writer = null
                    }
                }
            }
        }
    }

    /** Fetches the real playlist and hands the player the longer, rewound-able one. */
    private inner class PlaylistSource : DataSource {
        private val http = upstream.createDataSource()
        private var bytes: ByteArrayDataSource? = null
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            http.open(dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
            val raw = try {
                DataSourceUtil.readToEnd(http)
            } finally {
                uri = http.uri ?: dataSpec.uri
                http.close()
            }
            val text = String(raw, Charsets.UTF_8)
            if (text.contains("#EXT-X-STREAM-INF")) return serve(masterWithOneVariant(text), dataSpec)
            val merged = if (disabled) null else synchronized(window) { window.merge(text, uri.toString()) }
            val out = if (merged != null) {
                if (!rewriting) Diagnostics.add("timeshift", "live rewind on (HLS)")
                rewriting = true
                enqueue(merged.second)
                merged.first.toByteArray()
            } else {
                raw
            }
            return serve(out, dataSpec)
        }

        private fun serve(out: ByteArray, dataSpec: DataSpec): Long {
            val source = ByteArrayDataSource(out)
            bytes = source
            return source.open(dataSpec)
        }

        /**
         * One window can only follow one media playlist, so the player gets the best variant only
         * (no switching qualities mid-stream). Separate audio/subtitle renditions can't be rewound
         * this way: those streams play as before, without rewind.
         */
        private fun masterWithOneVariant(text: String): ByteArray {
            val lines = text.lines()
            if (lines.any { it.startsWith("#EXT-X-MEDIA:") && it.contains("URI=") }) {
                disabled = true
                return text.toByteArray()
            }
            val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") && it + 1 < lines.size }
            if (variants.size <= 1) return text.toByteArray()
            val best = variants.maxBy { i ->
                Regex("""(?:^|[:,])BANDWIDTH=(\d+)""").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            }
            val header = lines.filter { it.startsWith("#") && !it.startsWith("#EXT-X-STREAM-INF") && !it.startsWith("#EXT-X-I-FRAME") }
            return (header + lines[best] + lines[best + 1]).joinToString("\n").toByteArray()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = bytes?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            bytes?.close()
            bytes = null
        }
    }
}
