package io.konektis.ems

import android.app.Application
import io.konektis.ems.di.AppComponent
import io.konektis.ems.di.create

class EmsApplication : Application() {
    lateinit var component: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        component = AppComponent::class.create(this)
    }
}
