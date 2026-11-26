package com.techwin.statushd.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {

    @Test
    fun resolveDarkTheme_systemFollowsSystemIsDark() {
        assertFalse(resolveDarkTheme(AppThemeMode.SYSTEM, systemIsDark = false))
        assertTrue(resolveDarkTheme(AppThemeMode.SYSTEM, systemIsDark = true))
    }

    @Test
    fun resolveDarkTheme_lightAlwaysReturnsFalse() {
        assertFalse(resolveDarkTheme(AppThemeMode.LIGHT, systemIsDark = false))
        assertFalse(resolveDarkTheme(AppThemeMode.LIGHT, systemIsDark = true))
    }

    @Test
    fun resolveDarkTheme_darkAlwaysReturnsTrue() {
        assertTrue(resolveDarkTheme(AppThemeMode.DARK, systemIsDark = false))
        assertTrue(resolveDarkTheme(AppThemeMode.DARK, systemIsDark = true))
    }

    @Test
    fun storageValues_areStableAndDoNotUseDisplayStrings() {
        assertEquals("system", AppThemeMode.SYSTEM.storageValue)
        assertEquals("light", AppThemeMode.LIGHT.storageValue)
        assertEquals("dark", AppThemeMode.DARK.storageValue)
    }

    @Test
    fun fromStorageValue_mapsValidAndUnknownValuesCorrectly() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageValue("system"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStorageValue("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorageValue("dark"))

        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageValue(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageValue(""))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageValue("System default"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageValue("LIGHT"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromStorageValue("corrupt_value"))
    }

    @Test
    fun nextThemeMode_cyclesSystemLightDarkSystem() {
        assertEquals(AppThemeMode.LIGHT, nextThemeMode(AppThemeMode.SYSTEM))
        assertEquals(AppThemeMode.DARK, nextThemeMode(AppThemeMode.LIGHT))
        assertEquals(AppThemeMode.SYSTEM, nextThemeMode(AppThemeMode.DARK))
    }
}
