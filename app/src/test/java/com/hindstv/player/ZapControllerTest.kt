package com.hindstv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZapControllerTest {
    private val tuned = mutableListOf<Int>()

    // backgroundScope work is not run by advanceUntilIdle(), so move the clock explicitly.
    private fun TestScope.settle() {
        advanceTimeBy(ZapController.SETTLE_DELAY_MS + 100)
        runCurrent()
    }

    private fun TestScope.zapper() = ZapController<Int>(backgroundScope, { testScheduler.currentTime }) { tuned += it }

    @Test
    fun `a single press tunes immediately`() = runTest {
        val zap = zapper()
        zap.request(1)
        runCurrent()
        assertEquals(listOf(1), tuned)
    }

    @Test
    fun `a burst of presses only tunes the channel the user settles on`() = runTest {
        val zap = zapper()
        zap.request(1)
        runCurrent()
        advanceTimeBy(200)
        zap.request(2)
        advanceTimeBy(200)
        zap.request(3)
        advanceTimeBy(200)
        zap.request(4)
        settle()
        assertEquals(listOf(1, 4), tuned)
    }

    @Test
    fun `a press after a quiet period is immediate again`() = runTest {
        val zap = zapper()
        zap.request(1)
        runCurrent()
        advanceTimeBy(ZapController.BURST_WINDOW_MS + 100)
        zap.request(2)
        runCurrent()
        assertEquals(listOf(1, 2), tuned)
    }

    @Test
    fun `cancel drops a pending tune`() = runTest {
        val zap = zapper()
        zap.request(1)
        runCurrent()
        zap.request(2) // in burst: waits for the settle delay
        zap.cancel()
        settle()
        assertEquals(listOf(1), tuned)
    }
}
