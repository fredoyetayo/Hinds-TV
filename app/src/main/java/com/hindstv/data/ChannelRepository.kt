package com.hindstv.data

import com.hindstv.data.db.ChannelDao
import com.hindstv.data.db.ChannelEntity
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.EpgDao
import com.hindstv.data.db.FavoriteItemEntity
import com.hindstv.data.db.FavoriteListEntity
import com.hindstv.data.db.GenreEntity
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.data.db.RadioEntity
import com.hindstv.data.db.VideoCategoryEntity
import com.hindstv.data.db.VideoEntity
import com.hindstv.data.source.ChannelSource
import com.hindstv.data.source.EpgChannelRef
import com.hindstv.data.source.PlaylistException
import com.hindstv.data.source.StalkerSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Channels and guide data live in the local database; the UI only ever reads from there, so
 * browsing is instant and works while the provider is slow. [sync] and [syncEpg] refresh it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRepository(
    private val dao: ChannelDao,
    private val epgDao: EpgDao,
    private val store: ProfileStore,
    private val source: () -> ChannelSource,
) {
    fun genres(): Flow<List<GenreEntity>> = dao.genres()

    private val recentIds = MutableStateFlow(store.recentChannelIds)

    fun channels(genreId: String): Flow<List<ChannelRow>> {
        if (genreId == RECENT) return recentChannels()
        listIdOf(genreId)?.let { return dao.listChannels(it) }
        return if (genreId == ALL) dao.allChannels() else dao.channelsInGenre(genreId)
    }

    // --- Recently watched ------------------------------------------------------------------------

    /** Newest first; ids a re-sync dropped are skipped. */
    private fun recentChannels(): Flow<List<ChannelRow>> = recentIds.flatMapLatest { ids ->
        dao.channelsByIds(ids).map { rows ->
            val byId = rows.associateBy { it.id }
            ids.mapNotNull(byId::get)
        }
    }

    fun noteWatched(channelId: String) {
        if (recentIds.value.firstOrNull() == channelId) return
        recentIds.value = pushRecent(recentIds.value, channelId)
        store.recentChannelIds = recentIds.value
    }

    /** Recent channels belong to one provider. */
    fun clearRecent() {
        recentIds.value = emptyList()
        store.recentChannelIds = emptyList()
    }

    // --- Favourites lists --------------------------------------------------------------------------

    fun favoriteLists(): Flow<List<FavoriteListEntity>> = dao.favoriteLists()

    suspend fun listsContaining(channelId: String): Set<Long> = dao.listsContaining(channelId).toSet()

    /** Creates a list and returns its id. */
    suspend fun createList(name: String): Long {
        val order = (dao.favoriteListsNow().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return dao.insertList(FavoriteListEntity(name = name.trim(), sortOrder = order))
    }

    suspend fun renameList(listId: Long, name: String) = dao.renameList(listId, name.trim())

    fun listSummaries(): Flow<List<com.hindstv.data.db.ListSummary>> = dao.listSummaries()

    /** Moves a list up ([delta] −1) or down (+1) in the group column. */
    suspend fun moveList(listId: Long, delta: Int) {
        val lists = dao.favoriteListsNow().toMutableList()
        val from = lists.indexOfFirst { it.id == listId }
        val to = from + delta
        if (from < 0 || to !in lists.indices) return
        lists.add(to, lists.removeAt(from))
        lists.forEachIndexed { i, l -> if (l.sortOrder != i) dao.setListOrder(l.id, i) }
    }

    suspend fun channelCount(): Int = dao.channelCount()

    suspend fun deleteList(listId: Long) = dao.deleteList(listId)

    /** Adds the channel to the list or takes it out. Returns true if it is now in the list. */
    suspend fun toggleInList(listId: Long, channelId: String): Boolean =
        if (dao.isInList(listId, channelId)) {
            dao.removeFromList(listId, channelId)
            false
        } else {
            dao.addToList(FavoriteItemEntity(listId, channelId, System.currentTimeMillis()))
            true
        }

    /** The first list, created as "Favourites" if there is none yet (fresh installs). */
    suspend fun defaultListId(defaultName: String): Long =
        dao.favoriteListsNow().firstOrNull()?.id ?: createList(defaultName)

    suspend fun searchChannels(query: String, limit: Int = 40): List<ChannelRow> = dao.searchChannels(query, limit)

    suspend fun searchProgrammes(query: String, nowSec: Long, limit: Int = 30): List<ProgrammeEntity> =
        epgDao.search(query, nowSec, limit)

    suspend fun channel(id: String): ChannelRow? = dao.channelRow(id)

    suspend fun hasChannels(): Boolean = dao.channelCount() > 0

    /** Downloads genres + channels and swaps them in atomically. Returns the channel count. */
    suspend fun sync(): Int {
        val active = source()
        val content = active.load()
        if (content.channels.isEmpty() && content.videos.isEmpty() && content.radio.isEmpty()) {
            throw PlaylistException("No channels were found.")
        }
        dao.replaceAll(
            genres = content.genres.mapIndexed { i, g -> GenreEntity(g.id, g.title, i, g.censored) },
            channels = content.channels.mapIndexed { i, c ->
                ChannelEntity(
                    id = c.id, sortOrder = i, number = c.number, name = c.name, genreId = c.genreId,
                    logo = c.logo, cmd = c.cmd, useTmpLink = c.useTmpLink, hasArchive = c.hasArchive,
                    archiveHours = c.archiveHours, xmltvId = c.epgId, censored = c.censored,
                )
            },
            videoCategories = content.videoCategories.mapIndexed { i, c -> VideoCategoryEntity(c.id, c.title, i) },
            videos = content.videos.mapIndexed { i, v -> VideoEntity(v.id, v.categoryId, i, v.title, v.poster, v.cmd) },
            radio = content.radio.mapIndexed { i, r -> RadioEntity(r.id, i, r.number, r.name, r.logo, r.cmd) },
        )
        store.discoveredEpgUrl = content.epgUrl
        rememberSession(active)
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

    /** Keeps the portal endpoint and session token for the next launch (skips discovery and handshake). */
    private fun rememberSession(active: ChannelSource) {
        val client = (active as? StalkerSource)?.client ?: return
        client.session?.let { if (store.endpoint != it.endpoint) store.endpoint = it.endpoint }
        client.currentToken?.let { if (store.portalToken != it) store.portalToken = it }
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
        rememberSession(active)
        return url
    }

    companion object {
        private const val LIST_PREFIX = "__list:"

        /** Group id for a favourites list, so lists sit in the group column like any group. */
        fun listGroupId(listId: Long) = "$LIST_PREFIX$listId"

        fun listIdOf(genreId: String): Long? =
            if (genreId.startsWith(LIST_PREFIX)) genreId.removePrefix(LIST_PREFIX).toLongOrNull() else null
        const val ALL = "__all"
        const val RECENT = "__recent"
        const val EPG_PAST_S = 2 * 3600L
        const val EPG_AHEAD_S = 12 * 3600L
    }
}
