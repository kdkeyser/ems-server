package io.konektis.ems.di

import android.content.Context
import io.konektis.ems.data.settings.SettingsRepository
import io.konektis.ems.data.ws.ControlWsClient
import io.konektis.ems.data.ws.StatusWsClient
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@AppScope
@Component
abstract class AppComponent(
    @get:Provides val context: Context
) : AppModule {
    abstract val settingsRepository: SettingsRepository
    abstract val statusWsClient: StatusWsClient
    abstract val controlWsClient: ControlWsClient

    companion object
}
