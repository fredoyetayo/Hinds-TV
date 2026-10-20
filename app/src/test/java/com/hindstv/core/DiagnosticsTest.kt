package com.hindstv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun `MACs, tokens and device ids never survive redaction`() {
        val raw = """vod/create_link → {"js":{"cmd":"ffmpeg http://h:80/play/movie.php?mac=00:1A:79:12:34:56&stream=9.mp4&play_token=abc123","token":"T0K3N"}} """ +
            "Cookie: mac=00%3A1A%3A79%3A12%3A34%3A56 Authorization: Bearer ABCDEF sn=0123456789 device_id=XYZ"

        val clean = Diagnostics.redact(raw)

        listOf("00:1A:79", "00%3A1A%3A79", "abc123", "T0K3N", "ABCDEF", "0123456789", "XYZ").forEach {
            assertFalse("leaked $it in $clean", clean.contains(it))
        }
        assertTrue(clean.contains("stream=9.mp4")) // what was asked for stays readable
        assertTrue(clean.contains("vod/create_link"))
    }

    @Test
    fun `subscriber details from get_profile are hidden`() {
        val out = Diagnostics.redact("""{"js":{"fname":"Jane Doe","ip":"81.2.3.4","ls":12345,"stb_sn":"ABC123","status":0}}""")
        listOf("Jane", "81.2.3.4", "12345", "ABC123").forEach { assertFalse(it, it in out) }
        assertTrue(out.contains(""""status":0"""))
    }

    @Test
    fun `keeps the newest entries first`() {
        Diagnostics.clear()
        Diagnostics.add("portal", "first")
        Diagnostics.add("player", "second")
        assertEquals(listOf("second", "first"), Diagnostics.recent().map { it.message })
    }
}
