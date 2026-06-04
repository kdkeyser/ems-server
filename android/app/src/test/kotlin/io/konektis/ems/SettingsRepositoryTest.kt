package io.konektis.ems

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.konektis.ems.data.settings.Settings
import io.konektis.ems.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsRepositoryTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val tmpFile = File.createTempFile("test_prefs", ".preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { tmpFile }
    )
    private val repo = SettingsRepository(dataStore)

    @Test
    fun `defaults match backend dev config`() = testScope.runTest {
        val s = repo.settingsFlow.first()
        assertEquals(SettingsRepository.DEFAULT_SERVER_URL, s.serverUrl)
        assertEquals(SettingsRepository.DEFAULT_USERNAME, s.username)
        assertEquals(SettingsRepository.DEFAULT_PASSWORD, s.password)
        assertFalse(s.useTls)                       // default off for LAN/dev
        assertEquals("", s.edgeKey)                 // no edge key by default
    }

    @Test
    fun `save and load round-trips`() = testScope.runTest {
        repo.save(
            Settings(
                serverUrl = "ems.kenas.be",
                username = "user",
                password = "pass",
                useTls = true,
                edgeKey = "edge-secret"
            )
        )
        val s = repo.settingsFlow.first()
        assertEquals("ems.kenas.be", s.serverUrl)
        assertEquals("user", s.username)
        assertEquals("pass", s.password)
        assertEquals(true, s.useTls)
        assertEquals("edge-secret", s.edgeKey)
    }

    @Test
    fun `settingsFlow emits updated value after save`() = testScope.runTest {
        repo.save(Settings("host1:8080", "", ""))
        repo.save(Settings("host2:8080", "", ""))
        assertEquals("host2:8080", repo.settingsFlow.first().serverUrl)
    }
}
