package com.hindstv.data

/** Most recent first, no duplicates, at most [max]: the "Recently watched" channels. */
fun pushRecent(recent: List<String>, id: String, max: Int = RECENT_MAX): List<String> =
    (listOf(id) + recent.filter { it != id }).take(max)

const val RECENT_MAX = 20
