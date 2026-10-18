package com.hindstv.data.stalker

import com.hindstv.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Native client for Stalker/Ministra middleware portals (the "MAC portal" API that MAG boxes and
 * STB Emu use). Talks to the portal's JSON API directly instead of rendering its web UI.
 *
 * Thread-safe. One instance per [PortalProfile]; the session token lives in memory and is renewed
 * transparently (at most once per [MIN_REAUTH_INTERVAL_MS]) when the portal reports it expired.
 *
 * Protocol notes and sources: docs/RESEARCH.md §6. This is a clean-room implementation — do not
 * paste code from GPL clients (pvr.stalker, stalkerhek, OwnTV…).
 */
class StalkerClient(
    http: OkHttpClient,
    val profile: PortalProfile,
    knownEndpoint: String? = null,
    /** The session token from last time; portals usually keep it valid, which saves a handshake. */
    knownToken: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val probeHttp = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val authLock = Mutex()

    @Volatile private var endpoint: HttpUrl? = knownEndpoint?.toHttpUrlOrNull()
    @Volatile private var token: String? = knownToken?.takeIf { knownEndpoint != null }

    /** The current session token, for the app to keep between launches (never logged). */
    val currentToken: String? get() = token
    @Volatile private var lastAuthAt = 0L

    @Volatile var session: StalkerSession? = null
        private set

    /** Discovers the API endpoint (if not known yet), performs handshake + get_profile. */
    suspend fun connect(): StalkerSession = authLock.withLock { authenticateLocked() }

    suspend fun genres(): List<StalkerGenre> = authed { ep, tok ->
        request(ep, tok, "type" to "itv", "action" to "get_genres")
            .asItemArray()
            .mapNotNull { o ->
                val id = o.string("id") ?: return@mapNotNull null
                if (id == "*") return@mapNotNull null // the portal's own "All" pseudo-genre
                StalkerGenre(id = id, title = o.string("title") ?: o.string("alias") ?: id, censored = o.flag("censored"))
            }
    }

    /** Full channel list: `get_all_channels`, falling back to paging `get_ordered_list`. */
    suspend fun channels(): List<StalkerChannel> = authed { ep, tok ->
        val all = try {
            request(ep, tok, "type" to "itv", "action" to "get_all_channels").asItemArray()
        } catch (e: StalkerException.BadResponse) {
            emptyList()
        } catch (e: StalkerException.ServerError) {
            emptyList()
        }
        val raw = all.ifEmpty { orderedListAllPages(ep, tok) }
        raw.mapNotNull { parseChannel(it, ep) }
            .distinctBy { it.id }
            .sortedWith(compareBy<StalkerChannel> { if (it.number > 0) it.number else Int.MAX_VALUE }.thenBy { it.name.lowercase() })
    }

    /**
     * Resolves a playable URL for a channel. Always asks the portal (`create_link`) because many
     * portals hand out short-lived tokenised links; falls back to the raw `cmd` if the portal
     * cannot create one.
     */
    suspend fun streamUrl(cmd: String): String = try {
        createLink(cmd)
    } catch (e: StalkerException.BadResponse) {
        cleanStreamUrl(cmd) ?: throw e
    } catch (e: StalkerException.ServerError) {
        cleanStreamUrl(cmd) ?: throw e
    }

    suspend fun createLink(cmd: String): String = createLink("itv", cmd, series = null)

    /**
     * `create_link` for any module (`itv`, `vod`, `radio`). [series] is the episode number for
     * series stored as one VOD item; films must send it empty (`series=0` gives `nothing_to_play`).
     */
    suspend fun createLink(type: String, cmd: String, series: Int?): String = authed { ep, tok ->
        val js = request(
            ep, tok,
            "type" to type, "action" to "create_link", "cmd" to cmd, "series" to (series?.toString() ?: ""),
            "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0", "force_ch_link_check" to "0",
        )
        val obj = js as? JsonObject
        obj?.string("error")?.let { throw StalkerException.StreamUnavailable(it) }
        val raw = obj?.string("cmd") ?: obj?.string("url") ?: (js as? JsonPrimitive)?.text()
        val url = raw?.let(::cleanStreamUrl)
            ?: obj?.let { movieUrl(it, ep) }
            ?: throw StalkerException.BadResponse("Portal returned no stream URL")
        rewriteLocalhost(url, ep)
    }

    /** Some portals answer a VOD create_link with only `id` + `play_token`; the URL is then implied. */
    private fun movieUrl(obj: JsonObject, ep: HttpUrl): String? {
        val id = obj.string("id") ?: return null
        val playToken = obj.string("play_token") ?: return null
        return ep.newBuilder().encodedPath("/play/movie.php").query(null)
            .addQueryParameter("mac", profile.mac)
            .addQueryParameter("stream", if (id.contains('.')) id else "$id.mp4")
            .addQueryParameter("play_token", playToken)
            .addQueryParameter("type", "vod")
            .build().toString()
    }

    // ---------------------------------------------------------------------------------------------
    // Videos (VOD) and radio

    suspend fun videoCategories(): List<StalkerGenre> = authed { ep, tok ->
        request(ep, tok, "type" to "vod", "action" to "get_categories")
            .asItemArray()
            .mapNotNull { o ->
                val id = o.string("id") ?: return@mapNotNull null
                if (id == "*") return@mapNotNull null
                StalkerGenre(id = id, title = o.string("title") ?: o.string("alias") ?: id, censored = o.flag("censored"))
            }
    }

    /** One page (1-based) of a VOD category, newest first. */
    suspend fun videoPage(
        categoryId: String,
        page: Int,
        byName: Boolean = false,
        search: String = "",
    ): StalkerVideoPage = authed { ep, tok ->
        val params = mutableListOf(
            "type" to "vod", "action" to "get_ordered_list", "category" to categoryId, "genre" to "*",
            "sortby" to if (byName) "name" else "added", "fav" to "0", "p" to page.toString(),
        )
        if (search.isNotBlank()) params += "search" to search.trim()
        val js = request(ep, tok, *params.toTypedArray())
        val obj = js as? JsonObject
        val items = js.asItemArray().mapNotNull { parseVideo(it, categoryId, ep) }
        val total = obj?.int("total_items") ?: 0
        val perPage = (obj?.int("max_page_items") ?: items.size).coerceAtLeast(1)
        StalkerVideoPage(items, hasMore = items.isNotEmpty() && page * perPage < total)
    }

    /** Videos whose title matches [query], across every category (the portal's own search). */
    suspend fun searchVideos(query: String): List<StalkerVideo> = authed { ep, tok ->
        request(
            ep, tok, "type" to "vod", "action" to "get_ordered_list", "category" to "*", "genre" to "*",
            "search" to query, "sortby" to "added", "fav" to "0", "p" to "1",
        ).asItemArray().mapNotNull { parseVideo(it, "", ep) }
    }

    /**
     * Seasons and episodes of a series stored the Ministra way: `movie_id` lists seasons, and
     * `movie_id` + `season_id` lists a season's episodes. A season that carries its own `series`
     * array is expanded directly (one item per season, episodes by number).
     */
    suspend fun videoEpisodes(movieId: String): List<StalkerEpisode> = authed { ep, tok ->
        fun list(seasonId: String) = arrayOf(
            "type" to "vod", "action" to "get_ordered_list", "movie_id" to movieId,
            "season_id" to seasonId, "episode_id" to "0", "p" to "1",
        )
        val seasons = request(ep, tok, *list("0")).asItemArray()
        val out = ArrayList<StalkerEpisode>()
        for ((index, season) in seasons.withIndex()) {
            val seasonId = season.string("id") ?: continue
            val seasonName = season.string("name") ?: "Season ${index + 1}"
            val numbers = season.intArray("series")
            val seasonCmd = season.string("cmd")
            if (numbers.isNotEmpty() && seasonCmd != null) {
                numbers.forEach { n -> out += StalkerEpisode("$seasonId:$n", "Episode $n", seasonName, n, seasonCmd, series = n) }
                continue
            }
            for ((i, e) in request(ep, tok, *list(seasonId)).asItemArray().withIndex()) {
                val id = e.string("id") ?: continue
                val number = e.int("series_number") ?: e.int("number") ?: (i + 1)
                out += StalkerEpisode(
                    key = "$seasonId:$id",
                    title = e.string("name") ?: "Episode $number",
                    season = seasonName,
                    number = number,
                    cmd = e.string("cmd") ?: "/media/file_$id.mpg",
                    series = null,
                )
            }
        }
        out
    }

    /** The portal's link for a video; a `cmd` that is already a URL is the fallback if it refuses. */
    suspend fun videoUrl(cmd: String, series: Int?): String = try {
        createLink("vod", cmd, series)
    } catch (e: StalkerException.BadResponse) {
        cleanStreamUrl(cmd) ?: throw e
    } catch (e: StalkerException.StreamUnavailable) {
        cleanStreamUrl(cmd) ?: throw e
    }

    /** Radio stations (the portal's `radio` module); empty when the portal has none. */
    suspend fun radio(): List<StalkerRadio> = authed { ep, tok ->
        orderedListAllPages(ep, tok, "radio")
            .mapNotNull { o ->
                val id = o.string("id") ?: return@mapNotNull null
                val cmd = o.string("cmd") ?: return@mapNotNull null
                StalkerRadio(id, o.string("name") ?: "Station $id", o.int("number") ?: 0, cmd, o.string("logo")?.let { logoUrl(it, ep) })
            }
            .distinctBy { it.id }
    }

    /**
     * Addresses to try for a radio station, best first: the portal's own link (many panels only
     * stream with the play token it adds), then the `cmd` itself when that is a usable URL.
     */
    suspend fun radioUrls(cmd: String): List<String> {
        val direct = cleanStreamUrl(cmd)?.takeUnless { it.contains("://localhost") || it.contains("://127.0.0.1") }
        val linked = try {
            createLink("radio", cmd, series = null)
        } catch (e: StalkerException) {
            if (direct == null) throw e
            null
        }
        return listOfNotNull(linked, direct).distinct()
    }

    private fun parseVideo(o: JsonObject, categoryId: String, ep: HttpUrl): StalkerVideo? {
        val id = o.string("id") ?: return null
        val cmd = o.string("cmd") ?: return null
        return StalkerVideo(
            id = id,
            categoryId = o.string("category_id") ?: categoryId,
            title = o.string("name") ?: o.string("o_name") ?: "Video $id",
            description = o.string("description"),
            poster = o.string("screenshot_uri")?.let { posterUrl(it, ep) },
            year = o.string("year")?.takeUnless { it == "0" || it.equals("N/A", ignoreCase = true) },
            durationMin = o.int("time") ?: 0,
            genres = o.string("genres_str"),
            cmd = cmd,
            episodeNumbers = o.intArray("series"),
            isSeries = o.flag("is_series"),
        )
    }

    /**
     * Programme guide for every channel for the next [periodHours] (`get_epg_info`), keyed by
     * portal channel id. Ministra treats `period` as hours; the response is size-capped in case a
     * portal interprets it differently.
     */
    suspend fun epg(periodHours: Int): Map<String, List<StalkerProgramme>> = authed { ep, tok ->
        val js = request(
            ep, tok, "type" to "itv", "action" to "get_epg_info", "period" to periodHours.toString(),
            maxBytes = MAX_EPG_BYTES,
        )
        val data = (js as? JsonObject)?.get("data") as? JsonObject ?: return@authed emptyMap()
        data.mapValues { (_, list) ->
            (list as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { o ->
                val start = o.long("start_timestamp") ?: return@mapNotNull null
                val stop = o.long("stop_timestamp") ?: return@mapNotNull null
                val title = o.string("name") ?: return@mapNotNull null
                StalkerProgramme(start, stop, title, o.string("descr"))
            }
        }
    }

    /** Keep-alive the portal expects every `timeslot` seconds while the app is in use. */
    suspend fun watchdog() {
        authed { ep, tok ->
            request(
                ep, tok,
                "type" to "watchdog", "action" to "get_events",
                "init" to "0", "cur_play_type" to "1", "event_active_id" to "0",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Authentication

    private suspend fun <T> authed(block: suspend (HttpUrl, String) -> T): T {
        val (ep, tok) = currentSession()
        return try {
            block(ep, tok)
        } catch (e: StalkerException.AuthFailed) {
            val (newEp, newTok) = authLock.withLock {
                // Another caller may already have renewed the token while we waited (token != tok),
                // or tried and failed (token == null), in which case we try once more ourselves.
                val current = token
                if (current == null || current == tok) {
                    if (current == tok && clock() - lastAuthAt < MIN_REAUTH_INTERVAL_MS) throw e
                    authenticateLocked()
                }
                endpoint!! to token!!
            }
            block(newEp, newTok)
        }
    }

    private suspend fun currentSession(): Pair<HttpUrl, String> {
        val ep = endpoint
        val tok = token
        if (ep != null && tok != null) return ep to tok
        return authLock.withLock {
            if (token == null) authenticateLocked()
            endpoint!! to token!!
        }
    }

    /** Must hold [authLock]. Tries the known endpoint first, then the discovery candidates. */
    private suspend fun authenticateLocked(): StalkerSession {
        val candidates = (listOfNotNull(endpoint) + candidateEndpoints(profile.portalUrl)).distinct()
        var failure: IOException? = null
        for (candidate in candidates) {
            val handshake = try {
                handshake(candidate)
            } catch (e: StalkerException.NotAPortal) {
                failure = failure ?: e
                continue
            } catch (e: StalkerException.Throttled) {
                failure = e // may be a firewall rule on a wrong path; keep probing, but report this
                continue
            } catch (e: StalkerException) {
                throw e // a real portal answered with a definitive error
            } catch (e: IOException) {
                failure = failure ?: e
                continue
            }
            endpoint = candidate
            token = handshake.first
            lastAuthAt = clock()
            val session = getProfile(candidate, handshake.first, handshake.second)
            this.session = session
            return session
        }
        token = null
        throw failure ?: StalkerException.NotAPortal("No portal found at ${profile.portalUrl}")
    }

    private suspend fun handshake(ep: HttpUrl): Pair<String, String?> {
        val js = request(ep, null, "type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0", probe = true)
        val obj = js as? JsonObject ?: throw StalkerException.BadResponse("Unexpected handshake response")
        val tok = obj.string("token") ?: throw StalkerException.AuthFailed("Portal did not issue a token")
        return tok to obj.string("random")
    }

    private suspend fun getProfile(ep: HttpUrl, tok: String, random: String?): StalkerSession {
        val metrics = buildJsonObject {
            put("mac", profile.mac)
            put("sn", profile.serialNumber)
            put("model", profile.stbType)
            put("type", "STB")
            put("uid", profile.deviceId)
            put("random", random ?: "")
        }.toString()
        val js = request(
            ep, tok,
            "type" to "stb", "action" to "get_profile",
            "hd" to "1",
            "ver" to VER_STRING,
            "num_banks" to "2",
            "sn" to profile.serialNumber,
            "stb_type" to profile.stbType,
            "client_type" to "STB",
            "image_version" to "218",
            "video_out" to "hdmi",
            "device_id" to profile.deviceId,
            "device_id2" to profile.deviceId2,
            "signature" to profile.signature,
            "auth_second_step" to "1",
            "hw_version" to "1.7-BD-00",
            "not_valid_token" to "0",
            "metrics" to metrics,
            "timestamp" to (clock() / 1000).toString(),
            "api_signature" to "262",
        )
        val obj = js as? JsonObject
            ?: throw StalkerException.Blocked("The portal returned no profile. Is this MAC address registered with your provider?")
        val message = obj.string("block_msg") ?: obj.string("msg")
        when (val status = obj.int("status") ?: 0) {
            0 -> Unit
            2 -> throw StalkerException.Blocked(message ?: "This portal needs a username and password, which isn't supported yet.")
            else -> throw StalkerException.Blocked(message ?: "The portal rejected this device (status $status).")
        }
        return StalkerSession(
            endpoint = ep.toString(),
            timeslotSeconds = (obj.int("timeslot") ?: 90).coerceIn(30, 600),
            watchdogTimeoutSeconds = obj.int("watchdog_timeout") ?: 120,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Transport

    private suspend fun request(
        ep: HttpUrl,
        tok: String?,
        vararg params: Pair<String, String>,
        probe: Boolean = false,
        maxBytes: Long = MAX_RESPONSE_BYTES,
    ): JsonElement {
        val url = ep.newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()
        val request = Request.Builder().url(url).headers(portalHeaders(ep, tok)).get().build()
        val client = if (probe) probeHttp else http
        return withContext(Dispatchers.IO) { client.newCall(request).await().use { response -> parseResponse(response, ep, tok, maxBytes) } }
    }

    private fun parseResponse(response: Response, ep: HttpUrl, tok: String?, maxBytes: Long): JsonElement {
        // Bounded read: a portal returning far more than expected must not exhaust a 2 GB stick.
        val source = response.body.source()
        if (source.request(maxBytes + 1)) throw StalkerException.BadResponse("Portal response too large")
        val body = source.buffer.readUtf8()
        when (response.code) {
            in 200..299 -> Unit
            401 -> throw StalkerException.AuthFailed("HTTP 401")
            403, 429, 458, 509 -> throw StalkerException.Throttled(response.code)
            400, 404, 405, 410 -> throw StalkerException.NotAPortal("HTTP ${response.code} at ${ep.encodedPath}")
            else -> throw StalkerException.ServerError(response.code)
        }
        val trimmed = body.trimStart()
        if (trimmed.startsWith("Authorization failed", ignoreCase = true)) throw StalkerException.AuthFailed()
        if (trimmed.isEmpty() || trimmed.startsWith("<")) {
            throw StalkerException.NotAPortal("Not a portal API response at ${ep.encodedPath}")
        }
        val root = try {
            json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            throw StalkerException.BadResponse("Unreadable portal response: ${e.message}")
        }
        val js = (root as? JsonObject)?.get("js")
            ?: throw StalkerException.BadResponse("Portal response has no 'js' field")
        if (tok != null && js is JsonPrimitive && js.booleanOrNull == false) throw StalkerException.AuthFailed()
        return js
    }

    private fun portalHeaders(ep: HttpUrl, tok: String?): Headers = Headers.Builder()
        .add("User-Agent", profile.userAgent)
        .add("X-User-Agent", "Model: ${profile.stbType}; Link: Ethernet")
        .add("Referer", refererFor(ep))
        .add("Accept", "*/*")
        .add("Cookie", "mac=${encode(profile.mac)}; stb_lang=en; timezone=${encode(profile.timezone)}")
        .apply { if (tok != null) add("Authorization", "Bearer $tok") }
        .build()

    private suspend fun orderedListAllPages(ep: HttpUrl, tok: String, type: String = "itv"): List<JsonObject> = coroutineScope {
        fun page(p: Int) = arrayOf(
            "type" to type, "action" to "get_ordered_list", "genre" to "*", "fav" to "0",
            "sortby" to "number", "p" to p.toString(),
        )
        val first = request(ep, tok, *page(1)) as? JsonObject ?: return@coroutineScope emptyList()
        val total = first.int("total_items") ?: 0
        val perPage = (first.int("max_page_items") ?: 14).coerceAtLeast(1)
        val pages = ((total + perPage - 1) / perPage).coerceAtMost(MAX_PAGES)
        val limiter = Semaphore(PAGE_PARALLELISM)
        val rest = (2..pages).map { p -> async { limiter.withPermit { request(ep, tok, *page(p)).asItemArray() } } }
        first.asItemArray() + rest.awaitAll().flatten()
    }

    private fun parseChannel(o: JsonObject, ep: HttpUrl): StalkerChannel? {
        val id = o.string("id") ?: return null
        val cmd = o.string("cmd")
            ?: ((o["cmds"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("url")
            ?: return null
        return StalkerChannel(
            id = id,
            name = o.string("name") ?: "Channel $id",
            number = o.int("number") ?: 0,
            cmd = cmd,
            logo = o.string("logo")?.let { logoUrl(it, ep) },
            genreId = o.string("tv_genre_id") ?: "",
            useTmpLink = o.flag("use_http_tmp_link") || o.flag("use_load_balancing"),
            hasArchive = o.flag("enable_tv_archive") || o.flag("archive") || o.flag("tv_archive"),
            archiveHours = o.int("tv_archive_duration") ?: 0,
            xmltvId = o.string("xmltv_id"),
            censored = o.flag("censored"),
        )
    }

    companion object {
        const val MIN_REAUTH_INTERVAL_MS = 60_000L
        private const val MAX_RESPONSE_BYTES = 48L * 1024 * 1024
        private const val MAX_EPG_BYTES = 40L * 1024 * 1024
        private const val PAGE_PARALLELISM = 4
        private const val MAX_PAGES = 2_000
        private const val VER_STRING =
            "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; PORTAL version: 5.6.1; " +
                "API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * API endpoints to probe, most likely first. Accepts what providers hand out:
         * `http://host/c/`, `http://host/stalker_portal/c/`, `http://host:8080/`, `…/portal.php`.
         */
        fun candidateEndpoints(portalUrl: String): List<HttpUrl> {
            var s = portalUrl.trim()
            if (!s.contains("://")) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: throw StalkerException.NotAPortal("Invalid portal URL: $portalUrl")
            val clean = url.newBuilder().query(null).fragment(null).build()
            if (clean.encodedPath.endsWith(".php")) return listOf(clean)

            var dir = clean.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
            val wasUiPath = dir.endsWith("/c/")
            if (wasUiPath) dir = dir.removeSuffix("c/")
            val base = clean.newBuilder().encodedPath(dir).build()
            val loadPhp = base.resolve("server/load.php")!!
            val portalPhp = base.resolve("portal.php")!!
            val list = if (wasUiPath) listOf(loadPhp, portalPhp) else listOf(portalPhp, loadPhp)
            val stalkerPortal = if (!dir.contains("/stalker_portal/")) base.resolve("stalker_portal/server/load.php") else null
            return (list + listOfNotNull(stalkerPortal)).distinct()
        }

        /** The portal's web UI directory, which real MAG boxes send as Referer. */
        fun refererFor(ep: HttpUrl): String {
            val dir = ep.encodedPath.substringBeforeLast('/') + "/"
            val root = dir.removeSuffix("server/")
            return ep.newBuilder().encodedPath(root + "c/").query(null).build().toString()
        }

        /** Strips player prefixes such as `ffmpeg `, `ffrt2 `, `auto ` from a portal `cmd`. */
        fun cleanStreamUrl(cmd: String): String? {
            val t = cmd.trim()
            val space = t.indexOf(' ')
            val candidate = if (space > 0 && !t.substring(0, space).contains("://")) t.substring(space + 1).trim() else t
            val scheme = candidate.substringBefore("://", "").lowercase()
            return candidate.takeIf { scheme in setOf("http", "https", "rtmp", "rtsp", "udp", "rtp") }
        }

        /** Some portals return links on `localhost`; they mean "the portal host". */
        fun rewriteLocalhost(url: String, ep: HttpUrl): String {
            val parsed = url.toHttpUrlOrNull() ?: return url
            if (parsed.host != "localhost" && parsed.host != "127.0.0.1") return url
            return parsed.newBuilder().scheme(ep.scheme).host(ep.host).port(ep.port).build().toString()
        }

        /** Screenshots are absolute URLs or portal paths such as `/stalker_portal/screenshots/1.jpg`. */
        fun posterUrl(path: String, ep: HttpUrl): String? = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> ep.resolve(path)?.toString()
            else -> null
        }

        fun logoUrl(logo: String, ep: HttpUrl): String? {
            if (logo.startsWith("http://") || logo.startsWith("https://")) return logo
            if (logo.startsWith("/")) return ep.resolve(logo)?.toString()
            val root = refererFor(ep).removeSuffix("c/")
            return "${root}misc/logos/320/$logo"
        }
    }
}
