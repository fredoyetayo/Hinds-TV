package com.hindstv.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(text.lineSequence())

    @Test
    fun `parses extended entries with quoted attributes containing commas`() {
        val playlist = parse(
            """
            #EXTM3U url-tvg="http://guide.example/epg.xml.gz" tvg-shift=0
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One, HD" tvg-logo="http://logo/bbc1.png" tvg-chno="101" group-title="UK, Main",BBC One HD
            http://stream.example/bbc1.m3u8
            #EXTINF:-1 tvg-id='itv1.uk' group-title="UK, Main",ITV1
            #EXTVLCOPT:http-user-agent=Something
            http://stream.example/itv1.ts
            """.trimIndent(),
        )

        assertEquals("http://guide.example/epg.xml.gz", playlist.epgUrl)
        assertEquals(2, playlist.entries.size)
        val bbc = playlist.entries[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("http://stream.example/bbc1.m3u8", bbc.url)
        assertEquals("bbc1.uk", bbc.attributes["tvg-id"])
        assertEquals("BBC One, HD", bbc.attributes["tvg-name"])
        assertEquals("101", bbc.attributes["tvg-chno"])
        assertEquals("UK, Main", bbc.group)
        assertEquals("itv1.uk", playlist.entries[1].attributes["tvg-id"])
        assertEquals("http://stream.example/itv1.ts", playlist.entries[1].url)
    }

    @Test
    fun `uses EXTGRP and accepts bare urls`() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,News 24
            #EXTGRP:News
            http://s/news.ts
            http://s/films/movie.mp4?token=abc
            """.trimIndent(),
        )

        assertNull(playlist.epgUrl)
        assertEquals("News", playlist.entries[0].group)
        assertEquals("News 24", playlist.entries[0].name)
        assertEquals("movie", playlist.entries[1].name)
        assertNull(playlist.entries[1].group)
    }

    @Test
    fun `falls back to tvg-name when the title is missing`() {
        val playlist = parse("#EXTINF:-1 tvg-name=\"Sky Sports\",\nhttp://s/sky.ts")
        assertEquals("Sky Sports", playlist.entries.single().name)
    }

    @Test
    fun `attribute parsing tolerates unquoted values and odd spacing`() {
        val attrs = M3uParser.parseAttributes(""" tvg-id=abc   group-title="A B"  catchup="default" """)
        assertEquals(mapOf("tvg-id" to "abc", "group-title" to "A B", "catchup" to "default"), attrs)
    }

    @Test
    fun `entries are classified as live, video or radio`() {
        fun kind(url: String, group: String? = null, attrs: Map<String, String> = emptyMap()) =
            M3uParser.kind(M3uParser.Entry("x", url, attrs, group))

        assertEquals(M3uParser.Kind.Live, kind("http://h/live/u/p/1.ts"))
        assertEquals(M3uParser.Kind.Live, kind("http://h/play/live.php?stream=1&extension=ts"))
        assertEquals(M3uParser.Kind.Live, kind("http://h/hls/index.m3u8", group = "Sky Movies"))
        assertEquals(M3uParser.Kind.Video, kind("http://h/movie/u/p/1.mp4"))
        assertEquals(M3uParser.Kind.Video, kind("http://h/series/u/p/7.mkv"))
        assertEquals(M3uParser.Kind.Video, kind("http://h/lessons/intro.MP4?token=1"))
        assertEquals(M3uParser.Kind.Radio, kind("http://h/stream.mp3"))
        assertEquals(M3uParser.Kind.Radio, kind("http://h/s", group = "UK Radio"))
        assertEquals(M3uParser.Kind.Live, kind("http://h/s", group = "Radiography lessons"))
        assertEquals(M3uParser.Kind.Radio, kind("http://h/s", attrs = mapOf("radio" to "true")))
    }
}
