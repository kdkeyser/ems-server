package io.konektis.ems.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val apiKey: String = ""
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("ems_settings")

class SettingsRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    val settingsFlow: Flow<Settings> = store.data.map { prefs ->
        Settings(
            serverUrl = prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL,
            username  = prefs[KEY_USERNAME]   ?: DEFAULT_USERNAME,
            password  = prefs[KEY_PASSWORD]   ?: DEFAULT_PASSWORD,
            useTls    = prefs[KEY_USE_TLS]    ?: DEFAULT_USE_TLS,
            apiKey    = prefs[KEY_API_KEY]    ?: DEFAULT_API_KEY
        )
    }

    suspend fun save(settings: Settings) {
        store.edit { prefs ->
            prefs[KEY_SERVER_URL] = settings.serverUrl
            prefs[KEY_USERNAME]   = settings.username
            prefs[KEY_PASSWORD]   = settings.password
            prefs[KEY_USE_TLS]    = settings.useTls
            prefs[KEY_API_KEY]    = settings.apiKey
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "10.0.2.2:8080"
        const val DEFAULT_USERNAME   = "user"
        const val DEFAULT_PASSWORD   = "password"
        const val DEFAULT_USE_TLS    = false
        const val DEFAULT_API_KEY    = ""

        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_USERNAME   = stringPreferencesKey("username")
        private val KEY_PASSWORD   = stringPreferencesKey("password")
        private val KEY_USE_TLS    = booleanPreferencesKey("use_tls")
        private val KEY_API_KEY    = stringPreferencesKey("api_key")
    }
}
