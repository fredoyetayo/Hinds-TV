package com.hindstv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces channel changes. A single press tunes immediately; presses that arrive in quick
 * succession (holding ▲/▼, or tapping through channels) only tune once the user settles.
 * Portals rate-limit bursts of stream opens (HTTP 429/458), so this also protects the account.
 *
 * Starting a new request cancels the in-flight one, including its pending portal call.
 */
class ZapController<T>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val tune: suspend (T) -> Unit,
) {
    private var job: Job? = null
    private var lastRequestAt = Long.MIN_VALUE / 2

    fun request(target: T) {
        val now = clock()
        val inBurst = now - lastRequestAt < BURST_WINDOW_MS
        lastRequestAt = now
        job?.cancel()
        job = scope.launch {
            if (inBurst) delay(SETTLE_DELAY_MS)
            tune(target)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        const val BURST_WINDOW_MS = 1_500L
        const val SETTLE_DELAY_MS = 500L
    }
}
