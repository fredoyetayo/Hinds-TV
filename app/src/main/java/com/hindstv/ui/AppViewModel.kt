package com.hindstv.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.hindstv.HindsApp
import com.hindstv.R
import com.hindstv.data.ChannelRepository
import com.hindstv.data.db.ChannelRow
import com.hindstv.data.db.ProgrammeEntity
import com.hindstv.data.source.SourceProfile
import com.hindstv.data.stalker.Mac
import com.hindstv.data.stalker.PortalProfile
import com.hindstv.data.stalker.StalkerClient
import com.hindstv.player.ZapController
import kotlinx.coroutines.CancellationException
import java.util.Calendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Top-level places. Live TV (with its overlays) is home; videos and radio sit beside it. */
enum class Screen { Setup, Tv, Videos, Radio, Search, Settings }

enum class Overlay { None, Banner, ChannelList, Guide }

enum class PlayStatus { Idle, Tuning, Buffering, Playing, Stopped, Error }

data class PlaybackUi(
    val channel: ChannelRow? = null,
    val status: PlayStatus = PlayStatus.Idle,
    val error: String? = null,
)

/** A group in the channel list; [listId] is set for the user's own favourites lists. */
data class GenreItem(val id: String, val title: String, val listId: Long? = null)

/** "Add to list" for one channel: which of the user's lists it is in. */
data class ListPickerUi(
    val channel: ChannelRow,
    val lists: List<com.hindstv.data.db.FavoriteListEntity>,
    val memberOf: Set<Long>,
)

/** Rename or delete one favourites list. */
data class ListEditUi(val listId: Long, val name: String)

/** Channels of one group, tagged with the group so the UI never mistakes a stale list for a new one. */
data class GenreChannels(val genreId: String, val channels: List<ChannelRow>)

enum class SetupMode { Portal, Playlist }

data class SetupForm(
    val mode: SetupMode = SetupMode.Portal,
    val playlistUrl: String = "",
    val epgUrl: String = "",
    val portalUrl: String = "",
    val mac: String = PortalProfile.DEFAULT_MAC_PREFIX,
    val stbType: String = PortalProfile.DEFAULT_STB_TYPE,
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
)

data class SetupUi(
    val form: SetupForm = SetupForm(),
    val busy: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val canCancel: Boolean = false,
    /** Set when the user generated a new MAC: it must be registered with the provider. */
    val generatedMac: String? = null,
)

sealed interface SyncUi {
    data object Idle : SyncUi
    data object Running : SyncUi
    data class Failed(val message: String) : SyncUi
}

/**
 * Single source of UI state for the whole app (it is one screen with overlays, so one ViewModel
 * keeps zapping, overlays and playback consistent). Runs on the main thread; portal and database
 * calls switch threads internally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val app: HindsApp) : ViewModel() {
    private val container = app.container
    private val store = container.profileStore
    private val repo = container.channels
    private val engine = container.player

    val player: Player get() = engine.player

    private val _screen = MutableStateFlow(if (store.profile == null) Screen.Setup else Screen.Tv)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** Bumped when the user connects a different provider (videos and radio start over). */
    private val _profileVersion = MutableStateFlow(0)
    val profileVersion: StateFlow<Int> = _profileVersion.asStateFlow()

    private val _setup = MutableStateFlow(SetupUi())
    val setup: StateFlow<SetupUi> = _setup.asStateFlow()

    // --- Browsing --------------------------------------------------------------------------------

    val genres: StateFlow<List<GenreItem>> = combine(repo.favoriteLists(), repo.genres()) { lists, genres ->
        lists.map { GenreItem(ChannelRepository.listGroupId(it.id), "★  " + it.name, it.id) } +
            GenreItem(ChannelRepository.ALL, app.getString(R.string.genre_all)) +
            genres.map { GenreItem(it.id, it.title) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _listPicker = MutableStateFlow<ListPickerUi?>(null)
    val listPicker: StateFlow<ListPickerUi?> = _listPicker.asStateFlow()

    private val _listEdit = MutableStateFlow<ListEditUi?>(null)
    val listEdit: StateFlow<ListEditUi?> = _listEdit.asStateFlow()

    private val _selectedGenre = MutableStateFlow(store.lastGenreId ?: ChannelRepository.ALL)
    val selectedGenre: StateFlow<String> = _selectedGenre.asStateFlow()

    val channels: StateFlow<GenreChannels?> = _selectedGenre
        .flatMapLatest { id -> repo.channels(id).map { GenreChannels(id, it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var genreFocusJob: Job? = null

    // --- Playback --------------------------------------------------------------------------------

    private val _playback = MutableStateFlow(PlaybackUi())
    val playback: StateFlow<PlaybackUi> = _playback.asStateFlow()

    private val _overlay = MutableStateFlow(Overlay.ChannelList)
    val overlay: StateFlow<Overlay> = _overlay.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _sync = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val sync: StateFlow<SyncUi> = _sync.asStateFlow()

    /** Zapping happens within the group the channel was chosen from. */
    private val zapGenre = MutableStateFlow(store.lastGenreId ?: ChannelRepository.ALL)
    private val zapList: StateFlow<List<ChannelRow>> = zapGenre
        .flatMapLatest { repo.channels(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // --- Guide -----------------------------------------------------------------------------------

    /** Bumped whenever new guide data has been stored. */
    private val _epgRevision = MutableStateFlow(0)
    val epgRevision: StateFlow<Int> = _epgRevision.asStateFlow()

    /** Wall-clock seconds, ticking on each minute boundary (drives clocks, progress bars, now/next). */
    val minute: StateFlow<Long> = flow {
        while (true) {
            val nowMs = System.currentTimeMillis()
            emit(nowMs / 1000)
            delay(60_000 - nowMs % 60_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis() / 1000)

    /** Whether the light palette applies now: Automatic re-checks on the minute tick. */
    val lightTheme: StateFlow<Boolean> = combine(container.themeMode, minute) { mode, now ->
        mode.isLight(Calendar.getInstance().apply { timeInMillis = now * 1000 }.get(Calendar.HOUR_OF_DAY))
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.themeMode.value.isLight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))

    /** What is on now, per channel id (for the channel list). */
    val onAir: StateFlow<Map<String, ProgrammeEntity>> = combine(minute, _epgRevision) { now, _ -> now }
        .mapLatest { now -> repo.airingAt(now).associateBy { it.channelId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Now and next for the playing channel (for the info banner). */
    val nowNext: StateFlow<List<ProgrammeEntity>> = combine(
        _playback.map { it.channel?.id }.distinctUntilChanged(), minute, _epgRevision,
    ) { id, now, _ -> id to now }
        .mapLatest { (id, now) -> if (id == null) emptyList() else repo.nowNext(id, now) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var epgJob: Job? = null

    suspend fun guide(channelIds: List<String>, fromSec: Long, toSec: Long): List<ProgrammeEntity> =
        repo.guide(channelIds, fromSec, toSec)

    /** How long the last channel took from the button press to its first picture (shown in the banner). */
    private val _tuneMs = MutableStateFlow<Long?>(null)
    val tuneMs: StateFlow<Long?> = _tuneMs.asStateFlow()
    private var tuneStartedAt = 0L
    /** Measures app start to first picture once, on the first channel after launch. */
    private var coldStart = true

    private val zapper = ZapController<ChannelRow>(viewModelScope, SystemClock::uptimeMillis) { tune(it, isRetry = false) }
    private var previousChannel: ChannelRow? = null
    private var autoRetries = 0
    private var resumeOnForeground = false
    private var lastBackAt = 0L
    private var bannerJob: Job? = null
    private var toastJob: Job? = null
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (engine.owner !== this@AppViewModel || _playback.value.channel == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> setStatus(PlayStatus.Buffering)
                Player.STATE_READY -> {
                    autoRetries = 0
                    setStatus(PlayStatus.Playing)
                }
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            if (engine.owner !== this@AppViewModel || tuneStartedAt == 0L) return
            val now = SystemClock.uptimeMillis()
            val ms = if (coldStart) now - android.os.Process.getStartUptimeMillis() else now - tuneStartedAt
            android.util.Log.i("HindsPerf", (if (coldStart) "start to first frame " else "zap to first frame ") + "${ms}ms")
            coldStart = false
            tuneStartedAt = 0L
            _tuneMs.value = ms
        }

        override fun onPlayerError(error: PlaybackException) {
            if (engine.owner !== this@AppViewModel) return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                engine.player.seekToDefaultPosition()
                engine.player.prepare()
                return
            }
            val channel = _playback.value.channel ?: return
            if (autoRetries < MAX_AUTO_RETRIES) {
                // Stream links are short-lived; one retry with a fresh link fixes most failures.
                autoRetries++
                retryJob?.cancel()
                retryJob = viewModelScope.launch {
                    delay(1_000)
                    if (_playback.value.channel?.id == channel.id) tune(channel, isRetry = true)
                }
            } else {
                _playback.update { it.copy(status = PlayStatus.Error, error = Errors.describe(error)) }
            }
        }
    }

    init {
        engine.player.addListener(playerListener)
        // A remembered group that no longer exists (deleted list, provider changed its groups)
        // would show an empty list; fall back to All channels instead.
        viewModelScope.launch {
            genres.collect { list ->
                if (list.isEmpty()) return@collect
                val ids = list.mapTo(HashSet()) { it.id }
                if (_selectedGenre.value !in ids) _selectedGenre.value = ChannelRepository.ALL
                if (zapGenre.value !in ids) zapGenre.value = ChannelRepository.ALL
            }
        }
        if (store.profile != null) {
            engine.setUserAgent(container.source().userAgent)
            viewModelScope.launch { startup() }
        }
    }

    private suspend fun startup() {
        if (!repo.hasChannels()) {
            _overlay.value = Overlay.ChannelList
            syncChannels()
        }
        val last = store.lastChannelId?.let { repo.channel(it) }
        if (last != null) {
            play(last, store.lastGenreId ?: ChannelRepository.ALL)
        } else {
            _overlay.value = Overlay.ChannelList
        }
        delay(5_000) // let the first channel start before loading the portal again
        if (System.currentTimeMillis() - store.lastSyncAt > SYNC_MAX_AGE_MS) {
            syncChannels() // also refreshes the guide
        } else if (System.currentTimeMillis() - store.lastEpgSyncAt > EPG_MAX_AGE_MS) {
            syncEpg()
        }
    }

    // --- Lifecycle -------------------------------------------------------------------------------

    fun onForeground() {
        if (_screen.value == Screen.Setup) return
        startWatchdog() // the portal session must stay alive while browsing videos or radio too
        if (_screen.value != Screen.Tv) return
        val channel = _playback.value.channel
        if (resumeOnForeground && channel != null) {
            resumeOnForeground = false
            zapper.request(channel)
        }
    }

    /** Amazon requires releasing decoders when not visible; it also frees the provider connection. */
    fun onBackground() {
        watchdogJob?.cancel()
        retryJob?.cancel()
        zapper.cancel() // also covers a tune still waiting on the portal
        val playback = _playback.value
        if (playback.channel != null && playback.status != PlayStatus.Stopped) resumeOnForeground = true
        engine.stop()
    }

    // --- Remote ----------------------------------------------------------------------------------

    /** True when video fills the screen and the remote controls channels rather than focus. */
    val isFullscreen: Boolean
        get() = _screen.value == Screen.Tv &&
            (_overlay.value == Overlay.None || _overlay.value == Overlay.Banner) &&
            _playback.value.channel != null

    /** Read on every key press, so a change in Settings applies at once. */
    val invertUpDown: Boolean get() = store.invertUpDown

    val isGuideOpen: Boolean get() = _screen.value == Screen.Tv && _overlay.value == Overlay.Guide

    fun onRemote(action: RemoteAction) {
        when (action) {
            RemoteAction.ChannelUp -> zap(+1)
            RemoteAction.ChannelDown -> zap(-1)
            RemoteAction.OpenList -> showChannelList()
            RemoteAction.OpenGuide -> openGuide()
            RemoteAction.Tracks -> container.trackPicker.open()
            RemoteAction.ShowInfo -> showBanner()
            RemoteAction.Recall -> previousChannel?.let { play(it, zapGenre.value) }
            RemoteAction.ToggleFavorite -> _playback.value.channel?.let { toggleFavorite(it.id) }
            RemoteAction.PlayPause -> togglePlayPause()
            RemoteAction.Back -> Unit // handled by onBackInFullscreen
        }
    }

    /** Returns true when the app should exit (Back pressed twice). */
    fun onBackInFullscreen(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < EXIT_WINDOW_MS) return true
        lastBackAt = now
        showToast(R.string.press_back_to_exit)
        return false
    }

    fun hideChannelList() {
        if (_playback.value.channel != null) _overlay.value = Overlay.None
    }

    /** Opens the guide on the group last viewed in it (e.g. a favourites list), else the watched group. */
    fun openGuide() {
        bannerJob?.cancel()
        val remembered = store.guideGroupId?.takeIf { id -> genres.value.any { it.id == id } }
        _selectedGenre.value = remembered ?: zapGenre.value
        _overlay.value = Overlay.Guide
    }

    /** ⏪ ⏩ in the guide: previous / next group, wrapping round; remembered for next time. */
    fun stepGuideGroup(delta: Int) {
        val list = genres.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _selectedGenre.value }
        val next = list[((if (index < 0) 0 else index) + delta).mod(list.size)]
        _selectedGenre.value = next.id
        store.guideGroupId = next.id
    }

    /** Back from the guide: to the video if something is playing, otherwise to the channel list. */
    fun closeGuide() {
        _overlay.value = if (_playback.value.channel != null) Overlay.None else Overlay.ChannelList
    }

    fun showChannelList() {
        bannerJob?.cancel()
        _selectedGenre.value = zapGenre.value
        _overlay.value = Overlay.ChannelList
    }

    private fun showBanner() {
        if (_playback.value.channel == null) return
        _overlay.value = Overlay.Banner
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(BANNER_MS)
            if (_overlay.value == Overlay.Banner) _overlay.value = Overlay.None
        }
    }

    // --- Browsing actions ------------------------------------------------------------------------

    /** Called as focus moves over groups; debounced so fast scrolling doesn't hit the database. */
    fun selectGenre(genreId: String) {
        genreFocusJob?.cancel()
        _selectedGenre.value = genreId
    }

    fun onGenreFocused(genreId: String) {
        genreFocusJob?.cancel()
        genreFocusJob = viewModelScope.launch {
            delay(GENRE_FOCUS_DEBOUNCE_MS)
            _selectedGenre.value = genreId
        }
    }

    fun play(channel: ChannelRow, fromGenre: String) {
        zapGenre.value = fromGenre
        store.lastGenreId = fromGenre
        showChannel(channel)
        zapper.request(channel)
    }

    /** ☰ on a channel: choose which favourites lists it belongs to. */
    fun toggleFavorite(channelId: String) {
        viewModelScope.launch {
            val channel = repo.channel(channelId) ?: return@launch
            repo.defaultListId(app.getString(R.string.genre_favorites)) // a fresh install starts with one list
            _listPicker.value = ListPickerUi(channel, repo.favoriteLists().first(), repo.listsContaining(channelId))
        }
    }

    fun toggleInList(listId: Long) {
        val picker = _listPicker.value ?: return
        viewModelScope.launch {
            val added = repo.toggleInList(listId, picker.channel.id)
            val memberOf = if (added) picker.memberOf + listId else picker.memberOf - listId
            _listPicker.value = picker.copy(memberOf = memberOf)
            refreshFavoriteMark(picker.channel.id, memberOf.isNotEmpty())
        }
    }

    /** "New list…" from the picker: creates it and puts the channel in it straight away. */
    fun createListWith(name: String) {
        val picker = _listPicker.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repo.createList(name)
            repo.toggleInList(id, picker.channel.id)
            _listPicker.value = picker.copy(lists = repo.favoriteLists().first(), memberOf = picker.memberOf + id)
            refreshFavoriteMark(picker.channel.id, true)
        }
    }

    fun closeListPicker() {
        _listPicker.value = null
    }

    /** ☰ on a list in the group column: rename or delete it. */
    fun editList(listId: Long) {
        val list = genres.value.firstOrNull { it.listId == listId } ?: return
        _listEdit.value = ListEditUi(listId, list.title.removePrefix("★  "))
    }

    /** Rename/delete from Settings → Favourites. */
    fun editList(listId: Long, name: String) {
        _listEdit.value = ListEditUi(listId, name)
    }

    fun renameList(name: String) {
        val edit = _listEdit.value ?: return
        _listEdit.value = null
        if (name.isBlank()) return
        viewModelScope.launch { repo.renameList(edit.listId, name) }
    }

    fun deleteList() {
        val edit = _listEdit.value ?: return
        _listEdit.value = null
        viewModelScope.launch {
            repo.deleteList(edit.listId)
            val group = ChannelRepository.listGroupId(edit.listId)
            if (_selectedGenre.value == group) _selectedGenre.value = ChannelRepository.ALL
            if (zapGenre.value == group) zapGenre.value = ChannelRepository.ALL
            if (store.lastGenreId == group) store.lastGenreId = ChannelRepository.ALL
            _playback.value.channel?.let { refreshFavoriteMark(it.id, repo.listsContaining(it.id).isNotEmpty()) }
        }
    }

    fun closeListEdit() {
        _listEdit.value = null
    }

    private fun refreshFavoriteMark(channelId: String, favourite: Boolean) {
        _playback.update { p ->
            if (p.channel?.id == channelId) p.copy(channel = p.channel.copy(isFavorite = favourite)) else p
        }
    }

    fun refreshChannels() {
        viewModelScope.launch { syncChannels() }
    }

    private suspend fun syncChannels() {
        if (_sync.value == SyncUi.Running) return
        _sync.value = SyncUi.Running
        _sync.value = try {
            repo.sync()
            SyncUi.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncUi.Failed(Errors.describe(e))
        }
        if (_sync.value == SyncUi.Idle) syncEpg()
    }

    /** Refreshes the guide in the background; failures keep the previous guide. */
    private fun syncEpg() {
        if (epgJob?.isActive == true) return
        epgJob = viewModelScope.launch {
            try {
                if (repo.syncEpg() > 0) _epgRevision.update { it + 1 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("HindsEpg", "Guide update failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun zap(delta: Int) {
        val list = zapList.value
        if (list.isEmpty()) return
        val currentId = _playback.value.channel?.id
        val index = list.indexOfFirst { it.id == currentId }
        val next = if (index < 0) list.first() else list[(index + delta).mod(list.size)]
        showChannel(next)
        zapper.request(next)
    }

    private fun showChannel(channel: ChannelRow) {
        retryJob?.cancel()
        tuneStartedAt = SystemClock.uptimeMillis()
        _tuneMs.value = null
        val current = _playback.value.channel
        if (current != null && current.id != channel.id) previousChannel = current
        _playback.value = PlaybackUi(channel = channel, status = PlayStatus.Tuning)
        showBanner()
    }

    private suspend fun tune(channel: ChannelRow, isRetry: Boolean) {
        if (!isRetry) autoRetries = 0
        engine.stop() // close the previous stream first: providers allow one connection per account
        try {
            val url = repo.streamUrl(channel.id)
            store.lastChannelId = channel.id
            engine.play(url, owner = this)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _playback.update { it.copy(status = PlayStatus.Error, error = Errors.describe(e)) }
        }
    }

    private fun togglePlayPause() {
        val playback = _playback.value
        val channel = playback.channel ?: return
        if (playback.status == PlayStatus.Stopped || playback.status == PlayStatus.Error) {
            showChannel(channel)
            zapper.request(channel)
        } else {
            // Live TV: "pause" releases the stream rather than holding the provider connection open.
            retryJob?.cancel()
            zapper.cancel()
            engine.stop()
            setStatus(PlayStatus.Stopped)
            showBanner()
        }
    }

    private fun setStatus(status: PlayStatus) {
        _playback.update { it.copy(status = status, error = if (status == PlayStatus.Error) it.error else null) }
    }

    private fun showToast(@StringRes message: Int) {
        _toast.value = app.getString(message)
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(TOAST_MS)
            _toast.value = null
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        if (store.profile == null) return
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                val client = runCatching { container.stalkerOrNull() }.getOrNull() ?: return@launch
                delay((client.session?.timeslotSeconds ?: DEFAULT_TIMESLOT_S) * 1_000L)
                try {
                    client.watchdog()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Non-fatal: the next portal call re-authenticates if the session lapsed.
                }
            }
        }
    }

    // --- Setup -----------------------------------------------------------------------------------

    /** Leaves live TV for videos or radio: the one provider connection is theirs now. */
    fun openSection(screen: Screen) {
        if (_screen.value == screen) return
        if (_screen.value == Screen.Tv) releaseLive()
        _screen.value = screen
    }

    /** Back to live TV, resuming the channel that was playing. */
    fun openLive() {
        if (_screen.value == Screen.Tv) return
        _screen.value = Screen.Tv
        _overlay.value = if (_playback.value.channel != null) Overlay.None else Overlay.ChannelList
        onForeground()
    }

    /** A channel picked from search: straight to full screen on it. */
    fun watchFromSearch(channel: ChannelRow) {
        resumeOnForeground = false
        _screen.value = Screen.Tv
        _overlay.value = Overlay.None
        onForeground()
        play(channel, ChannelRepository.ALL)
    }

    private fun releaseLive() {
        retryJob?.cancel()
        bannerJob?.cancel()
        zapper.cancel()
        if (engine.owner === this) engine.stop()
        val playback = _playback.value
        if (playback.channel != null && playback.status != PlayStatus.Stopped) resumeOnForeground = true
    }

    /** Account details form ("Change account" in Settings); Cancel returns to where it was opened. */
    fun openSetup() {
        val profile = store.profile
        setupReturn = if (_screen.value == Screen.Settings) Screen.Settings else Screen.Tv
        if (_screen.value == Screen.Tv) releaseLive() else engine.stop()
        watchdogJob?.cancel()
        _setup.value = SetupUi(
            form = when (profile) {
                is SourceProfile.Stalker -> profile.portal.let {
                    SetupForm(
                        mode = SetupMode.Portal, portalUrl = it.portalUrl, mac = it.mac, stbType = it.stbType,
                        serialNumber = it.serialNumber, deviceId = it.deviceId, deviceId2 = it.deviceId2,
                        signature = it.signature,
                    )
                }
                is SourceProfile.Playlist -> SetupForm(mode = SetupMode.Playlist, playlistUrl = profile.url, epgUrl = profile.epgUrl)
                null -> SetupForm()
            },
            canCancel = profile != null,
        )
        _screen.value = Screen.Setup
    }

    fun cancelSetup() {
        if (store.profile == null) return
        _screen.value = setupReturn
        if (setupReturn == Screen.Tv) onForeground()
    }

    private var setupReturn = Screen.Tv

    fun updateForm(transform: (SetupForm) -> SetupForm) {
        _setup.update { it.copy(form = transform(it.form), error = null) }
    }

    /** Creates a new random MAC for the user to register with their provider. */
    fun generateMac() {
        val mac = Mac.generate()
        _setup.update { it.copy(form = it.form.copy(mac = mac), generatedMac = mac, error = null) }
    }

    fun connect() {
        val form = _setup.value.form
        val profile = when (form.mode) {
            SetupMode.Portal -> {
                val mac = Mac.normalize(form.mac)
                if (mac == null) {
                    _setup.update { it.copy(error = app.getString(R.string.error_invalid_mac)) }
                    return
                }
                val url = form.portalUrl.trim()
                if (url.isEmpty() || runCatching { StalkerClient.candidateEndpoints(url) }.isFailure) {
                    _setup.update { it.copy(error = app.getString(R.string.error_invalid_url)) }
                    return
                }
                SourceProfile.Stalker(
                    PortalProfile(
                        portalUrl = url,
                        mac = mac,
                        stbType = form.stbType,
                        serialNumber = form.serialNumber.trim(),
                        deviceId = form.deviceId.trim(),
                        deviceId2 = form.deviceId2.trim(),
                        signature = form.signature.trim(),
                    ),
                )
            }
            SetupMode.Playlist -> {
                val url = form.playlistUrl.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    _setup.update { it.copy(error = app.getString(R.string.error_invalid_playlist)) }
                    return
                }
                SourceProfile.Playlist(url, form.epgUrl.trim())
            }
        }
        _setup.update { it.copy(busy = true, error = null, progress = app.getString(R.string.connecting)) }
        viewModelScope.launch {
            try {
                val source = container.newSource(profile)
                if (source is com.hindstv.data.source.StalkerSource) {
                    source.client.connect()
                    _setup.update { it.copy(progress = app.getString(R.string.loading_channels)) }
                }
                val profileChanged = store.profile != profile
                store.profile = profile
                container.useSource(profile, source)
                engine.setUserAgent(source.userAgent)
                val count = repo.sync()
                if (profileChanged) {
                    container.media.clearProgress()
                    _profileVersion.update { it + 1 }
                    store.lastChannelId = null
                    zapGenre.value = ChannelRepository.ALL
                    resumeOnForeground = false
                    _playback.value = PlaybackUi()
                }
                _setup.value = SetupUi()
                _selectedGenre.value = ChannelRepository.ALL
                _overlay.value = Overlay.ChannelList
                _screen.value = Screen.Tv
                syncEpg()
                if (count > 0) onForeground()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _setup.update { it.copy(busy = false, progress = null, error = Errors.describe(e)) }
            }
        }
    }

    override fun onCleared() {
        engine.player.removeListener(playerListener)
    }

    companion object {
        private const val MAX_AUTO_RETRIES = 1
        private const val BANNER_MS = 4_000L
        private const val TOAST_MS = 2_500L
        private const val EXIT_WINDOW_MS = 2_500L
        private const val GENRE_FOCUS_DEBOUNCE_MS = 150L
        private const val DEFAULT_TIMESLOT_S = 90
        private const val SYNC_MAX_AGE_MS = 12 * 60 * 60 * 1_000L
        private const val EPG_MAX_AGE_MS = 4 * 60 * 60 * 1_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HindsApp
                AppViewModel(app)
            }
        }
    }
}
