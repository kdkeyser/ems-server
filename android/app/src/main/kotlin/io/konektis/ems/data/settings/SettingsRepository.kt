package io.konektis.ems.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = ""
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("ems_settings")

class SettingsRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    val settingsFlow: Flow<Settings> = store.data.map { prefs ->
        Settings(
            serverUrl = prefs[KEY_SERVER_URL] ?: "",
            username  = prefs[KEY_USERNAME]   ?: "",
            password  = prefs[KEY_PASSWORD]   ?: ""
        )
    }

    suspend fun save(settings: Settings) {
        store.edit { prefs ->
            prefs[KEY_SERVER_URL] = settings.serverUrl
            prefs[KEY_USERNAME]   = settings.username
            prefs[KEY_PASSWORD]   = settings.password
        }
    }

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_USERNAME   = stringPreferencesKey("username")
        private val KEY_PASSWORD   = stringPreferencesKey("password")
    }
}
