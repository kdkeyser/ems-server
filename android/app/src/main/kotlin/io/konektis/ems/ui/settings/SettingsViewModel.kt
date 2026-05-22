package io.konektis.ems.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.konektis.ems.data.settings.Settings
import io.konektis.ems.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {

    val settingsFlow: Flow<Settings> = repo.settingsFlow

    fun save(settings: Settings) {
        viewModelScope.launch { repo.save(settings) }
    }
}
