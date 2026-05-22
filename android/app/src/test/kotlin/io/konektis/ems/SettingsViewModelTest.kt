package io.konektis.ems

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.konektis.ems.data.settings.Settings
import io.konektis.ems.data.settings.SettingsRepository
import io.konektis.ems.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { File.createTempFile("vm_test_prefs", ".preferences_pb") }
    )
    private val repo = SettingsRepository(dataStore)

    @Test
    fun `current settings loaded from repository on init`() = testScope.runTest {
        repo.save(Settings("192.168.1.1:8080", "admin", "secret"))
        val vm = SettingsViewModel(repo)
        val s = vm.settingsFlow.first()
        assertEquals("192.168.1.1:8080", s.serverUrl)
        assertEquals("admin", s.username)
    }

    @Test
    fun `save persists to repository`() = testScope.runTest {
        val vm = SettingsViewModel(repo)
        vm.save(Settings("10.0.0.1:8080", "user", "pass"))
        assertEquals("10.0.0.1:8080", repo.settingsFlow.first().serverUrl)
    }
}
