package com.hindstv.player

import java.util.Locale

/** One audio or subtitle track the viewer can pick. [id] is "group:track" in the player's current tracks. */
data class TrackOption(val id: String, val label: String, val selected: Boolean)

data class TrackChoices(
    val audio: List<TrackOption>,
    val subtitles: List<TrackOption>,
    val subtitlesOff: Boolean,
) {
    val isEmpty: Boolean get() = audio.size <= 1 && subtitles.isEmpty()
}

/** Plain-English track names: "English · 5.1 · Dolby Digital". Pure, so it is unit-tested. */
object TrackLabels {
    fun audio(language: String?, label: String?, mime: String?, channels: Int, index: Int): String =
        listOfNotNull(
            name(language, label) ?: "Audio ${index + 1}",
            channelsName(channels),
            codecName(mime),
        ).joinToString(" · ")

    fun subtitle(language: String?, label: String?, index: Int): String =
        name(language, label) ?: "Subtitles ${index + 1}"

    private fun name(language: String?, label: String?): String? {
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val code = language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val tag = code.substringBefore('-').let { iso3To2[it] ?: it }
        val display = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.UK)
        return display.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(Locale.UK) }
            ?: code.uppercase(Locale.UK)
    }

    /** Broadcasts label tracks with three-letter codes ("eng", and older forms like "fre", "ger"). */
    private val iso3To2: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) } +
            mapOf(
                "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
                "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
                "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
                "tib" to "bo", "wel" to "cy",
            )
    }

    private fun channelsName(channels: Int): String? = when {
        channels <= 0 -> null
        channels == 1 -> "Mono"
        channels == 2 -> "Stereo"
        channels == 6 -> "5.1"
        channels == 8 -> "7.1"
        else -> "$channels ch"
    }

    private fun codecName(mime: String?): String? = when (mime) {
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/mpeg-L2" -> "MP2"
        else -> null
    }
}
