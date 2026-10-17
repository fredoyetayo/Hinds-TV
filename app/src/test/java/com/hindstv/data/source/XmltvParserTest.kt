package com.hindstv.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class XmltvParserTest {
    // 2026-10-17 20:00:00 UTC
    private val t0 = 1_792_267_200L

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="test">
          <channel id="bbc1.uk"><display-name>BBC One</display-name></channel>
          <channel id="xyz-123"><display-name lang="en">ITV1</display-name><icon src="x"/></channel>
          <channel id="unused"><display-name>Unknown</display-name></channel>
          <programme start="20261017200000 +0000" stop="20261017210000 +0000" channel="bbc1.uk">
            <title lang="en">News at Nine</title><desc>The day's news.</desc><category>News</category>
          </programme>
          <programme start="20261017220000 +0100" stop="20261017230000 +0100" channel="xyz-123">
            <title>Drama &amp; More</title>
          </programme>
          <programme start="20261020200000 +0000" stop="20261020210000 +0000" channel="bbc1.uk">
            <title>Too far ahead</title>
          </programme>
          <programme start="20261017200000 +0000" stop="20261017210000 +0000" channel="unused">
            <title>Not ours</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun `keeps our channels inside the window, matching by id then display name`() {
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val channels = listOf(
            EpgChannelRef("c1", "bbc1.uk", "BBC One HD"),
            EpgChannelRef("c2", null, "ITV1"),
        )

        val programmes = XmltvParser(t0 - 3600, t0 + 12 * 3600).parse(parser, channels).toList()

        assertEquals(2, programmes.size)
        val news = programmes[0]
        assertEquals("c1", news.channelId)
        assertEquals(t0, news.start)
        assertEquals(t0 + 3600, news.stop)
        assertEquals("News at Nine", news.title)
        assertEquals("The day's news.", news.description)
        val drama = programmes[1]
        assertEquals("c2", drama.channelId)
        assertEquals("Drama & More", drama.title)
        assertEquals(t0 + 3600, drama.start) // 22:00 +0100 == 21:00 UTC
        assertNull(drama.description)
    }

    @Test
    fun `parses xmltv times with and without offsets`() {
        assertEquals(t0, XmltvParser.parseTime("20261017200000 +0000"))
        assertEquals(t0, XmltvParser.parseTime("20261017200000"))
        assertEquals(t0, XmltvParser.parseTime("20261017150000 -0500"))
        assertNull(XmltvParser.parseTime("garbage"))
    }
}
