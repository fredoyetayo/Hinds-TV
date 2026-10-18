package com.hindstv.data

import com.hindstv.data.db.MediaDao
import com.hindstv.data.db.RadioEntity
import com.hindstv.data.db.WatchProgressEntity
import com.hindstv.data.source.ChannelSource
import com.hindstv.data.source.PlaylistException
import com.hindstv.data.source.VideoCategory
import com.hindstv.data.source.VideoEpisode
import com.hindstv.data.source.VideoItem
import com.hindstv.data.source.VideoPage
import com.hindstv.data.source.VideoSort
import com.hindstv.data.source.numberedEpisodes
import com.hindstv.player.ResumePolicy
import kotlinx.coroutines.flow.Flow

/**
 * Videos, radio and resume positions. Portals are browsed online (their libraries can be huge);
 * playlists were stored by [ChannelRepository.sync]. Resume positions are always local.
 */
class MediaRepository(
    private val dao: MediaDao,
    private val source: () -> ChannelSource,
) {
    suspend fun videoCategories(): List<VideoCategory> {
        val active = source()
        return if (active.hasOnlineVideos) {
            active.videoCategories()
        } else {
            dao.videoCategories().map { VideoCategory(it.id, it.title) }
        }
    }

    suspend fun videoPage(categoryId: String, page: Int, sort: VideoSort = VideoSort.Newest, search: String = ""): VideoPage {
        val active = source()
        if (active.hasOnlineVideos) return active.videoPage(categoryId, page, sort, search)
        val rows = dao.videos(
            categoryId, search.trim(), byName = sort == VideoSort.AtoZ, limit = PAGE_SIZE + 1, offset = (page - 1) * PAGE_SIZE,
        )
        return VideoPage(
            items = rows.take(PAGE_SIZE).map { VideoItem(id = it.id, categoryId = it.categoryId, title = it.title, poster = it.poster, cmd = it.cmd) },
            hasMore = rows.size > PAGE_SIZE,
        )
    }

    suspend fun episodes(item: VideoItem): List<VideoEpisode> = when {
        item.episodeNumbers.isNotEmpty() -> item.numberedEpisodes()
        item.isSeries -> source().videoEpisodes(item)
        else -> emptyList()
    }

    suspend fun videoUrl(item: VideoItem, episode: VideoEpisode?): String =
        source().videoUrl(episode?.cmd ?: item.cmd, episode?.series)

    suspend fun searchVideos(query: String, limit: Int = 40): List<VideoItem> {
        val active = source()
        if (active.hasOnlineVideos) return active.searchVideos(query).take(limit)
        return dao.searchVideos(query, limit).map {
            VideoItem(id = it.id, categoryId = it.categoryId, title = it.title, poster = it.poster, cmd = it.cmd)
        }
    }

    suspend fun searchRadio(query: String, limit: Int = 30): List<RadioEntity> = dao.searchRadio(query, limit)

    fun radio(): Flow<List<RadioEntity>> = dao.radio()

    suspend fun radioUrls(stationId: String): List<String> {
        val cmd = dao.radioCmd(stationId) ?: throw PlaylistException("That station no longer exists.")
        return source().radioUrls(cmd)
    }

    // --- Resume positions ------------------------------------------------------------------------

    fun continueWatching(): Flow<List<WatchProgressEntity>> =
        dao.continueWatching(ResumePolicy.MIN_RESUME_MS, limit = 20)

    fun progressFor(videoId: String): Flow<List<WatchProgressEntity>> = dao.progressFor(videoId)

    suspend fun progress(item: VideoItem, episode: VideoEpisode?): WatchProgressEntity? = dao.progress(progressKey(item, episode))

    suspend fun saveProgress(item: VideoItem, episode: VideoEpisode?, positionMs: Long, durationMs: Long) {
        dao.saveProgress(
            WatchProgressEntity(
                key = progressKey(item, episode),
                videoId = item.id,
                categoryId = item.categoryId,
                title = item.title,
                description = item.description,
                poster = item.poster,
                year = item.year,
                durationMin = item.durationMin,
                cmd = item.cmd,
                episodeNumbers = item.episodeNumbers.joinToString(","),
                isSeries = item.isSeries,
                episodeKey = episode?.key,
                episodeTitle = episode?.title,
                episodeSeason = episode?.season,
                episodeNumber = episode?.number,
                episodeCmd = episode?.cmd,
                episodeSeries = episode?.series,
                positionMs = positionMs,
                durationMs = durationMs,
                finished = ResumePolicy.isFinished(positionMs, durationMs),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Resume positions belong to one provider; a different provider starts with a clean slate. */
    suspend fun clearProgress() = dao.clearProgress()

    companion object {
        const val PAGE_SIZE = 60

        fun progressKey(item: VideoItem, episode: VideoEpisode?): String =
            if (episode == null) item.id else "${item.id}#${episode.key}"
    }
}

fun WatchProgressEntity.toVideoItem() = VideoItem(
    id = videoId,
    categoryId = categoryId,
    title = title,
    description = description,
    poster = poster,
    year = year,
    durationMin = durationMin,
    cmd = cmd,
    episodeNumbers = episodeNumbers.split(',').mapNotNull { it.trim().toIntOrNull() },
    isSeries = isSeries,
)

fun WatchProgressEntity.toEpisode(): VideoEpisode? {
    val key = episodeKey ?: return null
    return VideoEpisode(key, episodeTitle.orEmpty(), episodeSeason, episodeNumber ?: 0, episodeCmd ?: cmd, episodeSeries)
}
