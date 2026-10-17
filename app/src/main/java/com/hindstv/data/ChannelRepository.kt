package com.hindstv.data

import com.hindstv.data.db.ChannelDao
import com.hindstv.data.db.ChannelEntity
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.EpgDao
import com.hindstv.data.db.FavoriteEntity
import com.hindstv.data.db.GenreEntity
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.data.source.ChannelSource
import com.hindstv.data.source.EpgChannelRef
import com.hindstv.data.source.PlaylistException
import com.hindstv.data.source.StalkerSource
import kotlinx.coroutines.flow.Flow

/**
 * Channels and guide data live in the local database; the UI only ever reads from there, so
 * browsing is instant and works while the provider is slow. [sync] and [syncEpg] refresh it.
 */
class ChannelRepository(
    private val dao: ChannelDao,
    private val epgDao: EpgDao,
    private val store: ProfileStore,
    private val source: () -> ChannelSource,
) {
    fun genres(): Flow<List<GenreEntity>> = dao.genres()

    fun channels(genreId: String): Flow<List<ChannelRow>> = when (genreId) {
        FAVORITES -> dao.favoriteChannels()
        ALL -> dao.allChannels()
        else -> dao.channelsInGenre(genreId)
    }

    suspend fun channel(id: String): ChannelRow? = dao.channelRow(id)

    suspend fun hasChannels(): Boolean = dao.channelCount() > 0

    /** Downloads genres + channels and swaps them in atomically. Returns the channel count. */
    suspend fun sync(): Int {
        val active = source()
        val content = active.load()
        if (content.channels.isEmpty()) throw PlaylistException("No channels were found.")
        dao.replaceAll(
            genres = content.genres.mapIndexed { i, g -> GenreEntity(g.id, g.title, i, g.censored) },
            channels = content.channels.mapIndexed { i, c ->
                ChannelEntity(
                    id = c.id, sortOrder = i, number = c.number, name = c.name, genreId = c.genreId,
                    logo = c.logo, cmd = c.cmd, useTmpLink = c.useTmpLink, hasArchive = c.hasArchive,
                    archiveHours = c.archiveHours, xmltvId = c.epgId, censored = c.censored,
                )
            },
        )
        store.discoveredEpgUrl = content.epgUrl
        (active as? StalkerSource)?.client?.session?.let { store.endpoint = it.endpoint }
        store.lastSyncAt = System.currentTimeMillis()
        return content.channels.size
    }

    /** Replaces the guide with [EPG_PAST_S] of history and [EPG_AHEAD_S] ahead. Returns rows stored. */
    suspend fun syncEpg(): Int {
        val now = System.currentTimeMillis() / 1000
        val refs = dao.epgRefs().map { EpgChannelRef(it.channelId, it.epgId, it.name) }
        if (refs.isEmpty()) return 0
        var first = true
        var stored = 0
        source().loadEpg(refs, now - EPG_PAST_S, now + EPG_AHEAD_S) { batch ->
            // Clear only once new data has actually arrived, so a failed download keeps the old guide.
            if (first) {
                epgDao.clear()
                first = false
            }
            epgDao.insert(batch.map { ProgrammeEntity(it.channelId, it.start, it.stop, it.title, it.description) })
            stored += batch.size
        }
        store.lastEpgSyncAt = System.currentTimeMillis()
        return stored
    }

    suspend fun nowNext(channelId: String, nowSec: Long): List<ProgrammeEntity> = epgDao.nowNext(channelId, nowSec)

    suspend fun airingAt(nowSec: Long): List<ProgrammeEntity> = epgDao.airingAt(nowSec)

    suspend fun guide(channelIds: List<String>, fromSec: Long, toSec: Long): List<ProgrammeEntity> =
        if (channelIds.isEmpty()) emptyList() else epgDao.window(channelIds, fromSec, toSec)

    /** Asks the source for a fresh, playable URL (portal tokens in these links expire quickly). */
    suspend fun streamUrl(channelId: String): String {
        val cmd = dao.cmd(channelId) ?: throw PlaylistException("That channel no longer exists.")
        val active = source()
        val url = active.streamUrl(cmd)
        (active as? StalkerSource)?.client?.session?.let { if (store.endpoint != it.endpoint) store.endpoint = it.endpoint }
        return url
    }

    /** Returns true if the channel is now a favourite. */
    suspend fun toggleFavorite(channelId: String): Boolean =
        if (dao.isFavorite(channelId)) {
            dao.removeFavorite(channelId)
            false
        } else {
            dao.addFavorite(FavoriteEntity(channelId, System.currentTimeMillis()))
            true
        }

    companion object {
        const val FAVORITES = "__favorites"
        const val ALL = "__all"
        const val EPG_PAST_S = 2 * 3600L
        const val EPG_AHEAD_S = 12 * 3600L
    }
}
