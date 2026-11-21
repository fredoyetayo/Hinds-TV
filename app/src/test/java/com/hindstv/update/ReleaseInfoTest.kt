package com.hindstv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseInfoTest {
    private fun release(tag: String, vararg assets: String) = """
        {"tag_name":"$tag","name":"Hinds TV","assets":[${assets.joinToString(",") {
            """{"name":"$it","browser_download_url":"https://github.com/o/r/releases/download/$tag/$it"}"""
        }}]}
    """.trimIndent()

    @Test
    fun `reads the tag, versionCode and Fire TV APK`() {
        val info = ReleaseInfo.parse(release("v0.5.22", "hinds-tv-emulator.apk", "hinds-tv.apk"))!!
        assertEquals("v0.5.22", info.tag)
        assertEquals(22, info.versionCode)
        assertEquals("0.5.22", info.versionName)
        assertEquals("https://github.com/o/r/releases/download/v0.5.22/hinds-tv.apk", info.apkUrl)
    }

    @Test
    fun `no Fire TV APK means no update`() {
        assertNull(ReleaseInfo.parse(release("v0.5.22", "hinds-tv-emulator.apk")))
    }

    @Test
    fun `odd tags and bad JSON are ignored`() {
        assertNull(ReleaseInfo.parse(release("latest", "hinds-tv.apk")))
        assertNull(ReleaseInfo.parse("not json"))
        assertNull(ReleaseInfo.parse("""{"message":"Not Found"}"""))
    }
}
