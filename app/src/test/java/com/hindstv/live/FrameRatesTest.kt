package com.hindstv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRatesTest {
    private val tv = listOf(23.976f, 24f, 50f, 59.94f, 60f)

    @Test
    fun `broadcast rates pick the matching refresh`() {
        assertEquals(50f, FrameRates.bestRefresh(25f, tv)!!, 0.001f)
        assertEquals(50f, FrameRates.bestRefresh(50f, tv)!!, 0.001f)
        assertEquals(59.94f, FrameRates.bestRefresh(29.97f, tv)!!, 0.001f)
        assertEquals(60f, FrameRates.bestRefresh(60f, tv)!!, 0.001f)
        assertEquals(24f, FrameRates.bestRefresh(24f, tv)!!, 0.001f)
    }

    @Test
    fun `no match leaves the display alone`() {
        assertNull(FrameRates.bestRefresh(24f, listOf(50f, 60f)))
        assertNull(FrameRates.bestRefresh(0f, tv))
    }

    @Test
    fun `estimates from frame timestamps despite jitter and a dropped frame`() {
        val ts = (0 until 40).map { it * 40_000L + (if (it % 3 == 0) 900 else 0) }.filter { it != 400_000L }
        assertEquals(25f, FrameRates.estimate(ts)!!, 0.001f)
        val sixty = (0 until 40).map { it * 16_683L }
        assertEquals(59.94f, FrameRates.estimate(sixty)!!, 0.001f)
        assertNull(FrameRates.estimate(listOf(1L, 2L)))
    }
}
