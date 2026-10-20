package com.hindstv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `dark and light ignore the time`() {
        (0..23).forEach { hour ->
            assertFalse(ThemeMode.Dark.isLight(hour))
            assertTrue(ThemeMode.Light.isLight(hour))
        }
    }

    @Test
    fun `automatic is light from 07 00 to 19 00`() {
        assertFalse(ThemeMode.Auto.isLight(6))
        assertTrue(ThemeMode.Auto.isLight(7))
        assertTrue(ThemeMode.Auto.isLight(18))
        assertFalse(ThemeMode.Auto.isLight(19))
        assertFalse(ThemeMode.Auto.isLight(0))
    }

    @Test
    fun `unknown or missing stored values fall back to dark`() {
        assertEquals(ThemeMode.Dark, ThemeMode.parse(null))
        assertEquals(ThemeMode.Dark, ThemeMode.parse("Sepia"))
        assertEquals(ThemeMode.Auto, ThemeMode.parse("Auto"))
    }
}
