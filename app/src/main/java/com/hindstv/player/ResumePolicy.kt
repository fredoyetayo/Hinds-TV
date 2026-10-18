package com.hindstv.player

/** When a video counts as started, finished, and where to pick it up again (Netflix-style). */
object ResumePolicy {
    /** Shorter than this is "just looked at it": start from the beginning next time. */
    const val MIN_RESUME_MS = 30_000L

    /** Resume a few seconds early so the viewer picks the thread back up. */
    const val REWIND_ON_RESUME_MS = 5_000L

    /** Past this share of the running time (credits, end screens) a video counts as watched. */
    const val FINISHED_FRACTION = 0.95

    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    /** Where to start playing given a saved position; 0 means from the start. */
    fun startPosition(savedMs: Long, durationMs: Long, finished: Boolean): Long = when {
        finished || savedMs < MIN_RESUME_MS -> 0L
        isFinished(savedMs, durationMs) -> 0L
        else -> (savedMs - REWIND_ON_RESUME_MS).coerceAtLeast(0L)
    }

    /** Watched share for progress bars, 0..1. */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
