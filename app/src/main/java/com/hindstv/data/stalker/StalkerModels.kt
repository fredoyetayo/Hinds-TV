package com.hindstv.data.stalker

import java.io.IOException

data class StalkerSession(
    val endpoint: String,
    val timeslotSeconds: Int,
    val watchdogTimeoutSeconds: Int,
)

data class StalkerGenre(
    val id: String,
    val title: String,
    val censored: Boolean,
)

data class StalkerChannel(
    val id: String,
    val name: String,
    val number: Int,
    val cmd: String,
    val logo: String?,
    val genreId: String,
    val useTmpLink: Boolean,
    val hasArchive: Boolean,
    val archiveHours: Int,
    val xmltvId: String?,
    val censored: Boolean,
)

/** One guide entry; times are epoch seconds. */
data class StalkerProgramme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/** Failures the UI can explain to the user. All extend IOException so callers can catch broadly. */
sealed class StalkerException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** Nothing at the URL behaves like a portal (HTML page, 404, wrong path). */
    class NotAPortal(detail: String) : StalkerException(detail)

    /** Token missing/expired: "Authorization failed.", `{"js":false}` or HTTP 401. */
    class AuthFailed(detail: String = "Authorization failed") : StalkerException(detail)

    /** The portal answered but refused this device (unregistered MAC, expired account, device conflict). */
    class Blocked(val portalMessage: String?) : StalkerException(portalMessage ?: "The portal rejected this device")

    /** HTTP 403/429/458/509: too many requests or too many connections. Back off; not a logout. */
    class Throttled(val code: Int) : StalkerException("Portal is limiting requests (HTTP $code)")

    /** The portal refused to give a stream (e.g. `nothing_to_play`, connection limit). */
    class StreamUnavailable(val reason: String) : StalkerException("Stream unavailable: $reason")

    class ServerError(val code: Int) : StalkerException("Portal server error (HTTP $code)")

    class BadResponse(detail: String) : StalkerException(detail)
}
