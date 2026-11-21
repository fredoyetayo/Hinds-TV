package com.hindstv.live

import kotlin.math.abs

/**
 * Picks the TV refresh rate that suits a video's frame rate: UK broadcasts (25/50 fps) want 50 Hz,
 * US/online video (29.97/30/59.94/60) wants 60 Hz, films (23.976/24) want 24 Hz when the TV has it.
 * Pure, so it is unit-tested.
 */
object FrameRates {
    /** The refresh rate to ask for, from the rates the display offers at the current resolution. */
    fun bestRefresh(videoFps: Float, available: List<Float>): Float? {
        if (videoFps <= 0f || available.isEmpty()) return null
        // An exact match or an exact multiple (25 fps shows perfectly at 50 Hz).
        val exact = available
            .map { refresh -> refresh to (1..3).minOf { abs(refresh - videoFps * it) } }
            .filter { it.second < 0.06f }
            .minWithOrNull(compareBy<Pair<Float, Float>> { it.second }.thenBy { it.first })
        if (exact != null) return exact.first
        // 24 fps on a TV without 24 Hz: 60 Hz (3:2) is the usual fallback; otherwise leave it alone.
        return null
    }

    /** Frame rate from frame timestamps (microseconds), ignoring jitter and the odd dropped frame. */
    fun estimate(timestampsUs: List<Long>): Float? {
        if (timestampsUs.size < 8) return null
        val deltas = timestampsUs.zipWithNext { a, b -> b - a }.filter { it in 5_000..100_000 }.sorted()
        if (deltas.size < 6) return null
        val median = deltas[deltas.size / 2].toFloat()
        val fps = 1_000_000f / median
        val known = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
        return known.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) < 1.2f }
    }
}
