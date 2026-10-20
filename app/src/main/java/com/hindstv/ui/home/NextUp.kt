package com.hindstv.ui.home

/**
 * The episode after [current] in a numbered series ([numbers] is the comma-separated list the
 * portal gave), or null when [current] is the last one or the series has no numbers.
 */
fun nextEpisodeNumber(current: Int?, numbers: String): Int? {
    if (current == null) return null
    return numbers.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > current }.minOrNull()
}
