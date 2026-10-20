package com.hindstv.data.stalker

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class StalkerClientTest {
    private val server = MockWebServer()
    private val portal = FakePortal()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        server.dispatcher = portal
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(path: String = "/stalker_portal/c/") = StalkerClient(
        http = OkHttpClient(),
        profile = PortalProfile(portalUrl = server.url(path).toString(), mac = "00:1A:79:12:34:56"),
        clock = { now },
    )

    @Test
    fun `discovers the API endpoint and authenticates like a MAG box`() = runTest {
        val session = client("/stalker_portal/").connect()

        assertTrue(session.endpoint.endsWith("/stalker_portal/server/load.php"))
        assertEquals(60, session.timeslotSeconds)
        // portal.php was probed first (404), then server/load.php answered.
        assertTrue(portal.requests.any { it.url.encodedPath == "/stalker_portal/portal.php" })

        val profileRequest = portal.requests.last { it.url.queryParameter("action") == "get_profile" }
        assertEquals("Bearer TOKEN1", profileRequest.headers["Authorization"])
        assertTrue(profileRequest.headers["Cookie"]!!.contains("mac=00%3A1A%3A79%3A12%3A34%3A56"))
        assertTrue(profileRequest.headers["User-Agent"]!!.contains("MAG200"))
        assertTrue(profileRequest.headers["Referer"]!!.endsWith("/stalker_portal/c/"))
        assertEquals("MAG250", profileRequest.url.queryParameter("stb_type"))
    }

    @Test
    fun `parses channels with inconsistent field types`() = runTest {
        val channels = client().channels()

        assertEquals(listOf("BBC One", "ITV1", "Sky News"), channels.map { it.name })
        val bbc = channels[0]
        assertEquals(1, bbc.number)
        assertEquals("10", bbc.genreId)
        assertTrue(bbc.useTmpLink)
        assertTrue(bbc.hasArchive)
        assertTrue(bbc.logo!!.endsWith("/stalker_portal/misc/logos/320/bbc1.png"))
        assertFalse(channels[1].useTmpLink)
        assertEquals("http://cdn.example/itv.png", channels[1].logo)
    }

    @Test
    fun `falls back to paging when get_all_channels is unavailable`() = runTest {
        portal.allChannelsAvailable = false

        val channels = client().channels()

        assertEquals(3, channels.size)
        assertEquals(2, portal.requests.count { it.url.queryParameter("action") == "get_ordered_list" })
    }

    @Test
    fun `genres skip the portal's own All entry`() = runTest {
        val genres = client().genres()
        assertEquals(listOf("UK", "Sport"), genres.map { it.title })
    }

    @Test
    fun `create_link strips the player prefix and rewrites localhost`() = runTest {
        val url = client().streamUrl("ffmpeg http://localhost/ch/1")

        assertTrue(url.startsWith("http://${server.hostName}:${server.port}/play/live.php"))
        assertTrue(url.contains("play_token=xyz"))
    }

    @Test
    fun `renews an expired token once and retries`() = runTest {
        val client = client()
        client.connect()
        portal.validToken = "revoked" // the portal forgets our session
        now += StalkerClient.MIN_REAUTH_INTERVAL_MS + 1

        val genres = client.genres()

        assertEquals(2, genres.size)
        assertEquals(2, portal.requests.count { it.url.queryParameter("action") == "handshake" })
    }

    @Test
    fun `a token kept from last time skips the handshake`() = runTest {
        val first = client()
        first.connect()
        val endpoint = first.session!!.endpoint
        val token = first.currentToken!!
        portal.requests.clear()

        val restarted = StalkerClient(
            http = OkHttpClient(),
            profile = PortalProfile(portalUrl = server.url("/stalker_portal/c/").toString(), mac = "00:1A:79:12:34:56"),
            knownEndpoint = endpoint,
            knownToken = token,
            clock = { now },
        )
        restarted.streamUrl("ffmpeg http://localhost/ch/1")

        assertEquals(listOf("create_link"), portal.requests.map { it.url.queryParameter("action") })
    }

    @Test
    fun `an expired kept token is renewed transparently`() = runTest {
        val first = client()
        first.connect()
        val endpoint = first.session!!.endpoint
        portal.requests.clear()

        val restarted = StalkerClient(
            http = OkHttpClient(),
            profile = PortalProfile(portalUrl = server.url("/stalker_portal/c/").toString(), mac = "00:1A:79:12:34:56"),
            knownEndpoint = endpoint,
            knownToken = "STALE",
            clock = { now },
        )
        val url = restarted.streamUrl("ffmpeg http://localhost/ch/1")

        assertTrue(url.contains("play_token=xyz"))
        assertEquals(1, portal.requests.count { it.url.queryParameter("action") == "handshake" })
    }

    @Test
    fun `reads the bulk programme guide per channel`() = runTest {
        val epg = client().epg(periodHours = 6)

        assertEquals("6", portal.requests.last().url.queryParameter("period"))
        val bbc = epg.getValue("1")
        assertEquals(2, bbc.size)
        assertEquals("Breakfast", bbc[0].title)
        assertEquals(1_000L, bbc[0].start)
        assertEquals(4_600L, bbc[0].stop)
        assertEquals("Morning news", bbc[0].description)
        assertEquals(emptyList<StalkerProgramme>(), epg.getValue("3")) // entries without times are dropped
    }

    @Test
    fun `lists video categories and pages through a category`() = runTest {
        val client = client()

        assertEquals(listOf("GCSE Maths", "GCSE Biology"), client.videoCategories().map { it.title })
        val first = client.videoPage("5", page = 1)
        assertTrue(first.hasMore)
        val algebra = first.items[0]
        assertEquals("Algebra basics", algebra.title)
        assertEquals("2024", algebra.year)
        assertEquals(12, algebra.durationMin)
        assertTrue(algebra.poster!!.endsWith("/stalker_portal/screenshots/1.jpg"))
        assertEquals(listOf(1, 2, 3), first.items[1].episodeNumbers)
        val second = client.videoPage("5", page = 2)
        assertFalse(second.hasMore)
        assertEquals(listOf("Ratios"), second.items.map { it.title })
        assertEquals("added", portal.requests.last().url.queryParameter("sortby"))
    }

    @Test
    fun `a film's link is created without an episode number`() = runTest {
        val url = client().videoUrl("/media/9.mpg", series = null)

        val request = portal.requests.last()
        assertEquals("vod", request.url.queryParameter("type"))
        assertEquals("", request.url.queryParameter("series"))
        assertEquals("http://cdn.example/vod/9.mp4", url)
    }

    @Test
    fun `an episode's link names the episode`() = runTest {
        client().videoUrl("/media/10.mpg", series = 2)
        assertEquals("2", portal.requests.last().url.queryParameter("series"))
    }

    @Test
    fun `a link given as id and play token becomes a movie URL`() = runTest {
        portal.vodLinkAsToken = true

        val url = client().videoUrl("/media/9.mpg", series = null)

        assertTrue(url.startsWith("http://${server.hostName}:${server.port}/play/movie.php?"))
        assertTrue(url.contains("stream=9.mp4"))
        assertTrue(url.contains("play_token=tok9"))
        assertTrue(url.contains("type=vod"))
    }

    @Test
    fun `series seasons and episodes are drilled into`() = runTest {
        val episodes = client().videoEpisodes("77")

        assertEquals(listOf("Lesson 1", "Lesson 2", "Episode 1", "Episode 2"), episodes.map { it.title })
        assertEquals(listOf("Unit 1", "Unit 1", "Unit 2", "Unit 2"), episodes.map { it.season })
        assertEquals("/media/file_501.mpg", episodes[1].cmd) // no cmd: the portal's file path
        assertEquals(null, episodes[0].series)
        assertEquals(2, episodes[3].series) // a season carrying its own episode numbers
        assertEquals(4, episodes.map { it.key }.toSet().size)
    }

    @Test
    fun `radio asks the portal for a link first and keeps the direct URL as a fallback`() = runTest {
        val client = client()
        val stations = client.radio()

        assertEquals(listOf("Talk FM", "Jazz"), stations.map { it.name })
        assertEquals(
            listOf("http://radio.example/linked/2", "http://radio.example/talk"),
            client.radioUrls(stations[0].cmd),
        )
        assertEquals("radio", portal.requests.last().url.queryParameter("type"))
        // A station whose cmd is not a URL can only play through the portal's link.
        assertEquals(listOf("http://radio.example/linked/2"), client.radioUrls(stations[1].cmd))
    }

    @Test
    fun `radio plays the direct URL when the portal cannot make a link`() = runTest {
        portal.radioLinkFails = true
        val client = client()
        val stations = client.radio()

        assertEquals(listOf("http://radio.example/talk"), client.radioUrls(stations[0].cmd))
    }

    @Test
    fun `reads the account expiry whatever the portal calls it`() = runTest {
        val account = client().accountInfo()
        assertEquals("2027-03-01 00:00:00", account.expiry)
        assertEquals("Premium", account.plan)
    }

    @Test
    fun `unregistered device reports the portal's message`() = runTest {
        portal.blockMessage = "MAC address not registered"
        try {
            client().connect()
            fail("expected Blocked")
        } catch (e: StalkerException.Blocked) {
            assertEquals("MAC address not registered", e.portalMessage)
        }
    }

    @Test
    fun `a site that is not a portal is reported as such`() = runTest {
        try {
            client("/nothing-here/").connect()
            fail("expected NotAPortal")
        } catch (e: StalkerException.NotAPortal) {
            // expected
        }
    }

    /** Minimal Ministra-like portal at /stalker_portal/server/load.php. */
    private class FakePortal : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()
        var validToken: String? = null
        var allChannelsAvailable = true
        var blockMessage: String? = null
        private var tokens = 0

        private val channels = listOf(
            """{"id":"3","name":"Sky News","number":"501","cmd":"ffmpeg http://h/3","tv_genre_id":"11"}""",
            """{"id":1,"name":"BBC One","number":1,"cmd":"ffmpeg http://h/1","tv_genre_id":10,"logo":"bbc1.png","use_http_tmp_link":"1","enable_tv_archive":1}""",
            """{"id":"2","name":"ITV1","number":"3","cmd":"ffmpeg http://h/2","logo":"http://cdn.example/itv.png","use_http_tmp_link":0}""",
            """{"id":"2","name":"ITV1 duplicate","number":"3","cmd":"ffmpeg http://h/2"}""",
            """{"id":"9","name":"No stream"}""",
        )

        override fun dispatch(request: RecordedRequest): MockResponse {
            synchronized(requests) { requests += request }
            val url = request.url
            if (url.encodedPath != "/stalker_portal/server/load.php") {
                return MockResponse(code = 404, body = "<html>Not found</html>")
            }
            val call = "${url.queryParameter("type")}/${url.queryParameter("action")}"
            if (call == "stb/handshake") {
                validToken = "TOKEN${++tokens}"
                return js("""{"token":"$validToken","random":"r4nd0m"}""")
            }
            if (request.headers["Authorization"] != "Bearer $validToken") return MockResponse(body = "Authorization failed.")
            return when (call) {
                "stb/get_profile" -> blockMessage?.let { js("""{"status":1,"block_msg":"$it"}""") }
                    ?: js("""{"id":"77","status":0,"timeslot":"60","watchdog_timeout":120}""")
                "itv/get_genres" -> js("""[{"id":"*","title":"All"},{"id":"10","title":"UK"},{"id":"11","title":"Sport"}]""")
                "itv/get_all_channels" ->
                    if (allChannelsAvailable) js("""{"total_items":5,"data":[${channels.joinToString(",")}]}""") else js("[]")
                "itv/get_ordered_list" -> when (url.queryParameter("p")) {
                    "1" -> js("""{"total_items":3,"max_page_items":2,"data":[${channels[0]},${channels[1]}]}""")
                    else -> js("""{"total_items":3,"max_page_items":2,"data":[${channels[2]}]}""")
                }
                "itv/get_epg_info" -> js(EPG)
                "itv/create_link" -> js("""{"id":"1","cmd":"ffmpeg http://localhost/play/live.php?stream=1&play_token=xyz"}""")
                "account_info/get_main_info" -> js("""{"phone":"","end_date":"2027-03-01 00:00:00","tariff_plan":"Premium"}""")
                "vod/get_categories" -> js("""[{"id":"*","title":"All"},{"id":"5","title":"GCSE Maths"},{"id":"6","title":"GCSE Biology"}]""")
                "vod/get_ordered_list" -> vodList(url)
                "vod/create_link" ->
                    if (vodLinkAsToken) js("""{"id":"9","play_token":"tok9"}""") else js("""{"cmd":"ffmpeg http://cdn.example/vod/9.mp4"}""")
                "radio/get_ordered_list" -> js(
                    """{"total_items":2,"max_page_items":10,"data":[
                        {"id":"1","name":"Talk FM","number":"1","cmd":"ffmpeg http://radio.example/talk"},
                        {"id":2,"name":"Jazz","cmd":"auto /media/radio_2.mpg"}]}""",
                )
                "radio/create_link" ->
                    if (radioLinkFails) js("""{"error":"nothing_to_play"}""") else js("""{"cmd":"ffmpeg http://radio.example/linked/2"}""")
                else -> js("{}")
            }
        }

        var vodLinkAsToken = false
        var radioLinkFails = false

        private fun vodList(url: okhttp3.HttpUrl): MockResponse {
            val movie = url.queryParameter("movie_id")
            val season = url.queryParameter("season_id")
            return when {
                movie == "77" && season == "0" -> js(
                    """{"total_items":2,"max_page_items":10,"data":[
                        {"id":"s1","name":"Unit 1"},
                        {"id":"s2","name":"Unit 2","series":[1,2],"cmd":"/media/s2.mpg"}]}""",
                )
                movie == "77" && season == "s1" -> js(
                    """{"total_items":2,"max_page_items":10,"data":[
                        {"id":"500","name":"Lesson 1","series_number":"1","cmd":"/media/file_500.mpg"},
                        {"id":"501","name":"Lesson 2","series_number":2}]}""",
                )
                url.queryParameter("p") == "1" -> js(
                    """{"total_items":3,"max_page_items":2,"data":[
                        {"id":"1","name":"Algebra basics","year":"2024","time":"12","screenshot_uri":"/stalker_portal/screenshots/1.jpg","cmd":"/media/1.mpg"},
                        {"id":"2","name":"Geometry","series":["1","2","3"],"cmd":"/media/2.mpg","year":"0"}]}""",
                )
                else -> js("""{"total_items":3,"max_page_items":2,"data":[{"id":"3","name":"Ratios","cmd":"/media/3.mpg"}]}""")
            }
        }

        private val EPG = """{"data":{
            "1":[{"name":"Breakfast","descr":"Morning news","start_timestamp":1000,"stop_timestamp":"4600"},
                 {"name":"Lunch","start_timestamp":"4600","stop_timestamp":8200}],
            "3":[{"name":"Broken"}]
        }}"""

        private fun js(body: String) = MockResponse(body = """{"js":$body}""")
    }
}
