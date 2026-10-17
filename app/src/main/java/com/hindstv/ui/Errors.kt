package com.hindstv.ui

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.hindstv.data.source.PlaylistException
import com.hindstv.data.stalker.StalkerException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns failures into one plain-English sentence for the TV screen. */
object Errors {
    fun describe(e: Throwable): String = when (e) {
        is PlaylistException -> e.message ?: "The playlist couldn't be loaded."
        is StalkerException.Blocked -> "The portal says: ${e.portalMessage ?: "this device is not allowed."}"
        is StalkerException.NotAPortal -> "Couldn't find an IPTV portal at that address. Check the portal URL."
        is StalkerException.AuthFailed ->
            "The portal didn't accept this device. Check the MAC address matches the one registered with your provider."
        is StalkerException.Throttled ->
            "The provider is limiting connections (HTTP ${e.code}). If STB Emu or another device is using this " +
                "account, close it and try again in a minute."
        is StalkerException.StreamUnavailable -> "This channel isn't available right now (${e.reason})."
        is StalkerException.ServerError -> "The provider's server had a problem (HTTP ${e.code}). Try again shortly."
        is StalkerException.BadResponse -> "The portal sent a response Hinds TV couldn't read. (${e.message})"
        is UnknownHostException -> "Can't reach the portal. Check the address and your internet connection."
        is SocketTimeoutException -> "The portal took too long to respond. Try again."
        is SSLException -> "A secure connection to the portal failed. Try http:// instead of https://."
        is PlaybackException -> describePlayback(e)
        else -> e.message ?: e.javaClass.simpleName
    }

    @OptIn(UnstableApi::class)
    private fun describePlayback(e: PlaybackException): String {
        val http = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        return when {
            http != null && http.responseCode in setOf(403, 429, 458, 509) ->
                "The provider refused the stream (HTTP ${http.responseCode}). Another device may be using this " +
                    "account — close STB Emu and try again."
            http != null -> "The stream failed to load (HTTP ${http.responseCode})."
            e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This channel uses a format this device can't play."
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Lost connection to the stream. Check your internet connection."
            else -> "Playback failed (${e.errorCodeName})."
        }
    }
}
