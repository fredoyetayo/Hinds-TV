package com.hindstv.data.stalker

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StalkerUrlsTest {
    private fun candidates(url: String) = StalkerClient.candidateEndpoints(url).map { it.toString() }

    @Test
    fun `ui path points at server load php first`() {
        assertEquals(
            listOf(
                "http://host:8080/server/load.php",
                "http://host:8080/portal.php",
                "http://host:8080/stalker_portal/server/load.php",
            ),
            candidates("http://host:8080/c/"),
        )
    }

    @Test
    fun `stalker_portal ui path`() {
        assertEquals(
            listOf("http://host/stalker_portal/server/load.php", "http://host/stalker_portal/portal.php"),
            candidates("http://host/stalker_portal/c/"),
        )
    }

    @Test
    fun `bare host tries portal php first and adds a scheme`() {
        assertEquals(
            listOf("http://free.example/portal.php", "http://free.example/server/load.php", "http://free.example/stalker_portal/server/load.php"),
            candidates("free.example"),
        )
        assertEquals("https://free.example/portal.php", candidates("https://free.example/").first())
    }

    @Test
    fun `explicit php endpoint is used as is`() {
        assertEquals(listOf("http://host/portal.php"), candidates("http://host/portal.php?x=1"))
    }

    @Test
    fun `referer is the portal ui directory`() {
        assertEquals("http://host/stalker_portal/c/", StalkerClient.refererFor("http://host/stalker_portal/server/load.php".toHttpUrl()))
        assertEquals("http://host/c/", StalkerClient.refererFor("http://host/portal.php".toHttpUrl()))
    }

    @Test
    fun `stream urls lose their player prefix`() {
        assertEquals("http://h/live.php?a=1", StalkerClient.cleanStreamUrl("ffmpeg http://h/live.php?a=1"))
        assertEquals("http://h/x.ts", StalkerClient.cleanStreamUrl("  ffrt2 http://h/x.ts "))
        assertEquals("http://h/x.ts", StalkerClient.cleanStreamUrl("http://h/x.ts"))
        assertNull(StalkerClient.cleanStreamUrl("auto /media/123.mpg"))
    }

    @Test
    fun `localhost links are rewritten to the portal host`() {
        val ep = "http://portal.example:8080/server/load.php".toHttpUrl()
        assertEquals("http://portal.example:8080/ch/1?t=2", StalkerClient.rewriteLocalhost("http://localhost/ch/1?t=2", ep))
        assertEquals("http://cdn.example/ch/1", StalkerClient.rewriteLocalhost("http://cdn.example/ch/1", ep))
    }

    @Test
    fun `logo paths resolve against the portal`() {
        val ep = "http://host/stalker_portal/server/load.php".toHttpUrl()
        assertEquals("http://host/stalker_portal/misc/logos/320/a.png", StalkerClient.logoUrl("a.png", ep))
        assertEquals("http://host/logos/a.png", StalkerClient.logoUrl("/logos/a.png", ep))
        assertEquals("https://cdn/a.png", StalkerClient.logoUrl("https://cdn/a.png", ep))
    }

    @Test
    fun `mac addresses are normalised`() {
        assertEquals("00:1A:79:AB:CD:EF", Mac.normalize("00:1a:79:ab:cd:ef"))
        assertEquals("00:1A:79:AB:CD:EF", Mac.normalize("001A79ABCDEF"))
        assertEquals("00:1A:79:AB:CD:EF", Mac.normalize("00-1A-79-AB-CD-EF"))
        assertNull(Mac.normalize("00:1A:79:"))
        assertNull(Mac.normalize("00:1A:79:AB:CD:EG"))
    }
}
