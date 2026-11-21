package com.hindstv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.concurrent.thread

class TimeshiftBufferTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun buffer(max: Long = 1_000, segment: Long = 100) =
        TimeshiftBuffer(tmp.newFolder(), max, segment) { now }

    private fun bytes(from: Int, count: Int) = ByteArray(count) { (from + it).toByte() }

    @Test
    fun `reads back what was written, across segments`() {
        val b = buffer()
        b.append(bytes(0, 250), 0, 250)
        val out = ByteArray(250)
        var pos = 0L
        while (pos < 250) pos += b.read(pos, out, pos.toInt(), (250 - pos).toInt(), 0)
        assertArrayEquals(bytes(0, 250), out)
    }

    @Test
    fun `drops the oldest segments past the limit, and reads skip ahead to what is kept`() {
        val b = buffer(max = 300, segment = 100)
        repeat(10) { b.append(bytes(it * 100, 100), 0, 100) }
        assertTrue(b.oldestOffset >= 700)
        val out = ByteArray(10)
        assertEquals(10, b.read(0, out, 0, 10, 0))
        assertArrayEquals(bytes(b.oldestOffset.toInt(), 10), out)
    }

    @Test
    fun `maps times to offsets`() {
        val b = buffer(max = 10_000)
        b.append(bytes(0, 100), 0, 100) // t=1000 at 0
        now = 2_000
        b.append(bytes(100, 100), 0, 100) // t=2000 at 100
        now = 3_000
        b.append(bytes(200, 100), 0, 100) // t=3000 at 200
        assertEquals(0L, b.offsetAt(500))
        assertEquals(100L, b.offsetAt(2_500))
        assertEquals(200L, b.offsetAt(9_000))
        assertEquals(2_000L, b.timeAt(150))
        assertEquals(200L, b.liveOffset)
    }

    @Test
    fun `waits at the live edge, then ends or fails`() {
        val b = buffer()
        val out = ByteArray(10)
        assertEquals(0, b.read(0, out, 0, 10, timeoutMs = 0))
        val writer = thread { Thread.sleep(50); b.append(bytes(0, 10), 0, 10) }
        now = 0 // the clock doesn't move, so only the data can end the wait
        val n = TimeshiftBuffer(tmp.newFolder(), 1_000, 100) { System.currentTimeMillis() }.let { real ->
            val t = thread { Thread.sleep(50); real.append(bytes(0, 10), 0, 10) }
            real.read(0, out, 0, 10, timeoutMs = 2_000).also { t.join() }
        }
        writer.join()
        assertEquals(10, n)
        b.finish(null)
        assertEquals(-1, b.read(10, out, 0, 10, 0))
        val failed = buffer()
        failed.finish(IOException("HTTP 403"))
        try {
            failed.read(0, out, 0, 10, 0)
            throw AssertionError("expected the recorder's error")
        } catch (e: IOException) {
            assertEquals("HTTP 403", e.message)
        }
    }
}
