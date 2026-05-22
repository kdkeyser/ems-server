package io.konektis.ems.di

import android.content.Context
import io.konektis.ems.data.settings.SettingsRepository
import io.konektis.ems.data.ws.ControlWsClient
import io.konektis.ems.data.ws.StatusWsClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import me.tatarka.inject.annotations.Provides

interface AppModule {

    @AppScope
    @Provides
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(WebSockets)
    }

    @AppScope
    @Provides
    fun provideSettingsRepository(context: Context): SettingsRepository =
        SettingsRepository(context)

    @AppScope
    @Provides
    fun provideStatusWsClient(
        settings: SettingsRepository,
        client: HttpClient
    ): StatusWsClient = StatusWsClient(settings, client)

    @AppScope
    @Provides
    fun provideControlWsClient(
        settings: SettingsRepository,
        client: HttpClient
    ): ControlWsClient = ControlWsClient(settings, client)
}
