package com.hindstv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.hindstv.core.Diagnostics
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * One live channel being recorded to disk while it plays. The recorder thread holds the only
 * connection to the provider; the player reads the recording ([TimeshiftDataSource]), so pausing
 * or rewinding never opens a second stream. [close] cancels the connection at once (zapping must
 * free the account's one connection before the next channel opens).
 */
@OptIn(UnstableApi::class)
class TimeshiftSession(
    val id: Int,
    private val url: String,
    userAgent: String,
    http: OkHttpClient,
    dir: File,
    maxBytes: Long,
) {
    val buffer = TimeshiftBuffer(dir, maxBytes, clock = android.os.SystemClock::elapsedRealtime)
    private val call = AtomicReference<Call?>(null)
    @Volatile private var closed = false

    private val upstream = OkHttpDataSource.Factory(Call.Factory { request -> http.newCall(request).also(call::set) })
        .setUserAgent(userAgent)
        .createDataSource()

    private val thread = Thread({ record() }, "HindsTimeshift-$id").apply { isDaemon = true }

    fun start() = thread.start()

    private fun record() {
        var error: IOException? = null
        try {
            upstream.open(DataSpec(Uri.parse(url)))
            val chunk = ByteArray(CHUNK)
            while (!closed) {
                val n = upstream.read(chunk, 0, chunk.size)
                if (n == C.RESULT_END_OF_INPUT) break
                if (n > 0) buffer.append(chunk, 0, n)
            }
        } catch (e: IOException) {
            if (!closed) {
                error = e
                Diagnostics.add("timeshift", "recording stopped: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
        } finally {
            runCatching { upstream.close() }
            buffer.finish(error)
        }
    }

    fun close() {
        closed = true
        call.get()?.cancel()
        buffer.close()
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/**
 * Plays a [TimeshiftSession]'s recording: `timeshift://<session>/<start offset>`. Endless (unknown
 * length) like any live stream; waits at the live edge for the recorder.
 */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val sessions: () -> TimeshiftSession?) : BaseDataSource(/* isNetwork = */ false) {
    private var session: TimeshiftSession? = null
    private var position = 0L
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val s = sessions()?.takeIf { it.id.toString() == dataSpec.uri.host } ?: throw IOException("Timeshift session ended")
        session = s
        uri = dataSpec.uri
        position = (dataSpec.uri.lastPathSegment?.toLongOrNull() ?: 0L) + dataSpec.position
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val s = session ?: throw IOException("Not open")
        while (true) {
            // Paused for longer than the recording keeps: carry on from the oldest part still there.
            position = maxOf(position, s.buffer.oldestOffset)
            val n = s.buffer.read(position, buffer, offset, length, timeoutMs = 500)
            if (n < 0) return C.RESULT_END_OF_INPUT
            if (n > 0) {
                position += n
                bytesTransferred(n)
                return n
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (session != null) transferEnded()
        session = null
    }
}
