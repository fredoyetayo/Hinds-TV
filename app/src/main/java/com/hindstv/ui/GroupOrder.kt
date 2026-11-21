package com.hindstv.ui

/**
 * Where pinned provider groups go: straight after the user's favourites lists, above All channels,
 * in the provider's own order. Everything else keeps its place. Pure, so it is unit-tested.
 */
object GroupOrder {
    fun <T> pinFirst(groups: List<T>, pinned: Set<String>, id: (T) -> String): Pair<List<T>, List<T>> {
        if (pinned.isEmpty()) return emptyList<T>() to groups
        return groups.partition { id(it) in pinned }
    }
}
