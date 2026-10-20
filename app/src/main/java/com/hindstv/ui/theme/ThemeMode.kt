package com.hindstv.ui.theme

/** Settings → Appearance → Theme. Stored by name in `ProfileStore`. */
enum class ThemeMode {
    Dark, Light, Auto;

    /** Automatic is Light from 07:00 to 19:00 local time, Dark otherwise (no location needed). */
    fun isLight(hourOfDay: Int): Boolean = when (this) {
        Dark -> false
        Light -> true
        Auto -> hourOfDay in 7 until 19
    }

    companion object {
        fun parse(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: Dark
    }
}
