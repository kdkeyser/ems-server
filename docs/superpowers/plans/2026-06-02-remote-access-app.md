# Remote Access (Android App) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the phone app reach the EMS server remotely over `wss://ec29.ems.konektis.io` by adding a "Use TLS" toggle and an API-key field to Settings, building the WebSocket URL scheme accordingly, and sending the API key as an `Authorization: Bearer` header on both WebSocket handshakes.

**Architecture:** Two new persisted settings (`useTls`, `apiKey`) drive a pure URL-builder helper used by both WS clients; each client passes the API key via the Ktor `client.webSocket(request = { ... })` builder. `ControlWsClient` keeps sending its app-layer `Authenticate` frame; `StatusWsClient` gains no app-layer auth (the server's `/status-ws` is unauthenticated and gated remotely by the edge header).

**Tech Stack:** Android / Kotlin, Jetpack Compose (Material3), DataStore Preferences, Ktor client 3.3.1 (OkHttp engine + WebSockets), kotlin-inject, kotlinx.coroutines test.

**Spec:** `docs/superpowers/specs/2026-06-02-remote-access-netbird-design.md`

**Repo:** the Android app lives under `android/` (paths below are relative to `android/`).

---

## File Structure

- **Modify** `app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt` — add `useTls` + `apiKey` to `Settings`, with DataStore keys/defaults/save/load.
- **Create** `app/src/main/kotlin/io/konektis/ems/data/ws/WsUrl.kt` — pure helper that builds the `ws://`/`wss://` URL from `serverUrl` + `useTls` + `path`.
- **Create** `app/src/test/kotlin/io/konektis/ems/WsUrlTest.kt` — unit tests for the helper (pure JVM, no Android deps).
- **Modify** `app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt` — use `WsUrl` + send the API-key header; keep the `Authenticate` frame.
- **Modify** `app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt` — use `WsUrl` + send the API-key header; no app-layer auth.
- **Modify** `app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt` — add the API-key field + TLS switch; update the `save(...)` call.
- **Modify** `app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt` — extend for the new fields.

---

## Task 1: Add `useTls` + `apiKey` to Settings (persisted)

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt`
- Test: `app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Replace the contents of `app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt` with the version below. It updates the existing round-trip tests to include the new fields and adds defaults coverage for them.

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
        assertFalse(s.useTls)            // default off for LAN/dev
        assertEquals("", s.apiKey)       // no key by default
    }

    @Test
    fun `save and load round-trips`() = testScope.runTest {
        repo.save(
            Settings(
                serverUrl = "ec29.ems.konektis.io",
                username = "user",
                password = "pass",
                useTls = true,
                apiKey = "secret-key"
            )
        )
        val s = repo.settingsFlow.first()
        assertEquals("ec29.ems.konektis.io", s.serverUrl)
        assertEquals("user", s.username)
        assertEquals("pass", s.password)
        assertEquals(true, s.useTls)
        assertEquals("secret-key", s.apiKey)
    }

    @Test
    fun `settingsFlow emits updated value after save`() = testScope.runTest {
        repo.save(Settings("host1:8080", "", ""))
        repo.save(Settings("host2:8080", "", ""))
        assertEquals("host2:8080", repo.settingsFlow.first().serverUrl)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'io.konektis.ems.SettingsRepositoryTest'`
Expected: FAIL to compile — `Settings` has no `useTls`/`apiKey` parameters (`unresolved reference: useTls`).

- [ ] **Step 3: Add the fields to `Settings` and the repository**

In `app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt`:

Extend the `Settings` data class:

```kotlin
data class Settings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val apiKey: String = ""
)
```

Add the new imports near the existing DataStore imports:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
```

Update `settingsFlow` to read the new keys:

```kotlin
    val settingsFlow: Flow<Settings> = store.data.map { prefs ->
        Settings(
            serverUrl = prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL,
            username  = prefs[KEY_USERNAME]   ?: DEFAULT_USERNAME,
            password  = prefs[KEY_PASSWORD]   ?: DEFAULT_PASSWORD,
            useTls    = prefs[KEY_USE_TLS]    ?: DEFAULT_USE_TLS,
            apiKey    = prefs[KEY_API_KEY]    ?: DEFAULT_API_KEY
        )
    }
```

Update `save`:

```kotlin
    suspend fun save(settings: Settings) {
        store.edit { prefs ->
            prefs[KEY_SERVER_URL] = settings.serverUrl
            prefs[KEY_USERNAME]   = settings.username
            prefs[KEY_PASSWORD]   = settings.password
            prefs[KEY_USE_TLS]    = settings.useTls
            prefs[KEY_API_KEY]    = settings.apiKey
        }
    }
```

Update the `companion object` to add defaults + keys:

```kotlin
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'io.konektis.ems.SettingsRepositoryTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/konektis/ems/data/settings/SettingsRepository.kt app/src/test/kotlin/io/konektis/ems/SettingsRepositoryTest.kt
git commit -m "feat(settings): add useTls + apiKey settings for remote access"
```

---

## Task 2: WebSocket URL builder helper

**Files:**
- Create: `app/src/main/kotlin/io/konektis/ems/data/ws/WsUrl.kt`
- Test: `app/src/test/kotlin/io/konektis/ems/WsUrlTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/io/konektis/ems/WsUrlTest.kt`:

```kotlin
package io.konektis.ems

import io.konektis.ems.data.ws.wsUrl
import kotlin.test.Test
import kotlin.test.assertEquals

class WsUrlTest {
    @Test
    fun `plain ws for LAN host with port`() {
        assertEquals("ws://10.0.2.2:8080/ws", wsUrl("10.0.2.2:8080", useTls = false, path = "/ws"))
    }

    @Test
    fun `wss for remote host without port`() {
        assertEquals(
            "wss://ec29.ems.konektis.io/status-ws",
            wsUrl("ec29.ems.konektis.io", useTls = true, path = "/status-ws")
        )
    }

    @Test
    fun `strips any scheme already present in serverUrl`() {
        assertEquals("wss://host/ws", wsUrl("https://host", useTls = true, path = "/ws"))
        assertEquals("ws://host:8080/ws", wsUrl("ws://host:8080", useTls = false, path = "/ws"))
    }

    @Test
    fun `trims trailing slash on host`() {
        assertEquals("wss://host/ws", wsUrl("host/", useTls = true, path = "/ws"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'io.konektis.ems.WsUrlTest'`
Expected: FAIL to compile — `unresolved reference: wsUrl`.

- [ ] **Step 3: Implement the helper**

Create `app/src/main/kotlin/io/konektis/ems/data/ws/WsUrl.kt`:

```kotlin
package io.konektis.ems.data.ws

/**
 * Builds a WebSocket URL from a user-entered server address.
 *
 * Accepts a bare `host` or `host:port` (the remote case is a bare host like
 * `ec29.ems.konektis.io`; the LAN case includes a port). Any scheme the user typed
 * (`http(s)://`, `ws(s)://`) is stripped and replaced based on [useTls].
 */
internal fun wsUrl(serverUrl: String, useTls: Boolean, path: String): String {
    val host = serverUrl
        .substringAfter("://")     // drop any scheme the user typed
        .trim()
        .trimEnd('/')
    val scheme = if (useTls) "wss" else "ws"
    return "$scheme://$host$path"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'io.konektis.ems.WsUrlTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/io/konektis/ems/data/ws/WsUrl.kt app/src/test/kotlin/io/konektis/ems/WsUrlTest.kt
git commit -m "feat(ws): pure wsUrl helper (scheme from useTls, strips scheme/trailing slash)"
```

---

## Task 3: ControlWsClient — wss scheme + API-key header

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`

- [ ] **Step 1: Add imports**

Add to the import block in `app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`:

```kotlin
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
```

- [ ] **Step 2: Replace the `client.webSocket(...)` call to use `wsUrl` + send the header**

Find the line:

```kotlin
                        client.webSocket("ws://${s.serverUrl}/ws") {
```

Replace it with (note the `request = { ... }` builder added before the session block; the API-key header is only sent when set, so LAN/dev with a blank key is unaffected):

```kotlin
                        client.webSocket(
                            urlString = wsUrl(s.serverUrl, s.useTls, "/ws"),
                            request = {
                                if (s.apiKey.isNotBlank()) {
                                    header(HttpHeaders.Authorization, "Bearer ${s.apiKey}")
                                }
                            }
                        ) {
```

Everything inside the session block is unchanged — the client still sends the
`ClientMessage.Authenticate(s.username, s.password)` frame on connect.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt
git commit -m "feat(ws): ControlWsClient dials wss + sends API-key header (keeps Authenticate frame)"
```

---

## Task 4: StatusWsClient — wss scheme + API-key header (no app auth)

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt`

- [ ] **Step 1: Add imports**

Add to the import block in `app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt`:

```kotlin
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
```

- [ ] **Step 2: Replace the `client.webSocket(...)` call**

Find the line:

```kotlin
                client.webSocket("ws://${s.serverUrl}/status-ws") {
```

Replace it with:

```kotlin
                client.webSocket(
                    urlString = wsUrl(s.serverUrl, s.useTls, "/status-ws"),
                    request = {
                        if (s.apiKey.isNotBlank()) {
                            header(HttpHeaders.Authorization, "Bearer ${s.apiKey}")
                        }
                    }
                ) {
```

Do NOT add an `Authenticate` frame — `/status-ws` has no app-layer auth; the API-key
header is the only credential it carries (used by the edge proxy remotely; ignored on
the LAN where there's no edge).

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/io/konektis/ems/data/ws/StatusWsClient.kt
git commit -m "feat(ws): StatusWsClient dials wss + sends API-key header (no app-layer auth)"
```

---

## Task 5: Settings UI — API-key field + TLS switch

**Files:**
- Modify: `app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add imports for the Switch + Row layout**

Add to the import block in `app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt`:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
```

- [ ] **Step 2: Add state for the new fields**

After the existing `password` state line:

```kotlin
    var password  by rememberSaveable(saved.password)  { mutableStateOf(saved.password) }
```

add:

```kotlin
    var useTls    by rememberSaveable(saved.useTls)    { mutableStateOf(saved.useTls) }
    var apiKey    by rememberSaveable(saved.apiKey)    { mutableStateOf(saved.apiKey) }
```

- [ ] **Step 3: Add the UI controls and update the server-address hint**

Update the "Server address" field's placeholder to hint at the bare-host remote case, then add the API-key field and the TLS switch. Replace the existing "Server address" `OutlinedTextField` placeholder line:

```kotlin
                placeholder = { Text("10.0.2.2:8080") },
```

with:

```kotlin
                placeholder = { Text("ec29.ems.konektis.io or 10.0.2.2:8080") },
```

Then, immediately **after** the `password` `OutlinedTextField` (before the `Button`), insert:

```kotlin
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key (remote)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use TLS (wss)")
                Spacer(Modifier.weight(1f))
                Switch(checked = useTls, onCheckedChange = { useTls = it })
            }
```

- [ ] **Step 4: Update the `save(...)` call to include the new fields**

Replace:

```kotlin
                    vm.save(Settings(serverUrl.trim(), username.trim(), password))
```

with (named args, since the constructor now has five parameters):

```kotlin
                    vm.save(
                        Settings(
                            serverUrl = serverUrl.trim(),
                            username = username.trim(),
                            password = password,
                            useTls = useTls,
                            apiKey = apiKey.trim()
                        )
                    )
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/io/konektis/ems/ui/settings/SettingsScreen.kt
git commit -m "feat(settings-ui): API key field + TLS toggle + bare-host server hint"
```

---

## Task 6: Full app build + test gate

**Files:** none (verification only).

- [ ] **Step 1: Run the app's unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — including `SettingsRepositoryTest` (3) and `WsUrlTest` (4).

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (only if anything changed; otherwise skip)**

No code changes expected here. If the build surfaced a fix, commit it with a clear message.

---

## Self-Review

**Spec coverage:**
- B7 `wss://` support (TLS toggle, default off, build scheme) → Task 1 (`useTls` setting, default `false`) + Task 2 (`wsUrl` helper) + Tasks 3/4 (both clients use it). B8 API-key header on both handshakes via the request builder → Tasks 3 + 4 (`Authorization: Bearer`). B9 Settings UI (API key field + TLS toggle + bare-host server address) → Task 5. "StatusWsClient does NOT authenticate; only wss + header" → Task 4 explicitly adds no `Authenticate` frame. "ControlWsClient keeps sending its Authenticate frame" → Task 3 leaves the session block untouched. API key stored in Settings, rotatable without rebuild → Task 1 (persisted in DataStore).

**Placeholder scan:** No TBD/TODO/"handle edge cases". Every code step shows complete code.

**Type consistency:** `Settings(serverUrl, username, password, useTls, apiKey)` is identical across Task 1 (definition), the Task 1 tests, and the Task 5 `save(...)` call (named args). `wsUrl(serverUrl, useTls, path)` signature matches between Task 2 (definition + tests) and its two call sites in Tasks 3/4. `useTls`/`apiKey` field names are consistent across repository, UI state, and tests. The conditional `if (s.apiKey.isNotBlank())` is identical in both WS clients.
