# Charger-Control Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple EMS mode (device allocation) from charger mode (Solar/Fixed), replace the in-memory `ChargingState` with a persisted `ChargerControl` shared by the app and `/ocpp-ui`, auto-revert Solar→Fixed in MANUAL, set Fixed in amps, and drop the `emsAutoControl`/`Max A` knobs.

**Architecture:** A `ChargerControl(mode, fixedAmps, charging)` record is the single source of truth, owned by `EnergyManager`, persisted per charger via a `ChargerControlStore`, and set from both `/ws` (`SetCharging`) and `/ocpp-ui`. Each tick `EnergyManager` resolves effective charger amps (NotConnected/stop→0; MANUAL coerces Solar→Fixed; Fixed→clamped amps; AUTO+Solar→strategy surplus) reusing the existing `chargerOverrideAmps` plumbing. The battery already balances the measured grid (prerequisite fix), so a Fixed charger the car under-draws is handled correctly.

**Tech Stack:** Kotlin/Ktor, Exposed/SQLite, kotlinx-serialization (polymorphic sealed messages), MockK + kotlinx-coroutines-test (server), Jetpack Compose + JUnit (app). Build: `./gradlew build` (server); `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` (app).

**Spec:** `docs/superpowers/specs/2026-06-04-charger-control-unification-design.md`
**Prerequisite (already merged):** `docs/superpowers/specs/2026-06-04-battery-measured-grid-balance-design.md`

---

## File Structure

**Server**
- `ocpp/db/OcppTables.kt` — replace `OcppChargerSettings` with `OcppChargerControl`.
- `ocpp/db/OcppStores.kt` — replace `ChargerSettingsStore`/`ChargerSettingsRecord` with the `ChargerControlStore` interface + `SqlChargerControlStore` + `ChargerControlRecord`.
- `Messages.kt` — replace the `ChargingState` sealed class with `ChargerControl`/`ChargerMode`; `SetCharging(ChargerControl)`; `ChargerControlUpdate`.
- `ems/EnergyManager.kt` — own + persist `ChargerControl`; `effectiveChargerAmps`; load on startup.
- `Sockets.kt` — handle `SetCharging(ChargerControl)`, broadcast `ChargerControlUpdate`.
- `devices/charger/OcppCharger.kt` — drop the `emsAutoControl`/`maxCurrentA` gate.
- `ocpp/OcppService.kt` — drop the `ChargerSettingsStore` dependency + its methods.
- `ocpp/OcppWebUi.kt` — replace `/settings` with `/charger-control`; take `EnergyManager`.
- `src/main/resources/ocpp.html` — replace the EMS-auto/Max-A block with Solar/Fixed + amps.
- `di/AppModule.kt`, `Application.kt` — wiring.

**App**
- `data/model/WsMessage.kt` — `ChargerControl`/`ChargerMode`; messages.
- `data/ws/ControlWsClient.kt` — expose `chargerControl: StateFlow<ChargerControl?>`.
- `ui/dashboard/DashboardViewModel.kt`, `ui/NavHost.kt`, `ui/dashboard/DashboardScreen.kt` — plumb `chargerControl`.
- `ui/charger/ChargerScreen.kt` — Solar/Fixed gated by EMS mode; amps; Start/Stop sends `ChargerControl`.

---

## Task 1: `ChargerControlStore` (persistence, additive)

**Files:**
- Modify: `src/main/kotlin/ocpp/db/OcppTables.kt`
- Modify: `src/main/kotlin/ocpp/db/OcppStores.kt`
- Test: `src/test/kotlin/ocpp/db/OcppStoresTest.kt`

This task is additive — the old `ChargerSettingsStore` stays until Task 3, so the build stays green.

- [ ] **Step 1: Write the failing store round-trip test**

In `src/test/kotlin/ocpp/db/OcppStoresTest.kt`, add (next to `chargerSettingsRoundTrip`):

```kotlin
    @Test
    fun chargerControlRoundTrip() = runTest {
        val db = freshTestDb()
        val store = SqlChargerControlStore(db)
        store.init()

        assertNull(store.get("CP01"))
        store.put("CP01", mode = "FIXED", fixedAmps = 16, charging = true)
        val c = store.get("CP01")!!
        assertEquals("FIXED", c.mode)
        assertEquals(16, c.fixedAmps)
        assertTrue(c.charging)

        store.put("CP01", mode = "SOLAR", fixedAmps = 10, charging = false)
        val c2 = store.get("CP01")!!
        assertEquals("SOLAR", c2.mode)
        assertEquals(10, c2.fixedAmps)
        assertFalse(c2.charging)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "io.konektis.ocpp.db.OcppStoresTest"`
Expected: FAIL — `SqlChargerControlStore` unresolved (compile error).

- [ ] **Step 3: Add the table**

In `src/main/kotlin/ocpp/db/OcppTables.kt`, add after `OcppChargerSettings`:

```kotlin
object OcppChargerControl : Table("ocpp_charger_control") {
    val chargePointId = varchar("charge_point_id", 64)
    val mode = varchar("mode", 16)        // "SOLAR" | "FIXED"
    val fixedAmps = integer("fixed_amps")
    val charging = bool("charging")
    override val primaryKey = PrimaryKey(chargePointId)
}
```

- [ ] **Step 4: Add the store interface + SQL implementation**

In `src/main/kotlin/ocpp/db/OcppStores.kt`, add (after `ChargerSettingsStore`):

```kotlin
data class ChargerControlRecord(val chargePointId: String, val mode: String, val fixedAmps: Int, val charging: Boolean)

/** Persisted per-charger control intent. An interface so EnergyManager unit tests can fake it. */
interface ChargerControlStore {
    fun init()
    suspend fun get(id: String): ChargerControlRecord?
    suspend fun put(id: String, mode: String, fixedAmps: Int, charging: Boolean)
}

class SqlChargerControlStore(private val db: Database) : ChargerControlStore {
    override fun init() = transaction(db) { SchemaUtils.create(OcppChargerControl) }

    override suspend fun get(id: String): ChargerControlRecord? = dbQuery(db) {
        OcppChargerControl.selectAll().where { OcppChargerControl.chargePointId eq id }.singleOrNull()?.let {
            ChargerControlRecord(
                it[OcppChargerControl.chargePointId], it[OcppChargerControl.mode],
                it[OcppChargerControl.fixedAmps], it[OcppChargerControl.charging],
            )
        }
    }

    override suspend fun put(id: String, mode: String, fixedAmps: Int, charging: Boolean) = dbQuery(db) {
        val exists = OcppChargerControl.selectAll().where { OcppChargerControl.chargePointId eq id }.any()
        if (exists) {
            OcppChargerControl.update({ OcppChargerControl.chargePointId eq id }) {
                it[OcppChargerControl.mode] = mode
                it[OcppChargerControl.fixedAmps] = fixedAmps
                it[OcppChargerControl.charging] = charging
            }
        } else {
            OcppChargerControl.insert {
                it[chargePointId] = id
                it[OcppChargerControl.mode] = mode
                it[OcppChargerControl.fixedAmps] = fixedAmps
                it[OcppChargerControl.charging] = charging
            }
        }
        Unit
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests "io.konektis.ocpp.db.OcppStoresTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/ocpp/db/OcppTables.kt src/main/kotlin/ocpp/db/OcppStores.kt src/test/kotlin/ocpp/db/OcppStoresTest.kt
git commit -m "feat(db): add ChargerControlStore (persisted charger mode/fixedAmps/charging)"
```

---

## Task 2: `ChargerControl` protocol + EnergyManager control + Sockets

This is the atomic protocol switch — `Messages.kt`, `EnergyManager`, `Sockets`, DI, and their tests change together so the server compiles.

**Files:**
- Modify: `src/main/kotlin/Messages.kt`
- Modify: `src/main/kotlin/ems/EnergyManager.kt`
- Modify: `src/main/kotlin/Sockets.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`
- Modify: `src/main/kotlin/Application.kt`
- Test: `src/test/kotlin/ems/EnergyManagerTest.kt`
- Test: `src/test/kotlin/MessagesTest.kt`

- [ ] **Step 1: Replace the protocol model in `Messages.kt`**

In `src/main/kotlin/Messages.kt`, delete the entire `ChargingState` sealed class and add:

```kotlin
@Serializable
enum class ChargerMode { SOLAR, FIXED }

@Serializable
data class ChargerControl(
    val mode: ChargerMode = ChargerMode.SOLAR,
    val fixedAmps: Int = 16,
    val charging: Boolean = true,
)
```

In the `Message` sealed class, replace the `ChargingStateUpdate` entry with:

```kotlin
    @Serializable @SerialName("ChargerControlUpdate")
    data class ChargerControlUpdate(val control: ChargerControl) : Message()
```

In the `ClientMessage` sealed class, change `SetCharging`:

```kotlin
    @Serializable @SerialName("SetCharging")
    data class SetCharging(val control: ChargerControl) : ClientMessage()
```

- [ ] **Step 2: Rework `EnergyManager` to own + persist `ChargerControl`**

In `src/main/kotlin/ems/EnergyManager.kt`:

Update imports — remove `import io.konektis.ChargingState`; add:

```kotlin
import io.konektis.ChargerControl
import io.konektis.ChargerMode
import io.konektis.ocpp.db.ChargerControlStore
```

Add the store to the constructor:

```kotlin
class EnergyManager(
    private val world: World,
    private val config: Config,
    private val strategy: Strategy,
    private val chargerControlStore: ChargerControlStore,
) : Klogging {
```

Replace the existing `chargingStateFlow` / `chargerControl` getter / `setCharging` block with:

```kotlin
    private val chargerKey: String? =
        config.devices.charger.firstOrNull()?.let { it.chargePointId ?: it.name }

    private fun defaultControl() =
        ChargerControl(ChargerMode.SOLAR, config.devices.charger.firstOrNull()?.chargingCurrent?.max?.toInt() ?: 16, true)

    val chargerControlFlow = MutableStateFlow(defaultControl())
    val chargerControl: ChargerControl get() = chargerControlFlow.value

    /** Load the persisted control on startup (call once before run()). */
    suspend fun loadChargerControl() {
        chargerControlStore.init()
        val key = chargerKey ?: return
        val rec = chargerControlStore.get(key) ?: return
        chargerControlFlow.value = ChargerControl(
            mode = runCatching { ChargerMode.valueOf(rec.mode) }.getOrDefault(ChargerMode.SOLAR),
            fixedAmps = rec.fixedAmps,
            charging = rec.charging,
        )
    }

    suspend fun setCharging(control: ChargerControl) {
        chargerControlFlow.value = control
        val key = chargerKey ?: return
        runCatchingLog("persist charger control") {
            chargerControlStore.put(key, control.mode.name, control.fixedAmps, control.charging)
        }
    }
```

Replace the old `chargerOverrideAmps(maxAmps)` helper with the connection-aware, EMS-mode-aware resolver:

```kotlin
    /**
     * Effective forced charger amps, or null to let the strategy compute the solar surplus.
     * No car or stopped -> 0. Solar surplus only applies in AUTO; MANUAL auto-reverts to Fixed.
     */
    private fun effectiveChargerAmps(maxAmps: Int, connected: Boolean): Int? {
        val c = chargerControl
        if (!connected || !c.charging) return 0
        val chargerMode = if (mode == Mode.AUTO) c.mode else ChargerMode.FIXED
        return when (chargerMode) {
            ChargerMode.FIXED -> c.fixedAmps.coerceIn(0, maxAmps)
            ChargerMode.SOLAR -> null
        }
    }
```

In `tick()`, replace the `override` computation (the `configMaxAmps()?.let { ... }` block) with:

```kotlin
        val connected = emsState.chargerConnection != ChargerConnection.NotConnected
        val override = configMaxAmps()?.let { effectiveChargerAmps(it, connected) }
```

Simplify `applyChargerInManual` (in MANUAL the resolver always returns a concrete value, never null):

```kotlin
    /** MANUAL: battery/heat pump are released; still drive the charger per its (Fixed) intent. */
    private suspend fun applyChargerInManual(override: Int?) {
        applyChargerAmps(override ?: 0)
    }
```

Update the MANUAL branch call in `tick()` accordingly:

```kotlin
        if (mode != Mode.AUTO) {
            applyChargerInManual(override)
            return
        }
```

(The AUTO tier-1/tier-2/blind branches are unchanged — they already use `override` via `snapshot.copy(chargerOverrideAmps = override)` and `override?.let { applyChargerAmps(it) }`.)

- [ ] **Step 3: Update `Sockets.kt`**

In `src/main/kotlin/Sockets.kt`:

Change the charging-state collector:

```kotlin
            val chargingJob = launch {
                energyManager.chargerControlFlow.collect { control ->
                    if (authenticated) {
                        send(Json.encodeToString(Message.ChargerControlUpdate(control) as Message))
                    }
                }
            }
```

Change the auth-time send:

```kotlin
                                    send(Json.encodeToString(Message.ChargerControlUpdate(energyManager.chargerControlFlow.value) as Message))
```

Change the `SetCharging` handler:

```kotlin
                            is ClientMessage.SetCharging -> {
                                if (!authenticated) {
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                                } else {
                                    // The chargingJob collector echoes the resulting ChargerControlUpdate.
                                    energyManager.setCharging(message.control)
                                }
                            }
```

- [ ] **Step 4: Wire the store through DI**

In `src/main/kotlin/di/AppModule.kt`, add the import:

```kotlin
import io.konektis.ocpp.db.ChargerControlStore
import io.konektis.ocpp.db.SqlChargerControlStore
```

Add a provider and thread it into `provideEnergyManager`:

```kotlin
    @ApplicationScope
    @Provides
    fun provideChargerControlStore(database: Database): ChargerControlStore = SqlChargerControlStore(database)

    @Provides
    fun provideEnergyManager(world: World, config: Config, strategy: Strategy, chargerControlStore: ChargerControlStore): EnergyManager =
        EnergyManager(world, config, strategy, chargerControlStore)
```

In `src/main/kotlin/Application.kt`, load the persisted control once before the run loop — inside the `coroutineScope { ... }`, immediately before `launch { energyManager.run() }`:

```kotlin
            energyManager.loadChargerControl()
            launch { energyManager.run() }
```

- [ ] **Step 5: Update `MessagesTest`**

In `src/test/kotlin/MessagesTest.kt`, replace the `ChargingStateUpdate …` test:

```kotlin
    @Test
    fun `ChargerControlUpdate uses the agreed discriminator and encodes the control`() {
        val json = Json.encodeToString(
            Message.ChargerControlUpdate(ChargerControl(ChargerMode.FIXED, 16, true)) as Message
        )
        assertTrue(json.contains("\"type\":\"ChargerControlUpdate\""), "unexpected discriminator: $json")
        assertTrue(json.contains("\"mode\":\"FIXED\""), "missing mode: $json")
    }
```

(Add `import io.konektis.ChargerControl` and `import io.konektis.ChargerMode` to the test if not already imported via the package.)

- [ ] **Step 6: Update `EnergyManagerTest`**

In `src/test/kotlin/ems/EnergyManagerTest.kt`:

Replace the import `import io.konektis.ChargingState` with:

```kotlin
import io.konektis.ChargerControl
import io.konektis.ChargerMode
import io.konektis.ocpp.db.ChargerControlRecord
import io.konektis.ocpp.db.ChargerControlStore
```

Add a fake store near the top-level test helpers (after the `heatpump(...)`/`config()` helpers):

```kotlin
private class FakeChargerControlStore : ChargerControlStore {
    private var rec: ChargerControlRecord? = null
    override fun init() {}
    override suspend fun get(id: String): ChargerControlRecord? = rec
    override suspend fun put(id: String, mode: String, fixedAmps: Int, charging: Boolean) {
        rec = ChargerControlRecord(id, mode, fixedAmps, charging)
    }
}
```

Update the `manager(...)` helper:

```kotlin
    private fun manager(world: World) = EnergyManager(world, config(), SurplusPriorityStrategy(), FakeChargerControlStore())
```

Replace the charger-control tests (the ones that used `ChargingState`) with:

```kotlin
    @Test fun `setCharging updates chargerControlFlow`() = runTest {
        val world = World(grid(0), mapOf("c" to charger(0)), emptyMap(), emptyMap(), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setCharging(ChargerControl(charging = false))
        assertEquals(false, m.chargerControlFlow.value.charging)
    }

    @Test fun `stop forces charger to zero amps in AUTO`() = runTest {
        val ch = charger(0)
        val world = World(grid(-5000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setCharging(ChargerControl(ChargerMode.SOLAR, 16, charging = false))
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(0)) }
    }

    @Test fun `fixed mode sets clamped amps in AUTO`() = runTest {
        val ch = charger(0)
        val world = World(grid(0), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setCharging(ChargerControl(ChargerMode.FIXED, 16, true))
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(16 * 230)) }
    }

    @Test fun `MANUAL auto-reverts solar to fixed, battery untouched`() = runTest {
        val ch = charger(0)
        val bat = battery(0)
        val world = World(grid(-3000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to bat))
        val m = manager(world)
        m.setMode(Mode.MANUAL)
        m.setCharging(ChargerControl(ChargerMode.SOLAR, 16, true)) // solar is meaningless in MANUAL -> fixed 16A
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(16 * 230)) }
        coVerify(exactly = 0) { bat.setChargingPower(any()) }
    }

    @Test fun `stop forces zero in MANUAL`() = runTest {
        val ch = charger(0)
        val world = World(grid(-3000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        val m = manager(world)
        m.setMode(Mode.MANUAL)
        m.setCharging(ChargerControl(charging = false))
        m.tick()
        coVerify { ch.setMaxChargerPower(Watt(0)) }
    }

    @Test fun `no car connected forces charger to zero despite surplus`() = runTest {
        val ch = mockk<Charger>(relaxed = true).also {
            coEvery { it.getState() } returns DeviceUpdate(
                GlobalTimeSource.source.markNow(),
                ChargerState(Watt(0), ChargerConnection.NotConnected)
            )
        }
        val world = World(grid(-5000), mapOf("c" to ch), emptyMap(), mapOf("h" to heatpump(0)), mapOf("b" to battery(0)))
        manager(world).tick() // default control SOLAR/charging, but NotConnected -> 0
        coVerify { ch.setMaxChargerPower(Watt(0)) }
    }
```

Ensure these imports exist in the test (add any missing): `import io.konektis.devices.charger.ChargerConnection`, `import io.konektis.devices.charger.ChargerState`, `import io.konektis.devices.charger.Charger`, `import io.konektis.devices.DeviceUpdate`, `import io.konektis.GlobalTimeSource`, `import io.mockk.mockk`, `import io.mockk.coEvery`, `import kotlin.test.assertEquals`.

- [ ] **Step 7: Build and verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (all server tests pass; `OcppCharger` still has its old gate — removed in Task 3 — which is harmless here).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/Messages.kt src/main/kotlin/ems/EnergyManager.kt src/main/kotlin/Sockets.kt src/main/kotlin/di/AppModule.kt src/main/kotlin/Application.kt src/test/kotlin/ems/EnergyManagerTest.kt src/test/kotlin/MessagesTest.kt
git commit -m "feat(ems): persisted ChargerControl, EMS-mode-aware (solar auto-reverts to fixed in MANUAL)"
```

---

## Task 3: Remove the old `emsAutoControl`/`Max A` charger settings

**Files:**
- Modify: `src/main/kotlin/devices/charger/OcppCharger.kt`
- Modify: `src/main/kotlin/ocpp/OcppService.kt`
- Modify: `src/main/kotlin/ocpp/db/OcppStores.kt`
- Modify: `src/main/kotlin/ocpp/db/OcppTables.kt`
- Modify: `src/main/kotlin/di/AppModule.kt`
- Modify: `src/main/kotlin/ocpp/OcppWebUi.kt`
- Test: `src/test/kotlin/devices/charger/OcppChargerTest.kt`
- Test: `src/test/kotlin/ocpp/db/OcppStoresTest.kt`
- Test: `src/test/kotlin/ocpp/OcppWebUiTest.kt`, `OcppCommandsTest.kt`, `OcppServiceTest.kt`, `OcppCapabilityTest.kt`, `OcppCorrelationTest.kt`, `OcppServerTest.kt`

- [ ] **Step 1: Drop the gate in `OcppCharger.setMaxChargerPower`**

Replace the method body in `src/main/kotlin/devices/charger/OcppCharger.kt`:

```kotlin
    override suspend fun setMaxChargerPower(power: Watt) {
        if (!service.isPowerControlCapable(chargePointId)) {
            logger.debug { "OcppCharger $chargePointId: SmartCharging unsupported, skipping setMaxChargerPower" }
            return
        }
        // Send in amps (230V convention). The EMS has already clamped to the config max / fixed level.
        val amps = power.value / 230
        val ok = service.setChargingProfile(chargePointId, connectorId, amps.toDouble(), ChargingRateUnitType.A)
        if (!ok) logger.warn { "OcppCharger $chargePointId: SetChargingProfile($amps A) not accepted" }
    }
```

- [ ] **Step 2: Remove the store + table + OcppService dependency + DI + endpoints**

- `src/main/kotlin/ocpp/db/OcppStores.kt`: delete `ChargerSettingsRecord` and `ChargerSettingsStore`.
- `src/main/kotlin/ocpp/db/OcppTables.kt`: delete the `OcppChargerSettings` object.
- `src/main/kotlin/ocpp/OcppService.kt`:
  - remove the `private val settings: ChargerSettingsStore,` constructor parameter;
  - in `initStores()`, remove `settings.init();`;
  - delete `getChargerSettings(...)` and `putChargerSettings(...)`;
  - remove the `ChargerSettingsStore`/`ChargerSettingsRecord` imports.
- `src/main/kotlin/di/AppModule.kt`: in `provideOcppService`, drop the `ChargerSettingsStore(database),` argument; remove the `import io.konektis.ocpp.db.ChargerSettingsStore`.
- `src/main/kotlin/ocpp/OcppWebUi.kt`: delete the `SettingsBody` data class and the `get("/settings/{id}")` + `post("/settings/{id}")` routes.

- [ ] **Step 3: Update the tests that referenced the removed API**

- `src/test/kotlin/ocpp/db/OcppStoresTest.kt`: delete the `chargerSettingsRoundTrip` test.
- `src/test/kotlin/devices/charger/OcppChargerTest.kt`: delete `setMaxPowerNoOpWhenEmsAutoControlDisabled` and `setMaxPowerClampsToConfiguredMaxCurrent` (they tested the removed gate). Update `setMaxPowerSendsChargingProfileWhenCapable` to drop the `getChargerSettings` stub:

```kotlin
    @Test
    fun setMaxPowerSendsChargingProfileWhenCapable() = runTest {
        val svc = mockk<OcppService>()
        every { svc.isPowerControlCapable("CP1") } returns true
        coEvery { svc.setChargingProfile("CP1", 1, any(), any()) } returns true
        val charger = OcppCharger("CP1", 1, svc)

        charger.setMaxChargerPower(Watt(3680)) // 16A * 230V

        coVerify { svc.setChargingProfile("CP1", 1, 16.0, ChargingRateUnitType.A) }
    }
```

  Remove the now-unused `import io.konektis.ocpp.db.ChargerSettingsRecord`.
- In each of `OcppWebUiTest.kt`, `OcppCommandsTest.kt`, `OcppServiceTest.kt`, `OcppCapabilityTest.kt`, `OcppCorrelationTest.kt`, `OcppServerTest.kt`: every `OcppService(ChargePointStore(db), IdTagStore(db), ChargerSettingsStore(db), TransactionStore(db), ...)` call drops the `ChargerSettingsStore(db),` argument so it becomes `OcppService(ChargePointStore(db), IdTagStore(db), TransactionStore(db), ...)`. Remove any now-unused `ChargerSettingsStore` import.

- [ ] **Step 4: Build and verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — all references to the removed API are gone.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(ocpp): remove emsAutoControl/Max-A charger settings (superseded by ChargerControl)"
```

---

## Task 4: `/ocpp-ui` charger-control (mirror the app)

**Files:**
- Modify: `src/main/kotlin/ocpp/OcppWebUi.kt`
- Modify: `src/main/kotlin/Application.kt`
- Modify: `src/main/resources/ocpp.html`
- Test: `src/test/kotlin/ocpp/OcppWebUiTest.kt`

- [ ] **Step 1: Add the endpoints + take `EnergyManager`**

In `src/main/kotlin/ocpp/OcppWebUi.kt`:

Add the import:

```kotlin
import io.konektis.ChargerControl
import io.konektis.ems.EnergyManager
```

Change the function signature:

```kotlin
fun Application.configureOcppWebUi(service: OcppService, energyManager: EnergyManager) {
```

Add a body type near the others:

```kotlin
@Serializable data class ChargerControlBody(val mode: String, val fixedAmps: Int, val charging: Boolean)
```

Add the routes inside `route("/ocpp-ui/api") { ... }` (single configured charger, so `{id}` is accepted but the control is global):

```kotlin
            get("/chargepoints/{id}/charger-control") {
                call.respondText(json.encodeToString(energyManager.chargerControlFlow.value), ContentType.Application.Json)
            }
            post("/chargepoints/{id}/charger-control") {
                val body = call.receive<ChargerControlBody>()
                val mode = runCatching { io.konektis.ChargerMode.valueOf(body.mode) }.getOrDefault(io.konektis.ChargerMode.SOLAR)
                energyManager.setCharging(ChargerControl(mode, body.fixedAmps, body.charging))
                call.respond(HttpStatusCode.OK)
            }
```

- [ ] **Step 2: Wire `EnergyManager` into the call site**

In `src/main/kotlin/Application.kt`, update `module(...)` to pass `energyManager` and the call:

```kotlin
    configureOcppWebUi(ocppService, energyManager)
```

(`energyManager` is already a parameter of `Application.module`.)

- [ ] **Step 3: Replace the settings block in `ocpp.html`**

In `src/main/resources/ocpp.html`, replace the `settingsRow` block in `createCard` (the "Max A" input + "EMS auto" checkbox + Save) with a charger-control block:

```javascript
  // Charger control (mirrors the app): Solar surplus vs Fixed (amps), and Start/Stop via `charging`.
  const controlModeRow = document.createElement('div');
  controlModeRow.className = 'row';
  controlModeRow.style.marginTop = '8px';
  const modeSelect = document.createElement('select');
  modeSelect.innerHTML = '<option value="SOLAR">Solar surplus</option><option value="FIXED">Fixed power</option>';
  const fixedA = document.createElement('input');
  fixedA.type = 'number'; fixedA.min = '0'; fixedA.style.width = '64px';
  const ccLabel = document.createElement('span');
  ccLabel.className = 'muted';
  ccLabel.append(document.createTextNode('Mode '), modeSelect, document.createTextNode(' Fixed A '), fixedA);
  const ccSave = document.createElement('button');
  ccSave.textContent = 'Apply';
  ccSave.addEventListener('click', () => saveChargerControl(id));
  controlModeRow.append(ccLabel, ccSave);
```

In `createCard`, replace `settingsRow` with `controlModeRow` in the `div.append(...)` call, and update the `refs` to expose `modeSelect`/`fixedA` instead of `maxA`/`auto`:

```javascript
  div.append(headRow, meta, connectors, controlModeRow, actionRow, controlRow);

  const entry = { card: div, refs: { scPill, prPill, meta, connectors, modeSelect, fixedA, ampsInput, connInput, stopBtn } };
```

Replace `loadSettings`/`saveSettings` with charger-control equivalents:

```javascript
async function loadSettings(id) {
  const r = await api('/chargepoints/' + id + '/charger-control');
  const entry = cards[id];
  if (!entry || !r.ok) return;
  const c = await r.json();
  const { modeSelect, fixedA } = entry.refs;
  if (document.activeElement !== modeSelect) modeSelect.value = c.mode;
  if (document.activeElement !== fixedA) fixedA.value = c.fixedAmps;
}
async function saveChargerControl(id) {
  const entry = cards[id];
  if (!entry) return;
  const mode = entry.refs.modeSelect.value;
  const fixedAmps = parseInt(entry.refs.fixedA.value, 10) || 0;
  const r = await api('/chargepoints/' + id + '/charger-control', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode, fixedAmps, charging: true }),
  });
  if (!r.ok) alert('Apply failed — charger offline.');
}
```

(`loadSettings(id)` is already called once on card creation in `render`; keep that call. The low-level Set current / Clear profile / Reset / Stop controls and their handlers are unchanged.)

- [ ] **Step 4: Update `OcppWebUiTest`**

In `src/test/kotlin/ocpp/OcppWebUiTest.kt`, the `testModule()` builds an `OcppService` and calls `configureOcppWebUi`. Update it to construct an `EnergyManager` and pass it. Replace `testModule()`:

```kotlin
    private fun Application.testModule(): OcppService {
        install(WebSockets) { pingPeriod = 30.seconds; timeout = 60.seconds }
        install(ContentNegotiation) { json() }
        val db = freshTestDb()
        val svc = OcppService(ChargePointStore(db), IdTagStore(db), TransactionStore(db),
            OcppConfig(true, 300, 60, autoProbeOnBoot = false)).also { it.initStores() }
        val em = EnergyManager(
            io.konektis.devices.World(mockk(relaxed = true), emptyMap(), emptyMap(), emptyMap(), emptyMap()),
            mockk(relaxed = true), io.konektis.ems.SurplusPriorityStrategy(), SqlChargerControlStore(db),
        )
        configureOcppWebUi(svc, em)
        return svc
    }
```

Add imports: `import io.konektis.ems.EnergyManager`, `import io.konektis.ocpp.db.SqlChargerControlStore`, `import io.mockk.mockk`. (The `config` passed to `EnergyManager` is a relaxed mock; `provideEnergyManager`'s `chargerKey`/`defaultControl` tolerate an empty charger list via the `?:` fallbacks.)

> NOTE: confirm `EnergyManager`'s `defaultControl()`/`chargerKey` handle `config.devices.charger` being empty (a relaxed mock returns an empty list) — both use `firstOrNull()?...?: <default>`, so they do.

- [ ] **Step 5: Build and verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; `OcppWebUiTest.servesThePage` still passes.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(ocpp-ui): Solar/Fixed charger-control mirroring the app"
```

---

## Task 5: App — `ChargerControl` model + UI

**Files:**
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/model/WsMessage.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/data/ws/ControlWsClient.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardViewModel.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/NavHost.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/dashboard/DashboardScreen.kt`
- Modify: `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt`
- Test: `android/app/src/test/kotlin/io/konektis/ems/DashboardViewModelTest.kt`

- [ ] **Step 1: Replace the protocol model (`WsMessage.kt`)**

In `android/app/src/main/kotlin/io/konektis/ems/data/model/WsMessage.kt`, delete the `ChargingState` sealed class and add:

```kotlin
@Serializable enum class ChargerMode { SOLAR, FIXED }

@Serializable
data class ChargerControl(
    val mode: ChargerMode = ChargerMode.SOLAR,
    val fixedAmps: Int = 16,
    val charging: Boolean = true,
)
```

In `Message`, replace `ChargingStateUpdate` with:

```kotlin
    @Serializable @SerialName("ChargerControlUpdate") data class ChargerControlUpdate(val control: ChargerControl) : Message()
```

In `ClientMessage`, change `SetCharging`:

```kotlin
    @Serializable @SerialName("SetCharging") data class SetCharging(val control: ChargerControl) : ClientMessage()
```

- [ ] **Step 2: `ControlWsClient`**

In `ControlWsClient.kt`: replace the `ChargingState` import with `import io.konektis.ems.data.model.ChargerControl`; rename `_chargingState`/`chargingState` to `_chargerControl`/`chargerControl` (`StateFlow<ChargerControl?>`); change the handler to `is Message.ChargerControlUpdate -> _chargerControl.value = msg.control`; and the three reset sites set `_chargerControl.value = null`.

- [ ] **Step 3: `DashboardViewModel`**

In `DashboardViewModel.kt`: replace the `ChargingState` import with `ChargerControl`; change the param `val chargingState: StateFlow<ChargingState?> = MutableStateFlow(null)` to `val chargerControl: StateFlow<ChargerControl?> = MutableStateFlow(null)`; change `fun setCharging(state: ChargingState)` to `fun setCharging(control: ChargerControl)` sending `ClientMessage.SetCharging(control)`.

- [ ] **Step 4: `NavHost`**

In `NavHost.kt`, change the factory arg `chargingState = app.component.controlWsClient.chargingState` to `chargerControl = app.component.controlWsClient.chargerControl`.

- [ ] **Step 5: `DashboardScreen`**

In `DashboardScreen.kt`, change `val chargingState by vm.chargingState.collectAsState()` to `val chargerControl by vm.chargerControl.collectAsState()`, and the `ChargerScreen(...)` call to pass `chargerControl = chargerControl` (drop `chargingState`).

- [ ] **Step 6: Rework `ChargerScreen`**

Replace `android/app/src/main/kotlin/io/konektis/ems/ui/charger/ChargerScreen.kt` with:

```kotlin
package io.konektis.ems.ui.charger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ChargerControl
import io.konektis.ems.data.model.ChargerMode
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.data.model.StatusState
import io.konektis.ems.data.model.parseChargerConnection
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.components.StatusHero
import io.konektis.ems.ui.components.formatWatts
import io.konektis.ems.ui.theme.LocalEmsColors

@Composable
fun ChargerScreen(
    statusState: StatusState?,
    controlState: ControlState,
    chargerControl: ChargerControl?,
    mode: ManagerMode?,
    onSetCharging: (ChargerControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ems = LocalEmsColors.current
    val chargerW = statusState?.chargerW
    val connection = parseChargerConnection(statusState?.chargerConnection)
    val uiState = chargerUiState(connection)
    val isAuthenticated = controlState is ControlState.Authenticated
    val isCharging = uiState == ChargerUiState.CHARGING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (uiState) {
            ChargerUiState.NO_CAR -> {
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = "No car",
                    valueColor = ems.idle,
                    statusText = "No car connected",
                    online = false,
                )
            }
            else -> {
                val online = uiState != ChargerUiState.CONTROLS_FALLBACK || chargerW != null
                StatusHero(
                    icon = EmsIcons.Charger,
                    value = when {
                        isCharging && chargerW != null -> formatWatts(chargerW)
                        isCharging -> "Charging"
                        else -> "Idle"
                    },
                    valueColor = if (isCharging) ems.consumption else ems.idle,
                    statusText = when (uiState) {
                        ChargerUiState.CHARGING -> "Charging"
                        ChargerUiState.CONNECTED_IDLE -> "Connected — not charging"
                        else -> if (chargerW != null) "Charger online" else "Status unavailable"
                    },
                    online = online,
                )

                if (isAuthenticated && chargerControl != null) {
                    ChargerControls(
                        isCharging = isCharging,
                        control = chargerControl,
                        mode = mode,
                        onSetCharging = onSetCharging,
                    )
                } else {
                    Text(
                        "Charger control unavailable — check credentials in Settings.",
                        fontSize = 14.sp,
                        color = ems.idle,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargerControls(
    isCharging: Boolean,
    control: ChargerControl,
    mode: ManagerMode?,
    onSetCharging: (ChargerControl) -> Unit,
) {
    val ems = LocalEmsColors.current
    // EMS MANUAL makes solar surplus meaningless -> force Fixed.
    val solarAllowed = mode != ManagerMode.MANUAL
    var selectedMode by remember(control, mode) {
        mutableStateOf(if (control.mode == ChargerMode.SOLAR && solarAllowed) ChargerMode.SOLAR else ChargerMode.FIXED)
    }
    var fixedAmps by remember(control) { mutableIntStateOf(control.fixedAmps.coerceIn(6, 32)) }

    Text("MODE", style = MaterialTheme.typography.labelSmall, color = ems.idle)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selectedMode == ChargerMode.SOLAR,
            enabled = solarAllowed,
            onClick = { selectedMode = ChargerMode.SOLAR },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Solar surplus") }
        SegmentedButton(
            selected = selectedMode == ChargerMode.FIXED,
            onClick = { selectedMode = ChargerMode.FIXED },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Fixed power") }
    }

    if (!solarAllowed) {
        Text(
            "EMS is in manual mode — only fixed power applies to the charger.",
            fontSize = 12.sp,
            color = ems.idle,
        )
    }

    if (selectedMode == ChargerMode.FIXED) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Fixed current", style = MaterialTheme.typography.labelSmall, color = ems.idle)
                    Text("$fixedAmps A", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = fixedAmps.toFloat(),
                    onValueChange = { fixedAmps = it.toInt() },
                    valueRange = 6f..32f,
                    steps = 25,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("6 A", fontSize = 11.sp, color = ems.idle)
                    Text("32 A", fontSize = 11.sp, color = ems.idle)
                }
            }
        }
    }

    Button(
        onClick = {
            onSetCharging(
                ChargerControl(mode = selectedMode, fixedAmps = fixedAmps, charging = !isCharging)
            )
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isCharging) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            if (isCharging) "STOP CHARGING" else "START CHARGING",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

- [ ] **Step 7: Update `WsMessageTest`**

In `android/app/src/test/kotlin/io/konektis/ems/WsMessageTest.kt`, replace the `SetCharging` and `ChargingStateUpdate` tests (which used `ChargingState`) with `ChargerControl` versions:

```kotlin
    @Test
    fun `SetCharging round-trips`() {
        val msg = ClientMessage.SetCharging(ChargerControl(ChargerMode.FIXED, 16, true))
        assertEquals(msg, Json.decodeFromString<ClientMessage>(Json.encodeToString<ClientMessage>(msg)))
    }

    @Test
    fun `ChargerControlUpdate round-trips and uses the agreed discriminator`() {
        val msg = Message.ChargerControlUpdate(ChargerControl(ChargerMode.SOLAR, 10, false))
        val json = Json.encodeToString<Message>(msg)
        assertTrue(json.contains("\"type\":\"ChargerControlUpdate\""), "unexpected discriminator: $json")
        assertEquals(msg, Json.decodeFromString<Message>(json))
    }
```

Update the imports: replace `import io.konektis.ems.data.model.ChargingState` with `import io.konektis.ems.data.model.ChargerControl` and `import io.konektis.ems.data.model.ChargerMode`. (The `SetCharging ChargingWithMaxPower round-trips` test is removed — folded into the single `SetCharging round-trips` above.)

Also in `android/app/src/test/kotlin/io/konektis/ems/DashboardViewModelTest.kt`, the `setCharging forwards command to sendCommand` test uses `ChargingState.NotCharging`. Replace the import `import io.konektis.ems.data.model.ChargingState` with `import io.konektis.ems.data.model.ChargerControl`, then change the call `vm.setCharging(ChargingState.NotCharging)` to `vm.setCharging(ChargerControl(charging = false))` and the assertion's expected value `ClientMessage.SetCharging(ChargingState.NotCharging)` to `ClientMessage.SetCharging(ChargerControl(charging = false))` (leave the rest of the test body, including any `yield()`/dispatcher handling, unchanged).

- [ ] **Step 8: Build + tests + APK**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/kotlin/io/konektis/ems android/app/src/test/kotlin/io/konektis/ems
git commit -m "feat(app): ChargerControl model + Solar/Fixed charger tab gated by EMS mode"
```

---

## Task 6: Full-stack verification

**Files:** none (verification only).

- [ ] **Step 1: Server**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; all server tests pass.

- [ ] **Step 2: App**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke (optional, needs hardware)**

- App: in EMS AUTO, Solar/Fixed both selectable; in MANUAL, Solar disabled and a note shows; Fixed slider sets amps; Start/Stop persists across a server restart.
- `/ocpp-ui`: the charger card shows Solar/Fixed + Fixed A; Apply changes it; the app reflects the change (and vice versa).

---

## Self-Review Notes

- **Spec coverage:** two-concept decoupling + auto-revert → Task 2 (`effectiveChargerAmps`) + Task 5 (`solarAllowed`); persisted shared control → Tasks 1, 2, 4; protocol model → Tasks 2, 5; Max-A/emsAutoControl removal → Task 3; amps-based Fixed → Tasks 2 (`coerceIn(0, maxAmps)`), 5 (6–32 A slider); `/ocpp-ui` mirroring → Task 4; battery measured-grid prerequisite already merged.
- **Type consistency:** `ChargerControl(mode, fixedAmps, charging)` and `ChargerMode{SOLAR,FIXED}` identical in server `Messages.kt` and app `WsMessage.kt` with matching `@SerialName`s (`SetCharging`, `ChargerControlUpdate`); `ChargerControlStore.put(id, mode, fixedAmps, charging)` consistent across Task 1 (def), Task 2 (EnergyManager + fake), Task 4 (test); `configureOcppWebUi(service, energyManager)` consistent (Task 4 def + Application call).
- **Build-green ordering:** Task 1 additive; Task 2 atomic protocol switch (both stores coexist); Task 3 removes the old; Tasks 4–5 layer UI; Task 6 verifies. No placeholders; each code step shows full content or an exact edit.
