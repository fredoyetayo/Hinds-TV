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

enum class SettingsSection { Appearance, Account, Favourites, Playback, History, Diagnostics, About }

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
