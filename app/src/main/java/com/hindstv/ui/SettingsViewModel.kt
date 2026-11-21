package com.hindstv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hindstv.HindsApp
import com.hindstv.core.Diagnostics
import com.hindstv.data.db.ListSummary
import com.hindstv.data.source.SourceProfile
import com.hindstv.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SettingsSection { Appearance, Account, Favourites, Playback, HomeAssistant, History, Diagnostics, About }

/** Settings → Home Assistant: the hub's address, whether it answers, recordings. */
data class HubUi(
    val url: String,
    val checking: Boolean = false,
    val status: com.hindstv.hub.HubStatus? = null,
    val error: String? = null,
    val message: String? = null,
)

/** The account as shown in Settings. Identifiers are masked: the screen may end up in a photo. */
data class AccountUi(
    val kind: SourceKind,
    val address: String,
    val maskedMac: String?,
    val channels: Int = 0,
    val lastSyncAt: Long = 0,
    val expiry: String? = null,
    val plan: String? = null,
    val expiryLoading: Boolean = false,
    val expiryError: String? = null,
)

enum class SourceKind { Portal, Playlist }

/** Settings: appearance, account, favourites lists, playback options, history, diagnostics and about. */
class SettingsViewModel(app: HindsApp) : ViewModel() {
    private val container = app.container
    private val store = container.profileStore
    private val channels = container.channels

    private val _section = MutableStateFlow(SettingsSection.Appearance)
    val section: StateFlow<SettingsSection> = _section.asStateFlow()

    private val _account = MutableStateFlow<AccountUi?>(null)
    val account: StateFlow<AccountUi?> = _account.asStateFlow()

    val lists: StateFlow<List<ListSummary>> = channels.listSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _invertUpDown = MutableStateFlow(store.invertUpDown)
    val invertUpDown: StateFlow<Boolean> = _invertUpDown.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = container.themeMode.asStateFlow()

    val update: StateFlow<com.hindstv.update.UpdateUi> = container.updater.state

    fun checkForUpdate() = container.updater.check()

    fun installUpdate() {
        container.updater.state.value.let { it as? com.hindstv.update.UpdateUi.Available }?.let { container.updater.install(it.release) }
            ?: container.updater.check()
    }

    private val _startWithHome = MutableStateFlow(store.startWithHome)
    val startWithHome: StateFlow<Boolean> = _startWithHome.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics.recent())
    val diagnostics: StateFlow<List<Diagnostics.Entry>> = _diagnostics.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onOpen() {
        _message.value = null
        refreshDiagnostics()
        loadAccount()
    }

    fun select(section: SettingsSection) {
        _section.value = section
        _message.value = null
        if (section == SettingsSection.Diagnostics) refreshDiagnostics()
    }

    private fun loadAccount() {
        val profile = store.profile ?: return
        val base = when (profile) {
            is SourceProfile.Stalker -> AccountUi(
                kind = SourceKind.Portal,
                address = hostOf(profile.portal.portalUrl),
                maskedMac = maskMac(profile.portal.mac),
            )
            is SourceProfile.Playlist -> AccountUi(kind = SourceKind.Playlist, address = hostOf(profile.url), maskedMac = null)
        }
        _account.value = base.copy(lastSyncAt = store.lastSyncAt, expiryLoading = profile is SourceProfile.Stalker)
        viewModelScope.launch {
            val count = channels.channelCount()
            _account.value = _account.value?.copy(channels = count)
            if (profile !is SourceProfile.Stalker) return@launch
            try {
                val info = container.source().account()
                _account.value = _account.value?.copy(expiry = info?.expiry, plan = info?.plan, expiryLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _account.value = _account.value?.copy(expiryLoading = false, expiryError = Errors.describe(e))
            }
        }
    }

    fun moveList(listId: Long, delta: Int) {
        viewModelScope.launch { channels.moveList(listId, delta) }
    }

    private val _liveRewind = MutableStateFlow(store.liveRewind)
    val liveRewind: StateFlow<Boolean> = _liveRewind.asStateFlow()

    private val _backupFeeds = MutableStateFlow(store.backupFeeds)
    val backupFeeds: StateFlow<Boolean> = _backupFeeds.asStateFlow()

    private val _matchFrameRate = MutableStateFlow(store.matchFrameRate)
    val matchFrameRate: StateFlow<Boolean> = _matchFrameRate.asStateFlow()

    fun setLiveRewind(on: Boolean) {
        store.liveRewind = on // applies from the next channel
        _liveRewind.value = on
    }

    fun setBackupFeeds(on: Boolean) {
        store.backupFeeds = on
        _backupFeeds.value = on
    }

    fun setMatchFrameRate(on: Boolean) {
        store.matchFrameRate = on
        _matchFrameRate.value = on
    }

    /** Groups hidden from the channel list, with their names (ids a re-sync dropped are left out). */
    val hiddenGroups: StateFlow<List<Pair<String, String>>> = kotlinx.coroutines.flow.combine(container.hiddenGroups, channels.genres()) { hidden, genres ->
        genres.filter { it.id in hidden }.map { it.id to it.title }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun showGroup(groupId: String) {
        val updated = container.hiddenGroups.value - groupId
        store.hiddenGroupIds = updated
        container.hiddenGroups.value = updated
    }

    private val _hub = MutableStateFlow(HubUi(store.hubUrl.orEmpty()))
    val hub: StateFlow<HubUi> = _hub.asStateFlow()

    fun setHubUrl(url: String) {
        val clean = url.trim().let { if (it.isNotEmpty() && "://" !in it) "http://$it" else it }
            .let { if (it.isNotEmpty() && Regex(""":\d+/?$""").find(it.substringAfter("://")) == null) it.trimEnd('/') + ":8642" else it }
        store.hubUrl = clean
        _hub.value = HubUi(clean)
        checkHub()
    }

    fun checkHub() {
        if (!container.hub.configured) return
        _hub.value = _hub.value.copy(checking = true, error = null)
        viewModelScope.launch {
            _hub.value = try {
                _hub.value.copy(checking = false, status = container.hub.status(), error = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _hub.value.copy(checking = false, status = null, error = Errors.describe(e))
            }
        }
    }

    /**
     * The owner chose to let Home Assistant start recordings itself: send it the portal details
     * (only to the hub address shown on screen, on the home network).
     */
    fun allowHubRecording(done: String) {
        val portal = (store.profile as? com.hindstv.data.source.SourceProfile.Stalker)?.portal ?: return
        val endpoint = store.endpoint ?: return
        viewModelScope.launch {
            try {
                container.hub.sendPortal(
                    com.hindstv.hub.HubPortal(
                        endpoint, portal.mac, portal.stbType, portal.serialNumber, portal.deviceId, portal.deviceId2,
                        portal.signature, portal.timezone, portal.userAgent,
                    ),
                )
                _hub.value = _hub.value.copy(message = done)
                checkHub()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _hub.value = _hub.value.copy(error = Errors.describe(e))
            }
        }
    }

    fun forgetHubPortal() = hubAction { container.hub.forgetPortal() }

    fun cancelScheduled(id: String) = hubAction { container.hub.cancelScheduled(id) }

    fun stopHubRecording() = hubAction { container.hub.stopRecording() }

    private fun hubAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _hub.value = _hub.value.copy(error = Errors.describe(e))
            }
            checkHub()
        }
    }

    fun setInvertUpDown(invert: Boolean) {
        store.invertUpDown = invert
        _invertUpDown.value = invert
    }

    fun setThemeMode(mode: ThemeMode) {
        store.themeMode = mode.name
        container.themeMode.value = mode
    }

    val wallpapers: StateFlow<Pair<Int, Int>> = container.wallpapers.asStateFlow()

    fun setWallpaper(light: Boolean, index: Int) {
        store.setWallpaper(light, index)
        container.wallpapers.value = container.wallpapers.value.let { if (light) it.copy(second = index) else it.copy(first = index) }
    }

    fun setStartWithHome(home: Boolean) {
        store.startWithHome = home
        _startWithHome.value = home
    }

    fun clearResumeHistory(done: String) {
        viewModelScope.launch {
            container.media.clearProgress()
            _message.value = done
        }
    }

    fun clearRecentSearches(done: String) {
        store.recentSearches = emptyList()
        _message.value = done
    }

    fun refreshDiagnostics() {
        _diagnostics.value = Diagnostics.recent()
    }

    fun clearDiagnostics() {
        Diagnostics.clear()
        refreshDiagnostics()
    }

    companion object {
        /** "00:1A:79:••:••:EF": enough to recognise it, not enough to copy it. */
        fun maskMac(mac: String): String {
            val parts = mac.split(':')
            if (parts.size != 6) return "••:••:••:••:••:••"
            return (parts.take(3) + listOf("••", "••") + parts.last()).joinToString(":")
        }

        /** Just the host: the full portal address and any tokens in it stay off the screen. */
        fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').ifEmpty { url.take(24) }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as HindsApp) }
        }
    }
}
