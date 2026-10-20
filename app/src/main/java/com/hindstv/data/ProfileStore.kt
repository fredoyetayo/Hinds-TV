package com.hindstv.data

import android.content.Context
import androidx.core.content.edit
import com.hindstv.data.source.SourceProfile
import com.hindstv.data.stalker.PortalProfile

/** Small key-value settings. Portal credentials stay on the device and are never logged. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("hinds", Context.MODE_PRIVATE)

    var profile: SourceProfile?
        get() = when (prefs.getString(KEY_TYPE, TYPE_STALKER)) {
            TYPE_PLAYLIST -> prefs.getString(KEY_PLAYLIST_URL, null)?.let {
                SourceProfile.Playlist(it, prefs.getString(KEY_EPG_URL, null).orEmpty())
            }
            else -> stalkerProfile()?.let(SourceProfile::Stalker)
        }
        set(value) = prefs.edit {
            ALL_PROFILE_KEYS.forEach(::remove)
            when (value) {
                null -> Unit
                is SourceProfile.Playlist -> {
                    putString(KEY_TYPE, TYPE_PLAYLIST)
                    putString(KEY_PLAYLIST_URL, value.url)
                    putString(KEY_EPG_URL, value.epgUrl)
                }
                is SourceProfile.Stalker -> {
                    val p = value.portal
                    putString(KEY_TYPE, TYPE_STALKER)
                    putString(KEY_URL, p.portalUrl)
                    putString(KEY_MAC, p.mac)
                    putString(KEY_STB, p.stbType)
                    putString(KEY_SN, p.serialNumber)
                    putString(KEY_DEVICE_ID, p.deviceId)
                    putString(KEY_DEVICE_ID2, p.deviceId2)
                    putString(KEY_SIGNATURE, p.signature)
                    putString(KEY_TZ, p.timezone)
                }
            }
        }

    private fun stalkerProfile(): PortalProfile? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val mac = prefs.getString(KEY_MAC, null) ?: return null
        return PortalProfile(
            portalUrl = url,
            mac = mac,
            stbType = prefs.getString(KEY_STB, null) ?: PortalProfile.DEFAULT_STB_TYPE,
            serialNumber = prefs.getString(KEY_SN, null).orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE_ID, null).orEmpty(),
            deviceId2 = prefs.getString(KEY_DEVICE_ID2, null).orEmpty(),
            signature = prefs.getString(KEY_SIGNATURE, null).orEmpty(),
            timezone = prefs.getString(KEY_TZ, null) ?: PortalProfile.DEFAULT_TIMEZONE,
        )
    }

    /** Most recent searches, newest first (shown as shortcuts on the search screen). */
    var recentSearches: List<String>
        get() = prefs.getString(KEY_RECENT_SEARCHES, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit { putString(KEY_RECENT_SEARCHES, value.joinToString("\n")) }

    /**
     * The portal session token from the last run. Reusing it lets the first channel start without
     * a handshake; an expired token is renewed automatically. Stays on the device, never logged.
     */
    var portalToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    /** The API endpoint found by discovery; reusing it skips probing on every launch. */
    var endpoint: String?
        get() = prefs.getString(KEY_ENDPOINT, null)
        set(value) = prefs.edit { putString(KEY_ENDPOINT, value) }

    /** XMLTV URL advertised by the playlist header on the last sync. */
    var discoveredEpgUrl: String?
        get() = prefs.getString(KEY_DISCOVERED_EPG, null)
        set(value) = prefs.edit { putString(KEY_DISCOVERED_EPG, value) }

    var lastChannelId: String?
        get() = prefs.getString(KEY_LAST_CHANNEL, null)
        set(value) = prefs.edit { putString(KEY_LAST_CHANNEL, value) }

    /** Channels tuned to, newest first (Home's "Recently watched"). Ids only; at most [RECENT_MAX]. */
    var recentChannelIds: List<String>
        get() = prefs.getString(KEY_RECENT_CHANNELS, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit { putString(KEY_RECENT_CHANNELS, value.joinToString("\n")) }

    /** Open on Home instead of playing the last channel (Settings → Appearance → Start Hinds TV with). */
    var startWithHome: Boolean
        get() = prefs.getString(KEY_START_WITH, START_LAST) == START_HOME
        set(value) = prefs.edit { putString(KEY_START_WITH, if (value) START_HOME else START_LAST) }

    /** The group the last channel was picked from — zapping stays inside it. */
    var lastGenreId: String?
        // Before named lists (v0.3) the single favourites group was "__favorites"; the database
        // upgrade made it list 1, so point the remembered group there instead of at nothing.
        get() = prefs.getString(KEY_LAST_GENRE, null)?.let { if (it == LEGACY_FAVORITES) "__list:1" else it }
        set(value) = prefs.edit { putString(KEY_LAST_GENRE, value) }

    /** ▲ goes to the previous channel instead of the next (list order). */
    var invertUpDown: Boolean
        get() = prefs.getBoolean(KEY_INVERT, false)
        set(value) = prefs.edit { putBoolean(KEY_INVERT, value) }

    /** The group the guide last showed (e.g. a favourites list), so it opens there next time. */
    var guideGroupId: String?
        get() = prefs.getString(KEY_GUIDE_GROUP, null)
        set(value) = prefs.edit { putString(KEY_GUIDE_GROUP, value) }

    /** Settings → Appearance → Theme, by `ThemeMode` name ("Dark", "Light", "Auto"). */
    var themeMode: String?
        get() = prefs.getString(KEY_THEME_MODE, null)
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value) }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

    var lastEpgSyncAt: Long
        get() = prefs.getLong(KEY_LAST_EPG_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_EPG_SYNC, value) }

    private companion object {
        const val KEY_TYPE = "source_type"
        const val TYPE_STALKER = "stalker"
        const val TYPE_PLAYLIST = "playlist"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_URL = "portal_url"
        const val KEY_MAC = "mac"
        const val KEY_STB = "stb_type"
        const val KEY_SN = "serial"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_ID2 = "device_id2"
        const val KEY_SIGNATURE = "signature"
        const val KEY_TZ = "timezone"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "portal_token"
        const val KEY_DISCOVERED_EPG = "discovered_epg_url"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val KEY_GUIDE_GROUP = "guide_group"
        const val KEY_INVERT = "invert_up_down"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_RECENT_CHANNELS = "recent_channels"
        const val KEY_START_WITH = "start_with"
        const val START_LAST = "last"
        const val START_HOME = "home"
        const val LEGACY_FAVORITES = "__favorites"
        const val KEY_LAST_GENRE = "last_genre"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_LAST_EPG_SYNC = "last_epg_sync"
        val ALL_PROFILE_KEYS = listOf(
            KEY_TYPE, KEY_PLAYLIST_URL, KEY_EPG_URL, KEY_URL, KEY_MAC, KEY_STB, KEY_SN, KEY_DEVICE_ID,
            KEY_DEVICE_ID2, KEY_SIGNATURE, KEY_TZ, KEY_ENDPOINT, KEY_TOKEN, KEY_DISCOVERED_EPG,
        )
    }
}
