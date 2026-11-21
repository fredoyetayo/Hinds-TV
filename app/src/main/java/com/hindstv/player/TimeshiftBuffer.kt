package com.hindstv.player

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * The last stretch of a live stream on disk, so live TV can pause and rewind. One writer (the
 * recorder) appends; readers (the player) read from any offset still kept, waiting at the live edge
 * for more. Kept as fixed-size segment files: the oldest is deleted once [maxBytes] is reached.
 *
 * Offsets are positions in the whole recorded stream; [offsetAt] maps a time (when those bytes
 * arrived) to an offset. Live TS arrives in real time, so arrival time is a good stand-in for
 * stream time. Pure JVM (files only), so it is unit-tested.
 */
class TimeshiftBuffer(
    private val dir: File,
    private val maxBytes: Long,
    private val segmentBytes: Long = 8L * 1024 * 1024,
    private val clock: () -> Long,
) {
    private class Segment(val start: Long, val file: File, val out: RandomAccessFile) {
        var length = 0L
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait/notify needs a plain monitor
    private val lock = Object()
    private val segments = ArrayDeque<Segment>()
    private var markTimes = LongArray(1024)
    private var markOffsets = LongArray(1024)
    private var markCount = 0
    private var markFirst = 0

    @Volatile var written = 0L
        private set
    @Volatile private var finished = false
    @Volatile private var failure: IOException? = null
    @Volatile var lastWriteAt = 0L
        private set
    private var closed = false

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // a previous session's leftovers
    }

    val oldestOffset: Long get() = synchronized(lock) { segments.firstOrNull()?.start ?: 0L }

    val oldestTime: Long get() = synchronized(lock) { if (markCount > markFirst) markTimes[markFirst] else lastWriteAt }

    fun append(buffer: ByteArray, offset: Int, length: Int) {
        var pos = offset
        var left = length
        synchronized(lock) {
            if (closed) return
            val now = clock()
            if (markCount == markFirst || now - markTimes[markCount - 1] >= MARK_INTERVAL_MS) addMark(now, written)
            while (left > 0) {
                val segment = segments.lastOrNull()?.takeIf { it.length < segmentBytes } ?: newSegment()
                val n = minOf(left.toLong(), segmentBytes - segment.length).toInt()
                segment.out.seek(segment.length)
                segment.out.write(buffer, pos, n)
                segment.length += n
                written += n
                pos += n
                left -= n
            }
            lastWriteAt = now
            trim()
            lock.notifyAll()
        }
    }

    /** The recorder stopped: the stream ended, or failed with [error] (handed to readers at the edge). */
    fun finish(error: IOException?) {
        synchronized(lock) {
            failure = error
            finished = true
            lock.notifyAll()
        }
    }

    /**
     * Reads from [position], waiting up to [timeoutMs] at the live edge. Returns bytes read, 0 if
     * nothing arrived in time, or -1 when the stream ended. A position already deleted reads from
     * the oldest data instead (the caller learns this from [oldestOffset]); TS resyncs on its own.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val segment: Segment
        val from: Long
        synchronized(lock) {
            val deadline = clock() + timeoutMs
            while (position >= written && !finished && !closed) {
                val wait = deadline - clock()
                if (wait <= 0) return 0
                try {
                    lock.wait(minOf(wait, 200))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            if (closed) throw IOException("Timeshift closed")
            if (position >= written) failure?.let { throw it } ?: return -1
            val p = maxOf(position, segments.first().start)
            segment = segments.last { it.start <= p }
            from = p
        }
        val n = minOf(length.toLong(), segment.start + segment.length - from).toInt()
        return try {
            RandomAccessFile(segment.file, "r").use { raf ->
                raf.seek(from - segment.start)
                raf.read(buffer, offset, n).coerceAtLeast(0)
            }
        } catch (_: java.io.FileNotFoundException) {
            0 // trimmed just now; the next read starts from the new oldest segment
        }
    }

    /** The offset of the data that arrived at (or just before) [timeMs], within what is kept. */
    fun offsetAt(timeMs: Long): Long = synchronized(lock) {
        if (markCount == markFirst) return 0L
        var lo = markFirst
        var hi = markCount - 1
        if (timeMs <= markTimes[lo]) return markOffsets[lo]
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (markTimes[mid] <= timeMs) lo = mid else hi = mid - 1
        }
        markOffsets[lo]
    }

    /** When the data at [offset] arrived. */
    fun timeAt(offset: Long): Long = synchronized(lock) {
        if (markCount == markFirst) return lastWriteAt
        var lo = markFirst
        var hi = markCount - 1
        if (offset <= markOffsets[lo]) return markTimes[lo]
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (markOffsets[mid] <= offset) lo = mid else hi = mid - 1
        }
        markTimes[lo]
    }

    /** The newest mark: where "live" starts playing from. */
    val liveOffset: Long get() = synchronized(lock) { if (markCount > markFirst) markOffsets[markCount - 1] else 0L }

    fun close() {
        synchronized(lock) {
            closed = true
            segments.forEach {
                runCatching { it.out.close() }
                it.file.delete()
            }
            segments.clear()
            lock.notifyAll()
        }
    }

    private fun newSegment(): Segment {
        val start = written
        val file = File(dir, "seg-$start.ts")
        return Segment(start, file, RandomAccessFile(file, "rw")).also { segments.addLast(it) }
    }

    private fun trim() {
        while (segments.size > 1 && written - segments.first().start > maxBytes) {
            val old = segments.removeFirst()
            runCatching { old.out.close() }
            old.file.delete()
        }
        val oldest = segments.first().start
        while (markFirst < markCount - 1 && markOffsets[markFirst + 1] <= oldest) markFirst++
        if (markOffsets[markFirst] < oldest) markOffsets[markFirst] = oldest
    }

    private fun addMark(time: Long, offset: Long) {
        if (markCount == markTimes.size) {
            // Drop the trimmed front first; grow only if that doesn't free enough.
            val live = markCount - markFirst
            val size = if (live * 2 > markTimes.size) markTimes.size * 2 else markTimes.size
            markTimes = markTimes.copyOfRange(markFirst, markCount).copyOf(size)
            markOffsets = markOffsets.copyOfRange(markFirst, markCount).copyOf(size)
            markCount = live
            markFirst = 0
        }
        markTimes[markCount] = time
        markOffsets[markCount] = offset
        markCount++
    }

    private companion object {
        const val MARK_INTERVAL_MS = 200L
    }
}
