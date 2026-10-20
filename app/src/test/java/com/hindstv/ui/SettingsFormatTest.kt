package com.hindstv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFormatTest {
    @Test
    fun `MACs are masked in the middle`() {
        assertEquals("00:1A:79:••:••:EF", SettingsViewModel.maskMac("00:1A:79:12:34:EF"))
        assertEquals("••:••:••:••:••:••", SettingsViewModel.maskMac("garbage"))
    }

    @Test
    fun `only the host of the server is shown`() {
        assertEquals("portal.example.com:8080", SettingsViewModel.hostOf("http://portal.example.com:8080/c/?mac=00:1A:79:00:00:01"))
        assertEquals("lists.example.org", SettingsViewModel.hostOf("https://user:secret@lists.example.org/get.php?username=a&password=b"))
    }
}
