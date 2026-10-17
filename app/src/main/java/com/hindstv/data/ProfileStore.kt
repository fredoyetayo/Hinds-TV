package com.hindstv.data

import android.content.Context
import androidx.core.content.edit
import com.hindstv.data.stalker.PortalProfile

/** Small key-value settings. Portal credentials stay on the device and are never logged. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("hinds", Context.MODE_PRIVATE)

    var profile: PortalProfile?
        get() {
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
        set(value) = prefs.edit {
            if (value == null) {
                listOf(KEY_URL, KEY_MAC, KEY_STB, KEY_SN, KEY_DEVICE_ID, KEY_DEVICE_ID2, KEY_SIGNATURE, KEY_TZ, KEY_ENDPOINT)
                    .forEach(::remove)
            } else {
                putString(KEY_URL, value.portalUrl)
                putString(KEY_MAC, value.mac)
                putString(KEY_STB, value.stbType)
                putString(KEY_SN, value.serialNumber)
                putString(KEY_DEVICE_ID, value.deviceId)
                putString(KEY_DEVICE_ID2, value.deviceId2)
                putString(KEY_SIGNATURE, value.signature)
                putString(KEY_TZ, value.timezone)
            }
        }

    /** The API endpoint found by discovery; reusing it skips probing on every launch. */
    var endpoint: String?
        get() = prefs.getString(KEY_ENDPOINT, null)
        set(value) = prefs.edit { putString(KEY_ENDPOINT, value) }

    var lastChannelId: String?
        get() = prefs.getString(KEY_LAST_CHANNEL, null)
        set(value) = prefs.edit { putString(KEY_LAST_CHANNEL, value) }

    /** The group the last channel was picked from — zapping stays inside it. */
    var lastGenreId: String?
        get() = prefs.getString(KEY_LAST_GENRE, null)
        set(value) = prefs.edit { putString(KEY_LAST_GENRE, value) }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

    private companion object {
        const val KEY_URL = "portal_url"
        const val KEY_MAC = "mac"
        const val KEY_STB = "stb_type"
        const val KEY_SN = "serial"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_ID2 = "device_id2"
        const val KEY_SIGNATURE = "signature"
        const val KEY_TZ = "timezone"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_LAST_GENRE = "last_genre"
        const val KEY_LAST_SYNC = "last_sync"
    }
}
