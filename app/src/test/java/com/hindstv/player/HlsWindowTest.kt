package com.hindstv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsWindowTest {
    private val base = "http://cdn.example/live/ch/index.m3u8?token=abc"

    private fun playlist(firstSeq: Long, count: Int, dseq: Long = 0, discontinuityAt: Long? = null) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$firstSeq\n")
        append("#EXT-X-DISCONTINUITY-SEQUENCE:$dseq\n")
        for (s in firstSeq until firstSeq + count) {
            if (s == discontinuityAt) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:6.000,\nseg-$s.ts?token=abc\n")
        }
    }

    private fun segments(text: String) = text.lines().filter { it.startsWith("http") }

    @Test
    fun `first load keeps the last few, later loads append, URIs become absolute`() {
        val w = HlsWindow(firstKeep = 3)
        val (first, prefetch) = w.merge(playlist(100, 5), base)!!
        assertEquals(listOf(102L, 103L, 104L).map { "http://cdn.example/live/ch/seg-$it.ts?token=abc" }, segments(first))
        assertTrue(first.contains("#EXT-X-MEDIA-SEQUENCE:102"))
        assertEquals(emptyList<String>(), prefetch) // the player is fetching these itself

        val (second, newOnes) = w.merge(playlist(103, 5), base)!! // provider window slid: 103..107
        assertEquals((102L..107L).map { "http://cdn.example/live/ch/seg-$it.ts?token=abc" }, segments(second))
        assertEquals(3, newOnes.size)
        assertEquals(36.0, w.durationS, 0.001)
    }

    @Test
    fun `old segments drop off past the window, keeping discontinuity numbering right`() {
        val w = HlsWindow(maxWindowS = 18.0, firstKeep = 1)
        w.merge(playlist(1, 1), base)
        w.merge(playlist(1, 2, discontinuityAt = 2), base)
        w.merge(playlist(2, 3, dseq = 0, discontinuityAt = 2), base)
        val (text, _) = w.merge(playlist(3, 3, dseq = 1), base)!! // 1 and 2 have left the provider's playlist
        assertEquals(3, segments(text).size) // 18 s window = 3 segments of 6 s
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:3"))
        assertTrue(text.contains("#EXT-X-DISCONTINUITY-SEQUENCE:1"))
    }

    @Test
    fun `masters, VOD and encrypted playlists pass through`() {
        val w = HlsWindow()
        assertNull(w.merge("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n", base))
        assertNull(w.merge(playlist(1, 3) + "#EXT-X-ENDLIST\n", base))
        assertNull(w.merge(playlist(1, 3).replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\""), base))
        assertNull(w.merge("not a playlist", base))
    }

    @Test
    fun `a restarted stream starts the window again`() {
        val w = HlsWindow(firstKeep = 2)
        w.merge(playlist(500, 4), base)
        val (text, _) = w.merge(playlist(1, 4), base)!!
        assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:3"))
    }
}
