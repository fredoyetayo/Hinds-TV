package com.hindstv.ui

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.hindstv.HindsApp
import com.hindstv.R
import com.hindstv.data.stalker.StalkerException
import com.hindstv.data.db.WatchProgressEntity
import com.hindstv.data.source.ALL_VIDEOS
import com.hindstv.data.source.VideoCategory
import com.hindstv.data.source.VideoSort
import com.hindstv.data.source.VideoEpisode
import com.hindstv.data.source.VideoItem
import com.hindstv.data.toEpisode
import com.hindstv.data.toVideoItem
import com.hindstv.player.ResumePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the user is inside Videos. */
sealed interface VideoPageUi {
    data object Library : VideoPageUi
    data class Details(val item: VideoItem) : VideoPageUi
    data object Player : VideoPageUi
}

/** What the VOD grid shows: one category (or all), in an order, optionally filtered by title. */
data class GridQuery(val categoryId: String, val sort: VideoSort = VideoSort.Newest, val search: String = "")

/** The grid's contents, filled page by page as focus nears the bottom. */
data class VideoGrid(
    val query: GridQuery,
    val items: List<VideoItem> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val nextPage: Int = 1,
    val error: String? = null,
)

sealed interface LibraryUi {
    data object Loading : LibraryUi
    data class Ready(val categories: List<VideoCategory>) : LibraryUi
    data class Failed(val message: String) : LibraryUi
}

sealed interface EpisodesUi {
    data object None : EpisodesUi
    data object Loading : EpisodesUi
    data class Ready(val episodes: List<VideoEpisode>) : EpisodesUi
    data class Failed(val message: String) : EpisodesUi
}

data class VodUi(
    val item: VideoItem,
    val episode: VideoEpisode?,
    val status: PlayStatus = PlayStatus.Tuning,
    val error: String? = null,
    val paused: Boolean = false,
    val controlsVisible: Boolean = true,
)

/** Position readout for the player controls; only ticks while they are on screen. */
data class VodPosition(val positionMs: Long = 0, val durationMs: Long = 0)

/**
 * Videos: library rows, details with episodes and resume, and the video player. Playback goes
 * through the app's single player; live TV is released before a video starts (one connection per
 * account) and resumes when the user goes back to Live.
 */
class VideoViewModel(private val app: HindsApp) : ViewModel() {
    private val media = app.container.media
    private val engine = app.container.player
    private val trackPicker = app.container.trackPicker

    val player: Player get() = engine.player

    private val _page = MutableStateFlow<VideoPageUi>(VideoPageUi.Library)
    val page: StateFlow<VideoPageUi> = _page.asStateFlow()

    private val _library = MutableStateFlow<LibraryUi>(LibraryUi.Loading)
    val library: StateFlow<LibraryUi> = _library.asStateFlow()

    private val _gridQuery = MutableStateFlow<GridQuery?>(null)
    val gridQuery: StateFlow<GridQuery?> = _gridQuery.asStateFlow()

    private val _grid = MutableStateFlow<VideoGrid?>(null)
    val grid: StateFlow<VideoGrid?> = _grid.asStateFlow()

    /** Recently shown grids, so going back to a category is instant. */
    private val gridCache = LinkedHashMap<GridQuery, VideoGrid>()
    private var gridJob: Job? = null
    private var searchJob: Job? = null

    val continueWatching: StateFlow<List<WatchProgressEntity>> = media.continueWatching()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _episodes = MutableStateFlow<EpisodesUi>(EpisodesUi.None)
    val episodes: StateFlow<EpisodesUi> = _episodes.asStateFlow()

    /** Saved positions for the item on the details page, keyed by progress key. */
    private val _detailsProgress = MutableStateFlow<List<WatchProgressEntity>>(emptyList())
    val detailsProgress: StateFlow<List<WatchProgressEntity>> = _detailsProgress.asStateFlow()

    private val _vod = MutableStateFlow<VodUi?>(null)
    val vod: StateFlow<VodUi?> = _vod.asStateFlow()

    private val _position = MutableStateFlow(VodPosition())
    val position: StateFlow<VodPosition> = _position.asStateFlow()

    private var libraryJob: Job? = null
    private var detailsJob: Job? = null
    private var progressJob: Job? = null
    private var playJob: Job? = null
    private var saveJob: Job? = null
    private var controlsJob: Job? = null
    private var tickJob: Job? = null
    private var seekTarget: Long? = null
    private var seekSettleJob: Job? = null
    /** Details page the player returns to. */
    private var returnTo: VideoItem? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (engine.owner !== this@VideoViewModel || _vod.value == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> setStatus(PlayStatus.Buffering)
                Player.STATE_READY -> setStatus(PlayStatus.Playing)
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (engine.owner !== this@VideoViewModel) return
            if (!isPlaying) saveProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (engine.owner !== this@VideoViewModel) return
            saveProgress()
            _vod.update { it?.copy(status = PlayStatus.Error, error = Errors.describe(error), controlsVisible = true) }
        }
    }

    init {
        engine.player.addListener(listener)
    }

    // --- Library ---------------------------------------------------------------------------------

    /** Called when VOD opens: loads categories once (again after a failure). */
    fun onOpen() {
        if (_library.value is LibraryUi.Ready || libraryJob?.isActive == true) return
        _library.value = LibraryUi.Loading
        libraryJob = viewModelScope.launch {
            _library.value = try {
                val categories = media.videoCategories()
                if (_gridQuery.value == null) {
                    val start = when {
                        continueWatching.value.isNotEmpty() -> CONTINUE
                        else -> categories.firstOrNull()?.id ?: ALL_VIDEOS
                    }
                    selectCategory(start)
                }
                LibraryUi.Ready(listOf(VideoCategory(ALL_VIDEOS, app.getString(com.hindstv.R.string.all_vod))) + categories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryUi.Failed(Errors.describe(e))
            }
        }
    }

    /** Shows a category in the grid (keeps the current order; clears the title filter). */
    fun selectCategory(categoryId: String) {
        val current = _gridQuery.value
        if (current?.categoryId == categoryId && current.search.isEmpty()) return
        show(GridQuery(categoryId, current?.sort ?: VideoSort.Newest))
    }

    fun setSort(sort: VideoSort) {
        val current = _gridQuery.value ?: return
        if (current.sort != sort) show(current.copy(sort = sort))
    }

    /** Filters the current category by title as the user types (debounced). */
    fun setSearch(text: String) {
        val current = _gridQuery.value ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (current.search != text.trim()) show(current.copy(search = text.trim()))
        }
    }

    private fun show(query: GridQuery) {
        gridJob?.cancel()
        _gridQuery.value = query
        if (query.categoryId == CONTINUE) {
            _grid.value = null
            return
        }
        val cached = gridCache[query]
        _grid.value = cached ?: VideoGrid(query)
        if (cached == null) loadMore()
    }

    /** Loads the next page of the grid when it opens or focus nears its end. */
    fun loadMore() {
        val grid = _grid.value ?: return
        if (grid.loading || !grid.hasMore || grid.error != null) return
        _grid.value = grid.copy(loading = true)
        gridJob = viewModelScope.launch {
            val q = grid.query
            val updated = try {
                val result = media.videoPage(q.categoryId, grid.nextPage, q.sort, q.search)
                grid.copy(
                    items = (grid.items + result.items).distinctBy { it.id },
                    loading = false,
                    hasMore = result.hasMore && result.items.isNotEmpty(),
                    nextPage = grid.nextPage + 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                grid.copy(loading = false, error = Errors.describe(e))
            }
            if (_gridQuery.value == q) _grid.value = updated
            if (q.search.isEmpty() && updated.error == null) {
                gridCache[q] = updated
                if (gridCache.size > GRID_CACHE) gridCache.remove(gridCache.keys.first())
            }
        }
    }

    /** Try again after a failed page. */
    fun retryGrid() {
        val grid = _grid.value ?: return
        _grid.value = grid.copy(error = null)
        loadMore()
    }

    /** After a provider change everything is reloaded. */
    fun reset() {
        stopPlayback(save = false)
        libraryJob?.cancel()
        _library.value = LibraryUi.Loading
        gridCache.clear()
        _gridQuery.value = null
        _grid.value = null
        _page.value = VideoPageUi.Library
    }

    // --- Details ---------------------------------------------------------------------------------

    /**
     * The card details were opened from (a video id, or a Continue watching key), so Back lands on
     * it again instead of at the top of the library. The library consumes it once.
     */
    var returnFocusKey: String? = null

    fun openDetails(item: VideoItem) {
        if (_page.value == VideoPageUi.Library) returnFocusKey = item.id
        _page.value = VideoPageUi.Details(item)
        progressJob?.cancel()
        progressJob = viewModelScope.launch { media.progressFor(item.id).collect { _detailsProgress.value = it } }
        if (_episodes.value.let { it is EpisodesUi.Ready || it is EpisodesUi.Loading } && returnTo?.id == item.id) return
        returnTo = item
        detailsJob?.cancel()
        if (!item.hasEpisodes) {
            _episodes.value = EpisodesUi.None
            return
        }
        _episodes.value = EpisodesUi.Loading
        detailsJob = viewModelScope.launch {
            _episodes.value = try {
                EpisodesUi.Ready(media.episodes(item))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EpisodesUi.Failed(Errors.describe(e))
            }
        }
    }

    /** Back from details to the library. */
    fun closeDetails() {
        progressJob?.cancel()
        _page.value = VideoPageUi.Library
    }

    /** "Continue watching": straight back into the video where it stopped. */
    fun resume(progress: WatchProgressEntity) {
        val item = progress.toVideoItem()
        returnTo = null
        openDetails(item)
        returnFocusKey = progress.key
        play(item, progress.toEpisode(), ResumePolicy.startPosition(progress.positionMs, progress.durationMs, progress.finished))
    }

    // --- Playback --------------------------------------------------------------------------------

    /** Plays [item] (or one of its episodes) from [startMs]. */
    fun play(item: VideoItem, episode: VideoEpisode?, startMs: Long) {
        stopPlayback(save = true)
        returnTo = item
        _vod.value = VodUi(item = item, episode = episode)
        _position.value = VodPosition(startMs, 0)
        _page.value = VideoPageUi.Player
        showControls()
        playJob = viewModelScope.launch {
            try {
                val url = media.videoUrl(item, episode)
                engine.play(url, owner = this@VideoViewModel, vod = true, startMs = startMs)
                startSaving()
                // A stream that connects but never delivers a picture would load for ever.
                delay(START_TIMEOUT_MS)
                if (_vod.value?.status == PlayStatus.Tuning || (_vod.value?.status == PlayStatus.Buffering && engine.player.currentPosition <= startMs)) {
                    if (engine.owner === this@VideoViewModel) engine.stop()
                    _vod.update { it?.copy(status = PlayStatus.Error, error = app.getString(R.string.vod_no_picture), controlsVisible = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StalkerException.StreamUnavailable) {
                val message = if (!e.storageTimeout && e.reason.contains("nothing_to_play", ignoreCase = true)) {
                    app.getString(R.string.vod_nothing_to_play)
                } else {
                    Errors.describe(e)
                }
                _vod.update { it?.copy(status = PlayStatus.Error, error = message, controlsVisible = true) }
            } catch (e: Exception) {
                _vod.update { it?.copy(status = PlayStatus.Error, error = Errors.describe(e), controlsVisible = true) }
            }
        }
    }

    /** Back from the player: save where we are and return to the details page. */
    fun closePlayer() {
        stopPlayback(save = true)
        val item = returnTo
        if (item != null) openDetails(item) else _page.value = VideoPageUi.Library
    }

    /** Leaving Videos entirely (to Live, Radio or Settings). */
    fun onLeave() {
        stopPlayback(save = true)
        if (_page.value == VideoPageUi.Player) {
            val item = returnTo
            _page.value = if (item != null) VideoPageUi.Details(item) else VideoPageUi.Library
        }
    }

    /** The app is going to the background: Fire OS wants decoders released. */
    fun onBackground() {
        if (_vod.value != null) closePlayer()
    }

    /**
     * Remote handling while a video plays (no focusable UI over the picture):
     * Select/⏯ pause and resume, ◀/▶ skip 10 s, ⏪/⏩ skip 30 s, ▲/▼ show the controls.
     * Back is handled by the screen. Returns true when the key was used.
     */
    fun onPlayerKey(keyCode: Int): Boolean {
        val vod = _vod.value ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> if (vod.status == PlayStatus.Error) retry() else togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-SHORT_SKIP_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(SHORT_SKIP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-LONG_SKIP_MS)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(LONG_SKIP_MS)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> trackPicker.open()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> showControls()
            else -> return false
        }
        return true
    }

    private fun retry() {
        val vod = _vod.value ?: return
        val start = _position.value.positionMs
        play(vod.item, vod.episode, start)
    }

    private fun togglePause() {
        val vod = _vod.value ?: return
        if (engine.owner !== this || !engine.hasMedia) return
        val pause = !vod.paused
        engine.player.playWhenReady = !pause
        _vod.update { it?.copy(paused = pause) }
        showControls(stay = pause)
    }

    private fun seekBy(deltaMs: Long) {
        if (engine.owner !== this || !engine.hasMedia) return
        val player = engine.player
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        // Presses in quick succession add up from the previous target, not from where playback
        // has got to, so five quick ⏩ really skip 2½ minutes.
        val base = seekTarget ?: player.currentPosition
        val target = (base + deltaMs).coerceIn(0, (duration - 1_000).coerceAtLeast(0))
        seekTarget = target
        player.seekTo(target)
        _position.value = VodPosition(target, player.duration.coerceAtLeast(0))
        seekSettleJob?.cancel()
        seekSettleJob = viewModelScope.launch {
            delay(SEEK_SETTLE_MS)
            seekTarget = null
        }
        showControls()
    }

    /** Shows the controls; they hide again after a few seconds unless paused. */
    fun showControls(stay: Boolean = _vod.value?.paused == true) {
        _vod.update { it?.copy(controlsVisible = true) }
        startTicking()
        controlsJob?.cancel()
        if (!stay) {
            controlsJob = viewModelScope.launch {
                delay(CONTROLS_MS)
                if (_vod.value?.let { !it.paused && it.status != PlayStatus.Error } == true) {
                    _vod.update { it?.copy(controlsVisible = false) }
                    tickJob?.cancel()
                }
            }
        }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (isActive) {
                if (engine.owner === this@VideoViewModel && engine.hasMedia && seekTarget == null) {
                    val player = engine.player
                    _position.value = VodPosition(player.currentPosition, player.duration.coerceAtLeast(0))
                }
                delay(TICK_MS)
            }
        }
    }

    private fun startSaving() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            while (isActive) {
                delay(SAVE_EVERY_MS)
                if (engine.player.isPlaying) saveProgress()
            }
        }
    }

    /** Remembers where the user is in the current video (Netflix-style resume). */
    private fun saveProgress() {
        val vod = _vod.value ?: return
        if (engine.owner !== this || !engine.hasMedia) return
        val player = engine.player
        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        if (position <= 0) return
        viewModelScope.launch { media.saveProgress(vod.item, vod.episode, position, duration) }
    }

    private fun onEnded() {
        val vod = _vod.value ?: return
        val duration = engine.player.duration.coerceAtLeast(0)
        viewModelScope.launch { media.saveProgress(vod.item, vod.episode, duration, duration) }
        val next = nextEpisodeAfter(vod.episode)
        if (next != null) {
            play(vod.item, next, startMs = 0) // series run on, like Netflix
        } else {
            closePlayer()
        }
    }

    private fun stopPlayback(save: Boolean) {
        if (save) saveProgress()
        playJob?.cancel()
        saveJob?.cancel()
        controlsJob?.cancel()
        tickJob?.cancel()
        seekSettleJob?.cancel()
        seekTarget = null
        if (engine.owner === this) engine.stop()
        _vod.value = null
    }

    /** The episode after [episode] in the open series, for "Up next" and running on. */
    fun nextEpisodeAfter(episode: VideoEpisode?): VideoEpisode? {
        if (episode == null) return null
        val list = (_episodes.value as? EpisodesUi.Ready)?.episodes ?: return null
        val index = list.indexOfFirst { it.key == episode.key }
        return if (index >= 0) list.getOrNull(index + 1) else null
    }

    private fun setStatus(status: PlayStatus) {
        _vod.update { it?.copy(status = status, error = null) }
    }

    override fun onCleared() {
        engine.player.removeListener(listener)
    }

    companion object {
        /** Rail entry for "Continue watching" (not a provider category). */
        const val CONTINUE = "__continue"
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val GRID_CACHE = 8
        private const val START_TIMEOUT_MS = 30_000L
        private const val SHORT_SKIP_MS = 10_000L
        private const val LONG_SKIP_MS = 30_000L
        private const val CONTROLS_MS = 4_000L
        private const val TICK_MS = 500L
        private const val SAVE_EVERY_MS = 10_000L
        private const val SEEK_SETTLE_MS = 1_200L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VideoViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HindsApp)
            }
        }
    }
}
