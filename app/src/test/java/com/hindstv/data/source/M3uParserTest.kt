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
}
