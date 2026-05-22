# Android EMS Companion App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin/Compose Android app that displays real-time EMS power topology and controls the car charger via WebSocket.

**Architecture:** Single `:app` module at `android/` in the repo root. Ktor client (OkHttp engine) for WebSockets, kotlin-inject for DI (same pattern as the server), DataStore Preferences for settings, Compose + Material3 for UI. Two screens: Dashboard (topology + charger control) and Settings (server address + credentials).

**Tech Stack:** Kotlin 2.2.20, AGP 8.7.3, Compose BOM 2025.05.00, Material3, Ktor 3.3.1, kotlinx.serialization 1.8.1, kotlin-inject 0.8.0 (KSP), DataStore Preferences 1.1.4, Compose Navigation 2.9.0, kotlinx-coroutines-test 1.10.2.

---

## File map

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/
        │   │   ├── values/colors.xml
        │   │   ├── values/strings.xml
        │   │   ├── drawable/ic_launcher_foreground.xml
        │   │   ├── mipmap-anydpi-v26/ic_launcher.xml
        │   │   └── mipmap-anydpi-v26/ic_launcher_round.xml
        │   └── kotlin/io/konektis/ems/
        │       ├── EmsApplication.kt
        │       ├── di/
        │       │   ├── AppScope.kt
        │       │   ├── AppModule.kt
        │       │   └── AppComponent.kt
        │       ├── data/
        │       │   ├── ConnectionState.kt
        │       │   ├── model/
        │       │   │   ├── StatusState.kt
        │       │   │   └── WsMessage.kt
        │       │   ├── settings/
        │       │   │   └── SettingsRepository.kt
        │       │   └── ws/
        │       │       ├── StatusWsClient.kt
        │       │       └── ControlWsClient.kt
        │       └── ui/
        │           ├── MainActivity.kt
        │           ├── NavHost.kt
        │           ├── components/
        │           │   ├── DeviceNode.kt
        │           │   └── TopologyView.kt
        │           ├── dashboard/
        │           │   ├── DashboardViewModel.kt
        │           │   └── DashboardScreen.kt
        │           └── settings/
        │               ├── SettingsViewModel.kt
        │               └── SettingsScreen.kt
        └── test/kotlin/io/konektis/ems/
            ├── StatusStateTest.kt
            ├── WsMessageTest.kt
            ├── SettingsRepositoryTest.kt
            ├── DashboardViewModelTest.kt
            └── SettingsViewModelTest.kt
```

---

### Task 1: Gradle project scaffold

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

- [ ] **Step 1: Create the directory tree**

```bash
mkdir -p android/gradle \
         android/app/src/main/kotlin/io/konektis/ems \
         android/app/src/main/res/values \
         android/app/src/main/res/drawable \
         android/app/src/main/res/mipmap-anydpi-v26 \
         android/app/src/test/kotlin/io/konektis/ems
```

- [ ] **Step 2: Create `android/settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ems-android"
include(":app")
```

- [ ] **Step 3: Create `android/gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.2.20"
ksp = "2.2.20-2.0.3"
compose-bom = "2025.05.00"
ktor = "3.3.1"
kotlin-inject = "0.8.0"
datastore = "1.1.4"
navigation = "2.9.0"
lifecycle = "2.9.0"
coroutines = "1.10.2"
kotlinx-serialization = "1.8.1"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-websockets = { group = "io.ktor", name = "ktor-client-websockets", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
kotlin-inject-runtime = { group = "me.tatarka.inject", name = "kotlin-inject-runtime", version.ref = "kotlin-inject" }
kotlin-inject-compiler = { group = "me.tatarka.inject", name = "kotlin-inject-compiler-ksp", version.ref = "kotlin-inject" }
kotlin-test = { group = "org.jetbrains.kotlin", name = "kotlin-test", version.ref = "kotlin" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 4: Create `android/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 5: Create `android/app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.konektis.ems"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.konektis.ems"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 6: Create `android/app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:name=".EmsApplication"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 7: Create resource files**

`android/app/src/main/res/values/colors.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1E3A5F</color>
</resources>
```

`android/app/src/main/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">EMS</string>
</resources>
```

`android/app/src/main/res/drawable/ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF"
        android:pathData="M57,12L36,60H54L51,96L72,48H54L57,12Z"/>
</vector>
```

`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

`android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

- [ ] **Step 8: Bootstrap the Gradle wrapper**

Run from `android/`:
```bash
cd android && gradle wrapper --gradle-version 8.10.2
```

Expected: `BUILD SUCCESSFUL`, `gradle/wrapper/` files created.

- [ ] **Step 9: Verify the project syncs**

```bash
cd android && ./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. There is no Kotlin source yet; the APK will be empty.

- [ ] **Step 10: Commit**

```bash
git add android/
git commit -m "feat(android): scaffold Gradle project with Compose + Ktor + kotlin-inject deps"
```

---

### Task 2: DI skeleton + EmsApplication

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/di/AppScope.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/di/AppModule.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/di/AppComponent.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/EmsApplication.kt`

Note: `AppModule` references `StatusWsClient` and `ControlWsClient` which don't exist yet. The file compiles once those classes are created in Task 5–6. The project will not compile cleanly until Task 6 is complete.

- [ ] **Step 1: Create `di/AppScope.kt`**

```kotlin
package io.konektis.ems.di

import me.tatarka.inject.annotations.Scope

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class AppScope
```

- [ ] **Step 2: Create `di/AppModule.kt`**

```kotlin
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
```

- [ ] **Step 3: Create `di/AppComponent.kt`**

```kotlin
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
```

- [ ] **Step 4: Create `EmsApplication.kt`**

```kotlin
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
```

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(android): add kotlin-inject DI skeleton — AppComponent, AppModule, AppScope, EmsApplication"
```

---

### Task 3: Data models + serialization tests

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/model/StatusState.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/model/WsMessage.kt`
- Create: `android/app/src/test/kotlin/io/konektis/ems/StatusStateTest.kt`
- Create: `android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt`

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/kotlin/io/konektis/ems/StatusStateTest.kt`:
```kotlin
package io.konektis.ems

import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.data.model.StatusState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusStateTest {

    @Test
    fun `DeviceHealth Online round-trips with type discriminator`() {
        val health = DeviceHealth.Online(lastSeenAt = 1748000000000L, powerW = 1800, extraInfo = "62% SoC")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"online\""))
        assertEquals(health, Json.decodeFromString<DeviceHealth>(json))
    }

    @Test
    fun `DeviceHealth Offline round-trips with type discriminator`() {
        val health = DeviceHealth.Offline(lastSeenAt = null, lastError = "Connection refused")
        val json = Json.encodeToString<DeviceHealth>(health)
        assertTrue(json.contains("\"type\":\"offline\""))
        assertEquals(health, Json.decodeFromString<DeviceHealth>(json))
    }

    @Test
    fun `StatusState full round-trip`() {
        val state = StatusState(
            devices = listOf(
                DeviceStatus("Grid meter", DeviceHealth.Online(1748000000000L, -800), "grid"),
                DeviceStatus("Webasto", DeviceHealth.Offline(null, "timeout"), "charger")
            ),
            totalSolarW = 3200, gridW = -800, batteryW = 200,
            batteryCharge = 62, chargerW = 0, heatpumpW = null
        )
        assertEquals(state, Json.decodeFromString<StatusState>(Json.encodeToString(state)))
    }
}
```

`android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt`:
```kotlin
package io.konektis.ems

import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class WsMessageTest {

    @Test
    fun `SetCharging NotCharging round-trips`() {
        val msg = ClientMessage.SetCharging(ChargingState.NotCharging())
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    @Test
    fun `SetCharging ChargingWithMaxPower round-trips`() {
        val msg = ClientMessage.SetCharging(ChargingState.ChargingWithMaxPower(maxPower = 7400u))
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    @Test
    fun `Authenticate round-trips`() {
        val msg = ClientMessage.Authenticate("user", "pass")
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -20
```

Expected: compilation failure — `Unresolved reference: DeviceHealth`.

- [ ] **Step 3: Create `data/model/StatusState.kt`**

```kotlin
package io.konektis.ems.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class DeviceHealth {
    @Serializable @SerialName("online")
    data class Online(
        val lastSeenAt: Long,
        val powerW: Int,
        val extraInfo: String? = null
    ) : DeviceHealth()

    @Serializable @SerialName("offline")
    data class Offline(
        val lastSeenAt: Long? = null,
        val lastError: String? = null
    ) : DeviceHealth()
}

@Serializable
data class DeviceStatus(
    val name: String,
    val health: DeviceHealth,
    val category: String
)

@Serializable
data class StatusState(
    val devices: List<DeviceStatus>,
    val totalSolarW: Int?,
    val gridW: Int?,
    val batteryW: Int?,
    val batteryCharge: Int?,
    val chargerW: Int?,
    val heatpumpW: Int?
)
```

- [ ] **Step 4: Create `data/model/WsMessage.kt`**

```kotlin
package io.konektis.ems.data.model

import kotlinx.serialization.Serializable

@Serializable
sealed class ChargingState {
    @Serializable class NotCharging : ChargingState()
    @Serializable class ChargingWithExcessPower : ChargingState()
    @Serializable data class ChargingWithMaxPower(val maxPower: UInt) : ChargingState()
}

@Serializable
enum class Devices { SOLAR, BATTERY, CAR_CHARGER, HEATPUMP, GRID }

@Serializable
data class Update(val device: Devices, val power: Int)

@Serializable
sealed class Message {
    @Serializable data class PowerUsageUpdate(val updates: List<Update>) : Message()
    @Serializable data class Authenticated(val username: String) : Message()
    @Serializable data class Unauthorized(val username: String) : Message()
}

@Serializable
sealed class ClientMessage {
    @Serializable data class SetCharging(val chargingState: ChargingState) : ClientMessage()
    @Serializable data class Authenticate(val username: String, val password: String) : ClientMessage()
}
```

- [ ] **Step 5: Run tests — expect PASS**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 6 tests passed.

- [ ] **Step 6: Commit**

```bash
git add android/
git commit -m "feat(android): add data models (StatusState, WsMessage) and serialization tests"
```

---

### Task 4: SettingsRepository + tests

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt`
- Create: `android/app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

`android/app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt`:
```kotlin
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

class SettingsRepositoryTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val tmpFile = File.createTempFile("test_prefs", ".preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = testScope,
        produceFile = { tmpFile }
    )
    private val repo = SettingsRepository(dataStore)

    @Test
    fun `defaults are empty strings`() = testScope.runTest {
        val s = repo.settingsFlow.first()
        assertEquals("", s.serverUrl)
        assertEquals("", s.username)
        assertEquals("", s.password)
    }

    @Test
    fun `save and load round-trips`() = testScope.runTest {
        repo.save(Settings("192.168.1.100:8080", "user", "pass"))
        val s = repo.settingsFlow.first()
        assertEquals("192.168.1.100:8080", s.serverUrl)
        assertEquals("user", s.username)
        assertEquals("pass", s.password)
    }

    @Test
    fun `settingsFlow emits updated value after save`() = testScope.runTest {
        repo.save(Settings("host1:8080", "", ""))
        repo.save(Settings("host2:8080", "", ""))
        assertEquals("host2:8080", repo.settingsFlow.first().serverUrl)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: compilation failure — `Unresolved reference: SettingsRepository`.

- [ ] **Step 3: Create `data/settings/SettingsRepository.kt`**

```kotlin
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
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 9 tests passed (6 from Task 3 + 3 new).

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(android): add SettingsRepository with DataStore and tests"
```

---

### Task 5: ConnectionState types + StatusWsClient

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/ConnectionState.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt`

No unit tests for WS clients (per spec — thin I/O wrappers, real WebSocket server required).

- [ ] **Step 1: Create `data/ConnectionState.kt`**

```kotlin
package io.konektis.ems.data

sealed class ConnectionState {
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Disconnected(val error: String? = null) : ConnectionState()
}

sealed class ControlState {
    object Connecting : ControlState()
    object Authenticated : ControlState()
    object Unauthenticated : ControlState()
    data class Disconnected(val error: String? = null) : ControlState()
}
```

- [ ] **Step 2: Create `data/ws/StatusWsClient.kt`**

```kotlin
package io.konektis.ems.data.ws

import io.konektis.ems.data.ConnectionState
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

class StatusWsClient(
    private val settings: SettingsRepository,
    private val client: HttpClient
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    val statusFlow: Flow<StatusState> = settings.settingsFlow.transformLatest { s ->
        if (s.serverUrl.isBlank()) {
            _connectionState.value = ConnectionState.Disconnected()
            return@transformLatest
        }
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _connectionState.value = ConnectionState.Connecting
            try {
                client.webSocket("ws://${s.serverUrl}/status-ws") {
                    attempt = 0
                    _connectionState.value = ConnectionState.Connected
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            emit(Json.decodeFromString<StatusState>(frame.readText()))
                        }
                    }
                    _connectionState.value = ConnectionState.Disconnected()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Disconnected(e.message)
            }
            delay(BACKOFF[minOf(attempt++, BACKOFF.size - 1)])
        }
    }

    companion object {
        private val BACKOFF = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/
git commit -m "feat(android): add ConnectionState types and StatusWsClient"
```

---

### Task 6: ControlWsClient

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`

- [ ] **Step 1: Create `data/ws/ControlWsClient.kt`**

```kotlin
package io.konektis.ems.data.ws

import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.Message
import io.konektis.ems.data.settings.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ControlWsClient(
    private val settings: SettingsRepository,
    private val client: HttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableStateFlow<ControlState>(ControlState.Connecting)
    val connectionState: StateFlow<ControlState> = _connectionState.asStateFlow()

    private val commandChannel = Channel<ClientMessage>(Channel.BUFFERED)

    init {
        scope.launch {
            settings.settingsFlow.collectLatest { s ->
                if (s.serverUrl.isBlank()) {
                    _connectionState.value = ControlState.Disconnected()
                    return@collectLatest
                }
                var attempt = 0
                while (currentCoroutineContext().isActive) {
                    _connectionState.value = ControlState.Connecting
                    try {
                        client.webSocket("ws://${s.serverUrl}/ws") {
                            attempt = 0
                            outgoing.send(Frame.Text(
                                Json.encodeToString<ClientMessage>(
                                    ClientMessage.Authenticate(s.username, s.password)
                                )
                            ))
                            val cmdJob = launch {
                                for (cmd in commandChannel) {
                                    outgoing.send(Frame.Text(Json.encodeToString<ClientMessage>(cmd)))
                                }
                            }
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    when (Json.decodeFromString<Message>(frame.readText())) {
                                        is Message.Authenticated ->
                                            _connectionState.value = ControlState.Authenticated
                                        is Message.Unauthorized -> {
                                            _connectionState.value = ControlState.Unauthenticated
                                            cmdJob.cancel()
                                            return@webSocket
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            cmdJob.cancel()
                            _connectionState.value = ControlState.Disconnected()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _connectionState.value = ControlState.Disconnected(e.message)
                    }
                    if (_connectionState.value is ControlState.Unauthenticated) break
                    delay(BACKOFF[minOf(attempt++, BACKOFF.size - 1)])
                }
            }
        }
    }

    suspend fun send(command: ClientMessage) {
        check(_connectionState.value is ControlState.Authenticated) { "Not authenticated" }
        commandChannel.send(command)
    }

    companion object {
        private val BACKOFF = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)
    }
}
```

- [ ] **Step 2: Verify full project compiles (DI now has all its dependencies)**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. KSP generates `AppComponent_Impl`.

- [ ] **Step 3: Run all tests to confirm nothing broke**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 9 tests passed.

- [ ] **Step 4: Commit**

```bash
git add android/
git commit -m "feat(android): add ControlWsClient with auth + SetCharging command dispatch"
```

---

### Task 7: DashboardViewModel + tests

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardViewModel.kt`
- Create: `android/app/src/test/kotlin/io/konektis/ems/DashboardViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/kotlin/io/konektis/ems/DashboardViewModelTest.kt`:
```kotlin
package io.konektis.ems

import io.konektis.ems.data.ConnectionState
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.DeviceHealth
import io.konektis.ems.data.model.DeviceStatus
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardViewModelTest {

    private fun makeState(gridW: Int) = StatusState(
        devices = listOf(DeviceStatus("Grid meter", DeviceHealth.Online(0L, gridW), "grid")),
        totalSolarW = null, gridW = gridW, batteryW = null,
        batteryCharge = null, chargerW = null, heatpumpW = null
    )

    @Test
    fun `statusState is null before first emission`() = runTest {
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = MutableStateFlow(ControlState.Connecting),
            sendCommand = {}
        )
        assertNull(vm.statusState.value)
    }

    @Test
    fun `statusState updates when flow emits`() = runTest {
        val state = makeState(-800)
        val vm = DashboardViewModel(
            statusFlow = flowOf(state),
            controlState = MutableStateFlow(ControlState.Connecting),
            sendCommand = {}
        )
        // collect in background to trigger stateIn
        val job = kotlinx.coroutines.launch { vm.statusState.collect {} }
        kotlinx.coroutines.yield()
        assertEquals(state, vm.statusState.value)
        job.cancel()
    }

    @Test
    fun `charger control visible only when Authenticated`() = runTest {
        val controlState = MutableStateFlow<ControlState>(ControlState.Connecting)
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = controlState,
            sendCommand = {}
        )
        assertTrue(!vm.chargerControlVisible.value)
        controlState.value = ControlState.Authenticated
        assertTrue(vm.chargerControlVisible.value)
        controlState.value = ControlState.Unauthenticated
        assertTrue(!vm.chargerControlVisible.value)
    }

    @Test
    fun `setCharging forwards command to sendCommand`() = runTest {
        val sent = mutableListOf<ClientMessage>()
        val vm = DashboardViewModel(
            statusFlow = flowOf(),
            controlState = MutableStateFlow(ControlState.Authenticated),
            sendCommand = { sent.add(it) }
        )
        vm.setCharging(ChargingState.NotCharging())
        assertEquals(1, sent.size)
        assertEquals(
            ClientMessage.SetCharging(ChargingState.NotCharging()),
            sent.first()
        )
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: compilation failure — `Unresolved reference: DashboardViewModel`.

- [ ] **Step 3: Create `ui/dashboard/DashboardViewModel.kt`**

```kotlin
package io.konektis.ems.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.data.model.ClientMessage
import io.konektis.ems.data.model.StatusState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(
    statusFlow: Flow<StatusState>,
    val controlState: StateFlow<ControlState>,
    private val sendCommand: suspend (ClientMessage) -> Unit
) : ViewModel() {

    val statusState: StateFlow<StatusState?> = statusFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    val chargerControlVisible: StateFlow<Boolean> = controlState
        .map { it is ControlState.Authenticated }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCharging(state: ChargingState) {
        viewModelScope.launch {
            sendCommand(ClientMessage.SetCharging(state))
        }
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 13 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(android): add DashboardViewModel with statusState, chargerControlVisible, setCharging"
```

---

### Task 8: SettingsViewModel + tests

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsViewModel.kt`
- Create: `android/app/src/test/kotlin/io/konektis/ems/SettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/kotlin/io/konektis/ems/SettingsViewModelTest.kt`:
```kotlin
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
```

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: compilation failure — `Unresolved reference: SettingsViewModel`.

- [ ] **Step 3: Create `ui/settings/SettingsViewModel.kt`**

```kotlin
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
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 15 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android/
git commit -m "feat(android): add SettingsViewModel and tests"
```

---

### Task 9: TopologyView + DeviceNode composables

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/components/DeviceNode.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/components/TopologyView.kt`

No unit tests — UI composables are verified visually in Task 12.

- [ ] **Step 1: Create `ui/components/DeviceNode.kt`**

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.model.DeviceStatus

val colorGreen = Color(0xFF34D399)
val colorRed   = Color(0xFFF87171)
val colorGray  = Color(0xFF9CA3AF)

fun powerColor(w: Int?, positiveIsConsumption: Boolean): Color {
    if (w == null || w == 0) return colorGray
    return if (positiveIsConsumption == (w > 0)) colorRed else colorGreen
}

fun fmtWatts(w: Int?): String {
    if (w == null) return "—"
    val a = Math.abs(w)
    return if (a >= 1000) "${"%.1f".format(a / 1000f)} kW" else "$a W"
}

@Composable
fun DeviceNode(
    icon: String,
    label: String,
    powerW: Int?,
    positiveIsConsumption: Boolean,
    devices: List<DeviceStatus>,
    extraText: String? = null,
    isHouse: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isHouse) Color(0xFF3B82F6) else Color(0xFF4B5563)
    val bgColor = if (isHouse) Color(0xFF1E3A5F) else Color(0xFF374151)

    Column(
        modifier = modifier
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = if (isHouse) 14.dp else 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(icon, fontSize = 22.sp)
        Text(label, fontSize = 10.sp, color = Color(0xFF9CA3AF))
        if (!isHouse) {
            Text(
                fmtWatts(powerW),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = powerColor(powerW, positiveIsConsumption)
            )
            if (extraText != null) {
                Text(extraText, fontSize = 10.sp, color = Color(0xFF9CA3AF))
            }
            if (devices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    devices.forEach { d ->
                        val dot = if (d.health is io.konektis.ems.data.model.DeviceHealth.Online)
                            Color(0xFF4ADE80) else colorRed
                        Text("●", fontSize = 8.sp, color = dot)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create `ui/components/TopologyView.kt`**

```kotlin
package io.konektis.ems.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.konektis.ems.data.model.StatusState

fun lineColor(w: Int?, positiveIsConsumption: Boolean): Color {
    if (w == null || w == 0) return Color(0xFF4B5563)
    return if (positiveIsConsumption == (w > 0)) colorRed else colorGreen
}

// Positions as fraction [0,1] of the container. House is always at (0.5, 0.5).
private data class NodePos(val x: Float, val y: Float) {
    fun toBias() = BiasAlignment(x * 2f - 1f, y * 2f - 1f)
}
private val POS_SOLAR    = NodePos(0.50f, 0.14f)
private val POS_GRID     = NodePos(0.12f, 0.50f)
private val POS_HOUSE    = NodePos(0.50f, 0.50f)
private val POS_BATTERY  = NodePos(0.88f, 0.50f)
private val POS_CHARGER  = NodePos(0.30f, 0.84f)
private val POS_HEATPUMP = NodePos(0.70f, 0.84f)

@Composable
fun TopologyView(state: StatusState?, modifier: Modifier = Modifier) {
    val cats = state?.devices?.groupBy { it.category } ?: emptyMap()

    Box(modifier = modifier.fillMaxWidth().aspectRatio(5f / 3f)) {

        // SVG-style lines drawn on a Canvas filling the box
        Canvas(modifier = Modifier.matchParentSize()) {
            val hw = size.width * POS_HOUSE.x
            val hy = size.height * POS_HOUSE.y
            fun line(pos: NodePos, color: Color) = drawLine(
                color = color,
                start = Offset(size.width * pos.x, size.height * pos.y),
                end   = Offset(hw, hy),
                strokeWidth = 2.dp.toPx()
            )
            if (cats["solar"]    != null) line(POS_SOLAR,    lineColor(state?.totalSolarW, false))
            if (cats["grid"]     != null) line(POS_GRID,     lineColor(state?.gridW,       true))
            if (cats["battery"]  != null) line(POS_BATTERY,  lineColor(state?.batteryW,    true))
            if (cats["charger"]  != null) line(POS_CHARGER,  lineColor(state?.chargerW,    true))
            if (cats["heatpump"] != null) line(POS_HEATPUMP, lineColor(state?.heatpumpW,   true))
        }

        // House — always visible
        DeviceNode(
            icon = "🏠", label = "House", powerW = null,
            positiveIsConsumption = true, devices = emptyList(), isHouse = true,
            modifier = Modifier.align(POS_HOUSE.toBias())
        )

        if (cats["solar"] != null) DeviceNode(
            icon = "☀️", label = "Solar",
            powerW = state?.totalSolarW, positiveIsConsumption = false,
            devices = cats["solar"]!!, modifier = Modifier.align(POS_SOLAR.toBias())
        )
        if (cats["grid"] != null) DeviceNode(
            icon = "🔌", label = "Grid",
            powerW = state?.gridW, positiveIsConsumption = true,
            devices = cats["grid"]!!, modifier = Modifier.align(POS_GRID.toBias())
        )
        if (cats["battery"] != null) DeviceNode(
            icon = "🔋", label = "Battery",
            powerW = state?.batteryW, positiveIsConsumption = true,
            devices = cats["battery"]!!,
            extraText = state?.batteryCharge?.let { "$it% SoC" },
            modifier = Modifier.align(POS_BATTERY.toBias())
        )
        if (cats["charger"] != null) DeviceNode(
            icon = "🚗", label = "Charger",
            powerW = state?.chargerW, positiveIsConsumption = true,
            devices = cats["charger"]!!, modifier = Modifier.align(POS_CHARGER.toBias())
        )
        if (cats["heatpump"] != null) DeviceNode(
            icon = "🌡️", label = "Heat Pump",
            powerW = state?.heatpumpW, positiveIsConsumption = true,
            devices = cats["heatpump"]!!, modifier = Modifier.align(POS_HEATPUMP.toBias())
        )
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add android/
git commit -m "feat(android): add TopologyView and DeviceNode composables"
```

---

### Task 10: DashboardScreen

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt`

- [ ] **Step 1: Create `ui/dashboard/DashboardScreen.kt`**

```kotlin
package io.konektis.ems.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ConnectionState
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargingState
import io.konektis.ems.ui.components.TopologyView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    connectionState: ConnectionState,
    onSettingsClick: () -> Unit
) {
    val status by vm.statusState.collectAsState()
    val controlVisible by vm.chargerControlVisible.collectAsState()
    var selectedMode by remember { mutableIntStateOf(0) }
    var maxPowerText by remember { mutableStateOf("7400") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("EMS") },
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        )

        // Connection status pill
        val (pillColor, pillText) = when (connectionState) {
            is ConnectionState.Connected    -> Color(0xFF34D399) to "Connected"
            is ConnectionState.Connecting   -> Color(0xFFFBBF24) to "Connecting…"
            is ConnectionState.Disconnected ->
                Color(0xFFF87171) to "Disconnected${connectionState.error?.let { " — $it" } ?: ""}"
        }
        Surface(
            color = pillColor.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(pillText, color = pillColor, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }

        TopologyView(
            state = status,
            modifier = Modifier.padding(16.dp)
        )

        if (controlVisible) {
            ChargerControl(
                selectedMode = selectedMode,
                maxPowerText = maxPowerText,
                onModeChange = { idx ->
                    selectedMode = idx
                    val state = when (idx) {
                        0 -> ChargingState.NotCharging()
                        1 -> ChargingState.ChargingWithExcessPower()
                        2 -> ChargingState.ChargingWithMaxPower(maxPowerText.toUIntOrNull() ?: 7400u)
                        else -> ChargingState.NotCharging()
                    }
                    vm.setCharging(state)
                },
                onMaxPowerChange = { maxPowerText = it }
            )
        } else if (vm.controlState.collectAsState().value is ControlState.Unauthenticated) {
            Text(
                "Charger control unavailable — check credentials in Settings.",
                fontSize = 12.sp, color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargerControl(
    selectedMode: Int,
    maxPowerText: String,
    onModeChange: (Int) -> Unit,
    onMaxPowerChange: (String) -> Unit
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Charger", fontSize = 12.sp, color = Color(0xFF9CA3AF))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Off", "Solar surplus", "Max power").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = selectedMode == i,
                    onClick = { onModeChange(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3)
                ) { Text(label, fontSize = 12.sp) }
            }
        }
        if (selectedMode == 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxPowerText,
                    onValueChange = onMaxPowerChange,
                    label = { Text("Max watts") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/
git commit -m "feat(android): add DashboardScreen with topology view and charger control"
```

---

### Task 11: SettingsScreen

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Create `ui/settings/SettingsScreen.kt`**

```kotlin
package io.konektis.ems.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.konektis.ems.data.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val saved by vm.settingsFlow.collectAsState(initial = Settings())
    var serverUrl by rememberSaveable(saved.serverUrl) { mutableStateOf(saved.serverUrl) }
    var username  by rememberSaveable(saved.username)  { mutableStateOf(saved.username) }
    var password  by rememberSaveable(saved.password)  { mutableStateOf(saved.password) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.100:8080") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    vm.save(Settings(serverUrl.trim(), username.trim(), password))
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd android && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add android/
git commit -m "feat(android): add SettingsScreen"
```

---

### Task 12: MainActivity + NavHost — wire everything together

**Files:**
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/MainActivity.kt`
- Create: `android/app/src/main/kotlin/io/konektis/ems/ui/NavHost.kt`

- [ ] **Step 1: Create `ui/NavHost.kt`**

```kotlin
package io.konektis.ems.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.konektis.ems.EmsApplication
import io.konektis.ems.ui.dashboard.DashboardScreen
import io.konektis.ems.ui.dashboard.DashboardViewModel
import io.konektis.ems.ui.settings.SettingsScreen
import io.konektis.ems.ui.settings.SettingsViewModel

@Composable
fun EmsNavHost(app: EmsApplication) {
    val navController = rememberNavController()

    val dashboardVm: DashboardViewModel = viewModel(factory = androidx.lifecycle.viewmodel.compose.viewModel<DashboardViewModel>(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DashboardViewModel(
                    statusFlow    = app.component.statusWsClient.statusFlow,
                    controlState  = app.component.controlWsClient.connectionState,
                    sendCommand   = { app.component.controlWsClient.send(it) }
                ) as T
            }
        }
    ).javaClass.let { viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(
                statusFlow   = app.component.statusWsClient.statusFlow,
                controlState = app.component.controlWsClient.connectionState,
                sendCommand  = { app.component.controlWsClient.send(it) }
            ) as T
        }
    }) })

    val settingsVm: SettingsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(app.component.settingsRepository) as T
        }
    })

    val connectionState by app.component.statusWsClient.connectionState.collectAsState()

    NavHost(navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                vm = dashboardVm,
                connectionState = connectionState,
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                vm = settingsVm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
```

- [ ] **Step 2: Simplify NavHost.kt — the above has a duplicate factory. Replace with the clean version**

```kotlin
package io.konektis.ems.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.konektis.ems.EmsApplication
import io.konektis.ems.ui.dashboard.DashboardScreen
import io.konektis.ems.ui.dashboard.DashboardViewModel
import io.konektis.ems.ui.settings.SettingsScreen
import io.konektis.ems.ui.settings.SettingsViewModel

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) =
    object : ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }
    }

@Composable
fun EmsNavHost(app: EmsApplication) {
    val navController = rememberNavController()

    val dashboardVm: DashboardViewModel = viewModel(factory = factory {
        DashboardViewModel(
            statusFlow   = app.component.statusWsClient.statusFlow,
            controlState = app.component.controlWsClient.connectionState,
            sendCommand  = { app.component.controlWsClient.send(it) }
        )
    })

    val settingsVm: SettingsViewModel = viewModel(factory = factory {
        SettingsViewModel(app.component.settingsRepository)
    })

    val connectionState by app.component.statusWsClient.connectionState.collectAsState()

    NavHost(navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                vm = dashboardVm,
                connectionState = connectionState,
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(vm = settingsVm, onBack = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 3: Create `ui/MainActivity.kt`**

```kotlin
package io.konektis.ems.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import io.konektis.ems.EmsApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as EmsApplication
        setContent {
            MaterialTheme {
                EmsNavHost(app)
            }
        }
    }
}
```

- [ ] **Step 4: Build the debug APK**

```bash
cd android && ./gradlew assembleDebug 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Run all tests one final time**

```bash
cd android && ./gradlew :app:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 15 tests passed.

- [ ] **Step 6: Commit**

```bash
git add android/
git commit -m "feat(android): wire MainActivity + NavHost — app is runnable end-to-end"
```

---

## Self-review

**Spec coverage:**
- ✅ Read-only monitoring via `/status-ws` → `StatusWsClient.statusFlow` → `DashboardViewModel.statusState` → `TopologyView`
- ✅ Charger control via `/ws` → `ControlWsClient` → `DashboardViewModel.setCharging()` → `ChargerControl`
- ✅ Single server, configured in Settings screen (serverUrl, username, password)
- ✅ Auth: username + password sent on connect to `/ws`; Unauthenticated hides charger card
- ✅ Exponential backoff reconnect in both WS clients
- ✅ Settings change triggers reconnect via `collectLatest` / `transformLatest`
- ✅ kotlin-inject DI matching server pattern
- ✅ Model classes are exact serialization copies of server types
- ✅ Serialization tests, SettingsRepository tests, ViewModel tests

**Placeholder scan:** No TBDs or incomplete steps found.

**Type consistency:**
- `DashboardViewModel` constructor (`statusFlow`, `controlState`, `sendCommand`) matches usage in `NavHost.kt` ✅
- `StatusWsClient.statusFlow: Flow<StatusState>` matches `DashboardViewModel` parameter ✅
- `ControlWsClient.connectionState: StateFlow<ControlState>` matches `DashboardViewModel` parameter ✅
- `ControlWsClient.send(ClientMessage)` matches `sendCommand` lambda signature ✅
- `SettingsRepository(DataStore<Preferences>)` secondary constructor used in tests ✅
- `DeviceStatus(name, health, category)` consistent across models and tests ✅
