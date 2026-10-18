package com.hindstv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    private val tenMinutes = 600_000L

    @Test
    fun `a video is finished in its last five percent`() {
        assertFalse(ResumePolicy.isFinished(569_000, tenMinutes))
        assertTrue(ResumePolicy.isFinished(570_000, tenMinutes))
        assertFalse(ResumePolicy.isFinished(570_000, 0)) // unknown length is never "finished"
    }

    @Test
    fun `resumes a few seconds before where it stopped`() {
        assertEquals(427_000L, ResumePolicy.startPosition(432_000, tenMinutes, finished = false))
    }

    @Test
    fun `starts again from the beginning when barely started or already watched`() {
        assertEquals(0L, ResumePolicy.startPosition(12_000, tenMinutes, finished = false))
        assertEquals(0L, ResumePolicy.startPosition(590_000, tenMinutes, finished = false))
        assertEquals(0L, ResumePolicy.startPosition(300_000, tenMinutes, finished = true))
    }

    @Test
    fun `streams without a known length still resume`() {
        assertEquals(95_000L, ResumePolicy.startPosition(100_000, 0, finished = false))
    }
}
