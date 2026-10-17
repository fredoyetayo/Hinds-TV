package com.hindstv.data

import com.hindstv.data.db.ChannelDao
import com.hindstv.data.db.ChannelEntity
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.FavoriteEntity
import com.hindstv.data.db.GenreEntity
import com.hindstv.data.stalker.StalkerClient
import com.hindstv.data.stalker.StalkerException
import kotlinx.coroutines.flow.Flow

/**
 * Channels live in the local database; the UI only ever reads from there, so browsing is instant
 * and works while the portal is slow. [sync] refreshes the cache from the portal.
 */
class ChannelRepository(
    private val dao: ChannelDao,
    private val store: ProfileStore,
    private val client: () -> StalkerClient,
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
        val portal = client()
        val channels = portal.channels()
        // Groups are optional: some panels don't implement get_genres; channels still show under "All".
        val genres = try {
            portal.genres()
        } catch (e: StalkerException.BadResponse) {
            emptyList()
        } catch (e: StalkerException.ServerError) {
            emptyList()
        }
        if (channels.isEmpty()) throw StalkerException.BadResponse("The portal returned no channels")
        dao.replaceAll(
            genres = genres.mapIndexed { i, g -> GenreEntity(g.id, g.title, i, g.censored) },
            channels = channels.mapIndexed { i, c ->
                ChannelEntity(
                    id = c.id, sortOrder = i, number = c.number, name = c.name, genreId = c.genreId,
                    logo = c.logo, cmd = c.cmd, useTmpLink = c.useTmpLink, hasArchive = c.hasArchive,
                    archiveHours = c.archiveHours, xmltvId = c.xmltvId, censored = c.censored,
                )
            },
        )
        portal.session?.let { store.endpoint = it.endpoint }
        store.lastSyncAt = System.currentTimeMillis()
        return channels.size
    }

    /** Asks the portal for a fresh, playable URL (tokens in these links expire quickly). */
    suspend fun streamUrl(channelId: String): String {
        val cmd = dao.cmd(channelId) ?: throw StalkerException.StreamUnavailable("channel not found")
        val portal = client()
        val url = portal.streamUrl(cmd)
        portal.session?.let { if (store.endpoint != it.endpoint) store.endpoint = it.endpoint }
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
    }
}
