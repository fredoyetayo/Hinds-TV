package com.hindstv.data.stalker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Portals are inconsistent about types ("1" vs 1 vs true, numbers as strings, nulls), so every
// accessor is tolerant and returns null/false instead of throwing.

internal fun JsonElement?.text(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

internal fun JsonObject.string(key: String): String? = this[key].text()?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.int(key: String): Int? = this[key].text()?.trim()?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? = this[key].text()?.trim()?.toLongOrNull()

internal fun JsonObject.flag(key: String): Boolean =
    when (this[key].text()?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }

/** Lists arrive as `[...]`, `{"data":[...]}` or occasionally `{"<id>":{...}}`. */
internal fun JsonElement?.asItemArray(): List<JsonObject> = when (this) {
    is JsonArray -> filterIsInstance<JsonObject>()
    is JsonObject -> (this["data"] as? JsonArray)?.filterIsInstance<JsonObject>()
        ?: values.filterIsInstance<JsonObject>().takeIf { values.all { it is JsonObject } }
        ?: emptyList()
    else -> emptyList()
}
