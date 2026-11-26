package com.techwin.statushd.ui.theme

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemePreferenceRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun defaultValue_isSystem() = runBlocking {
        val file = File(tempFolder.root, "default.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { file }
        )
        val repository = ThemePreferenceRepository(dataStore)

        assertEquals(AppThemeMode.SYSTEM, repository.themeMode.first())
    }

    @Test
    fun setThemeMode_persistsAndDeserializesEachEnumValue() = runBlocking {
        val file = File(tempFolder.root, "roundtrip.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { file }
        )
        val repository = ThemePreferenceRepository(dataStore)

        for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.SYSTEM)) {
            repository.setThemeMode(mode)
            assertEquals(mode, repository.themeMode.first())

            val rawPrefs = dataStore.data.first()
            assertEquals(mode.storageValue, rawPrefs[ThemePreferenceRepository.THEME_MODE_KEY])
        }
    }

    @Test
    fun unknownStoredValue_fallsBackToSystem() = runBlocking {
        val file = File(tempFolder.root, "unknown.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { file }
        )
        dataStore.edit { prefs ->
            prefs[ThemePreferenceRepository.THEME_MODE_KEY] = "unexpected_mode_value"
        }
        val repository = ThemePreferenceRepository(dataStore)

        assertEquals(AppThemeMode.SYSTEM, repository.themeMode.first())
    }

    @Test
    fun corruptDataStoreFile_fallsBackToSystem() = runBlocking {
        val file = File(tempFolder.root, "corrupt.preferences_pb")
        file.writeBytes(byteArrayOf(0x00, 0x7F, 0x12, 0x34, 0x56))

        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = testScope,
            produceFile = { file }
        )
        val repository = ThemePreferenceRepository(dataStore)

        assertEquals(AppThemeMode.SYSTEM, repository.themeMode.first())
    }
}
