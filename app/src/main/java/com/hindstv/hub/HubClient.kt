package com.hindstv.hub

import com.hindstv.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A followed team's match, as the hub reports it (ESPN data). */
data class HubMatch(
    val id: String,
    val title: String,
    val home: String,
    val away: String,
    val competition: String,
    val kickoff: Long,
    val kickoffLocal: String,
    val state: String,
    val detail: String,
    val homeScore: String? = null,
    val awayScore: String? = null,
) {
    /** "2–1" once it has started, else null. */
    val score: String? get() = if (homeScore != null && awayScore != null && state != "pre") "$homeScore–$awayScore" else null
}

data class HubRecording(
    val id: String,
    val title: String,
    val channel: String,
    val start: Long,
    val stop: Long,
    val recording: Boolean,
    val durationS: Long,
)

data class HubScheduled(val id: String, val title: String, val channel: String, val start: Long, val stop: Long)

data class HubStatus(val version: String, val portal: Boolean, val recording: HubRecording?, val schedule: List<HubScheduled>)

/** What the hub needs to start streams itself (scheduled recordings). Sent only when the owner allows it. */
data class HubPortal(
    val endpoint: String,
    val mac: String,
    val stbType: String,
    val serialNumber: String,
    val deviceId: String,
    val deviceId2: String,
    val signature: String,
    val timezone: String,
    val userAgent: String,
)

/**
 * Talks to the Hinds Hub add-on on Home Assistant (`hinds_hub/` in this repo) over the home
 * network: fixtures for match reminders, what's playing, recordings. Every call is short and
 * best-effort; the TV never waits on the hub to play anything.
 */
class HubClient(http: OkHttpClient, private val baseUrl: () -> String?) {
    private val http = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    val configured: Boolean get() = !baseUrl().isNullOrBlank()

    fun recordingUrl(id: String, fromEnd: Boolean = false): String? =
        baseUrl()?.trimEnd('/')?.let { "$it/recordings/$id.ts" + if (fromEnd) "?from=end" else "" }

    suspend fun status(): HubStatus {
        val o = get("/status") as JsonObject
        return HubStatus(
            version = o.str("version").orEmpty(),
            portal = (o["portal"] as? JsonPrimitive)?.booleanOrNull == true,
            recording = (o["recording"] as? JsonObject)?.let(::recording),
            schedule = (o["schedule"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::scheduled) },
        )
    }

    suspend fun fixtures(): List<HubMatch> =
        ((get("/fixtures") as JsonObject)["matches"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            HubMatch(
                id = o.str("id") ?: return@mapNotNull null,
                title = o.str("title").orEmpty(),
                home = o.str("home").orEmpty(),
                away = o.str("away").orEmpty(),
                competition = o.str("competition").orEmpty(),
                kickoff = o.long("kickoff") ?: return@mapNotNull null,
                kickoffLocal = o.str("kickoff_local").orEmpty(),
                state = o.str("state") ?: "pre",
                detail = o.str("detail").orEmpty(),
                homeScore = o.str("home_score"),
                awayScore = o.str("away_score"),
            )
        }

    suspend fun recordings(): List<HubRecording> =
        ((get("/recordings") as JsonObject)["recordings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::recording) }

    suspend fun nowPlaying(channel: String?, programme: String?, playing: Boolean) {
        post("/now-playing", buildJsonObject {
            put("state", if (playing) "playing" else "off")
            put("channel", channel)
            put("programme", programme)
        })
    }

    suspend fun sendPortal(p: HubPortal) {
        post("/portal", buildJsonObject {
            put("endpoint", p.endpoint)
            put("mac", p.mac)
            put("stbType", p.stbType)
            put("serialNumber", p.serialNumber)
            put("deviceId", p.deviceId)
            put("deviceId2", p.deviceId2)
            put("signature", p.signature)
            put("timezone", p.timezone)
            put("userAgent", p.userAgent)
        })
    }

    suspend fun forgetPortal() = send("DELETE", "/portal", null)

    /** Records now until [stop]. [url] is a fresh link from the TV, used when the hub has no portal details. */
    suspend fun record(cmd: String, channel: String, title: String, stop: Long, url: String?, userAgent: String): HubRecording {
        val o = post("/record", buildJsonObject {
            put("cmd", cmd)
            put("channel", channel)
            put("title", title)
            put("stop", stop)
            put("url", url)
            put("userAgent", userAgent)
        }) as JsonObject
        return recording(o["recording"] as? JsonObject ?: throw IOException("The hub didn't start recording"))
    }

    suspend fun schedule(cmd: String, channel: String, title: String, start: Long, stop: Long) {
        post("/schedule", buildJsonObject {
            put("cmd", cmd)
            put("channel", channel)
            put("title", title)
            put("start", start)
            put("stop", stop)
        })
    }

    suspend fun cancelScheduled(id: String) = send("DELETE", "/schedule/$id", null)

    suspend fun stopRecording() = post("/record/stop", buildJsonObject { })

    private fun recording(o: JsonObject) = HubRecording(
        id = o.str("id").orEmpty(),
        title = o.str("title").orEmpty(),
        channel = o.str("channel").orEmpty(),
        start = o.long("start") ?: 0,
        stop = o.long("stop") ?: 0,
        recording = (o["recording"] as? JsonPrimitive)?.booleanOrNull == true,
        durationS = o.long("duration_s") ?: 0,
    )

    private fun scheduled(o: JsonObject) = HubScheduled(
        id = o.str("id").orEmpty(),
        title = o.str("title").orEmpty(),
        channel = o.str("channel").orEmpty(),
        start = o.long("start") ?: 0,
        stop = o.long("stop") ?: 0,
    )

    private suspend fun get(path: String): JsonElement = send("GET", path, null)

    private suspend fun post(path: String, body: JsonObject): JsonElement = send("POST", path, body)

    private suspend fun send(method: String, path: String, body: JsonObject?): JsonElement {
        val base = baseUrl()?.trimEnd('/') ?: throw IOException("Home Assistant isn't set up")
        val request = Request.Builder()
            .url(base + path)
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).await().use { response ->
                val text = response.body.string()
                val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: JsonObject(emptyMap())
                if (!response.isSuccessful) {
                    val error = ((parsed as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
                    throw IOException(error ?: "Home Assistant answered HTTP ${response.code}")
                }
                parsed
            }
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
