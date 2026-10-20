package com.hindstv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelsTest {
    @Test
    fun `audio names the language, channels and codec`() {
        assertEquals("English · 5.1 · Dolby Digital", TrackLabels.audio("en", null, "audio/ac3", 6, 0))
        assertEquals("French · Stereo", TrackLabels.audio("fra", null, "audio/mp4a-latm", 2, 1))
        assertEquals("German", TrackLabels.audio("ger", null, null, 0, 0))
    }

    @Test
    fun `the provider's own label wins`() {
        assertEquals("Commentary · Stereo", TrackLabels.audio("en", "Commentary", null, 2, 0))
    }

    @Test
    fun `unknown languages fall back to a number or the code`() {
        assertEquals("Audio 2", TrackLabels.audio("und", null, null, 0, 1))
        assertEquals("Audio 1 · MP2", TrackLabels.audio(null, " ", "audio/mpeg-L2", 0, 0))
        assertEquals("Subtitles 3", TrackLabels.subtitle(null, null, 2))
        assertEquals("XQZ", TrackLabels.subtitle("xqz", null, 0))
    }
}
