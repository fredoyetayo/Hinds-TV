package com.hindstv.data.source

import com.hindstv.data.stalker.StalkerClient

/** Channels and guide from a Stalker/Ministra portal. */
class StalkerSource(val client: StalkerClient) : ChannelSource {
    override val userAgent: String get() = client.profile.userAgent

    override suspend fun load(): SourceContent {
        val channels = client.channels()
        // Groups are optional: some panels don't implement get_genres; channels still show under "All".
        val genres = try {
            client.genres()
        } catch (e: com.hindstv.data.stalker.StalkerException.BadResponse) {
            emptyList()
        } catch (e: com.hindstv.data.stalker.StalkerException.ServerError) {
            emptyList()
        }
        return SourceContent(
            genres = genres.map { SourceGenre(it.id, it.title, it.censored) },
            channels = channels.map { c ->
                SourceChannel(
                    id = c.id, name = c.name, number = c.number, cmd = c.cmd, logo = c.logo, genreId = c.genreId,
                    epgId = c.id, censored = c.censored, useTmpLink = c.useTmpLink,
                    hasArchive = c.hasArchive, archiveHours = c.archiveHours,
                )
            },
        )
    }

    override suspend fun streamUrl(cmd: String): String = client.streamUrl(cmd)

    override suspend fun loadEpg(
        channels: List<EpgChannelRef>,
        fromSec: Long,
        toSec: Long,
        onBatch: suspend (List<Programme>) -> Unit,
    ) {
        val hours = ((toSec - System.currentTimeMillis() / 1000) / 3600).toInt().coerceIn(1, MAX_HOURS)
        val known = channels.mapTo(HashSet()) { it.channelId }
        val batch = ArrayList<Programme>(BATCH)
        for ((channelId, programmes) in client.epg(hours)) {
            if (channelId !in known) continue
            for (p in programmes) {
                if (p.stop <= fromSec || p.start >= toSec) continue
                batch += Programme(channelId, p.start, p.stop, p.title, p.description?.take(400))
                if (batch.size >= BATCH) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch)
    }

    private companion object {
        const val MAX_HOURS = 12
        const val BATCH = 1_000
    }
}
