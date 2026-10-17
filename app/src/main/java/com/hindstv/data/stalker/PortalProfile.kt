package com.hindstv.data.stalker

/**
 * Everything needed to log in to a Stalker/Ministra ("MAC") portal.
 *
 * Device identity fields (serial, device IDs, signature) are user supplied and default to blank;
 * most portals only check the MAC. We never generate or guess identities.
 */
data class PortalProfile(
    val portalUrl: String,
    val mac: String,
    val stbType: String = DEFAULT_STB_TYPE,
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
    val timezone: String = DEFAULT_TIMEZONE,
    val userAgent: String = DEFAULT_USER_AGENT,
) {
    companion object {
        const val DEFAULT_STB_TYPE = "MAG250"
        const val DEFAULT_TIMEZONE = "Europe/London"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
                "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val DEFAULT_MAC_PREFIX = "00:1A:79:"
        val STB_TYPES = listOf("MAG250", "MAG254", "MAG256", "MAG270", "MAG322", "MAG324", "MAG424", "AuraHD")
    }
}

object Mac {
    /** Returns the MAC as `AA:BB:CC:DD:EE:FF`, or null if [input] is not 12 hex digits. */
    fun normalize(input: String): String? {
        val hex = input.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }
}
