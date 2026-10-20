package com.hindstv.core

/**
 * A short in-memory record of what the portal and the player said recently, for the
 * Settings → Diagnostics page: when something won't play, one photo of that page shows why.
 *
 * Everything is passed through [redact] first. MACs, tokens, serials and signatures never reach
 * the screen, the log or a photo. Nothing is written to disk.
 */
object Diagnostics {
    data class Entry(val atMs: Long, val area: String, val message: String)

    private const val MAX_ENTRIES = 80
    private const val MAX_MESSAGE = 240
    private val entries = ArrayDeque<Entry>()

    @Volatile var clock: () -> Long = System::currentTimeMillis

    fun add(area: String, message: String) {
        val entry = Entry(clock(), area, redact(message).take(MAX_MESSAGE))
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** Newest first. */
    fun recent(): List<Entry> = synchronized(entries) { entries.reversed() }

    fun clear() = synchronized(entries) { entries.clear() }

    private val macPattern = Regex("""(?i)\b[0-9a-f]{2}([:%3A-]{1,3}[0-9a-f]{2}){5}\b""")
    private val secretParams = Regex(
        """(?i)\b(mac|token|play_token|sn|device_id|device_id2|signature|password|login|username|uid|key)=([^&\s"',]+)""",
    )
    private val secretJson = Regex(
        """(?i)"(token|play_token|mac|sn|stb_sn|serial_number|device_id|device_id2|signature|password|login|random|fname|name_full|ls|ip|email|phone_number)"\s*:\s*("[^"]*"|[^,}\s]+)""",
    )
    private val bearer = Regex("""(?i)Bearer\s+\S+""")

    /** Removes anything that identifies the account or device. Pure, so it is unit-tested. */
    fun redact(text: String): String = text
        .replace(secretJson) { "\"${it.groupValues[1]}\":\"…\"" }
        .replace(secretParams) { "${it.groupValues[1]}=…" }
        .replace(bearer, "Bearer …")
        .replace(macPattern, "••:••:••:••:••:••")
}
