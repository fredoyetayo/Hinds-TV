package com.hindstv.live

/** A programme the user asked to be told about (☰ on a future programme in the guide). */
data class Reminder(val channelId: String, val start: Long, val stop: Long, val title: String) {
    val key: String get() = "$channelId@$start"

    companion object {
        /** One reminder per line, tab-separated. Pure, so it is unit-tested. */
        fun encode(reminders: List<Reminder>): String = reminders.joinToString("\n") {
            listOf(it.channelId, it.start, it.stop, it.title.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
        }

        fun decode(text: String?): List<Reminder> = text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val start = parts[1].toLongOrNull() ?: return@mapNotNull null
            val stop = parts[2].toLongOrNull() ?: return@mapNotNull null
            Reminder(parts[0], start, stop, parts[3])
        }.toList()

        /** Due from a minute before the start until it has been on for [GRACE_S]. */
        fun isDue(reminder: Reminder, nowSec: Long): Boolean =
            nowSec >= reminder.start - LEAD_S && nowSec < minOf(reminder.stop, reminder.start + GRACE_S)

        fun isExpired(reminder: Reminder, nowSec: Long): Boolean = nowSec >= minOf(reminder.stop, reminder.start + GRACE_S)

        const val LEAD_S = 60L
        const val GRACE_S = 15 * 60L
    }
}
