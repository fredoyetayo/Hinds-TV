package com.hindstv.data.source

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PlaylistSourceTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun source(path: String) =
        PlaylistSource(OkHttpClient(), SourceProfile.Playlist(server.url(path).toString())) { null }

    @Test
    fun `an m3u playlist becomes groups and channels`() = runTest {
        server.enqueue(
            MockResponse(
                body = """
                    #EXTM3U x-tvg-url="http://g/epg.xml"
                    #EXTINF:-1 tvg-id="a.uk" tvg-logo="http://l/a.png" group-title="News",Alpha News
                    http://s/a.ts
                    #EXTINF:-1 tvg-id="b.uk" group-title="Sport",Beta Sport
                    http://s/b.m3u8
                    #EXTINF:-1 group-title="News",Alpha News
                    http://s/a2.ts
                """.trimIndent(),
            ),
        )

        val content = source("/list.m3u").load()

        assertEquals(listOf("News", "Sport"), content.genres.map { it.title })
        assertEquals(3, content.channels.size)
        assertEquals("http://g/epg.xml", content.epgUrl)
        val alpha = content.channels[0]
        assertEquals("Alpha News", alpha.name)
        assertEquals("http://s/a.ts", alpha.cmd)
        assertEquals("a.uk", alpha.epgId)
        assertEquals("g:News", alpha.genreId)
        // Same name and group without tvg-id still gets a unique, stable id.
        assertEquals(3, content.channels.map { it.id }.toSet().size)
    }

    @Test
    fun `videos and radio are separated from live channels`() = runTest {
        server.enqueue(
            MockResponse(
                body = """
                    #EXTM3U
                    #EXTINF:-1 group-title="News",Alpha News
                    http://s/live/a.ts
                    #EXTINF:-1 tvg-logo="http://l/cells.png" group-title="GCSE Biology",Cell structure
                    http://s/vod/cells.mp4
                    #EXTINF:-1 group-title="GCSE Biology",Microscopy
                    http://s/movie/user/pass/42.mkv
                    #EXTINF:-1 group-title="UK Radio",Talk FM
                    http://s/stream/talk
                    #EXTINF:-1 radio="true",Jazz
                    http://s/jazz
                """.trimIndent(),
            ),
        )

        val content = source("/list.m3u").load()

        assertEquals(listOf("Alpha News"), content.channels.map { it.name })
        assertEquals(listOf("News"), content.genres.map { it.title })
        assertEquals(listOf("GCSE Biology"), content.videoCategories.map { it.title })
        assertEquals(listOf("Cell structure", "Microscopy"), content.videos.map { it.title })
        assertEquals("http://l/cells.png", content.videos[0].poster)
        assertEquals(content.videoCategories[0].id, content.videos[1].categoryId)
        assertEquals(listOf("Talk FM", "Jazz"), content.radio.map { it.name })
        assertEquals(listOf(1, 2), content.radio.map { it.number })
    }

    @Test
    fun `an hls link is a single channel`() = runTest {
        server.enqueue(MockResponse(body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))

        val content = source("/x36xhzz/x36xhzz.m3u8").load()

        val channel = content.channels.single()
        assertEquals("x36xhzz", channel.name)
        assertTrue(channel.cmd.endsWith("/x36xhzz/x36xhzz.m3u8"))
    }

    @Test
    fun `a raw video stream is a single channel`() = runTest {
        server.enqueue(MockResponse(body = "G@\u0000\u0010 binary transport stream bytes"))
        assertEquals(1, source("/live/stream.ts").load().channels.size)
    }

    @Test
    fun `a web page is rejected with a clear message`() = runTest {
        server.enqueue(MockResponse(body = "<!doctype html><html><body>Login</body></html>"))
        try {
            source("/").load()
            fail("expected PlaylistException")
        } catch (e: PlaylistException) {
            assertTrue(e.message!!.contains("web page"))
        }
    }
}
