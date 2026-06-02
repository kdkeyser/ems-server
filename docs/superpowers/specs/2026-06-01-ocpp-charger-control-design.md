# OCPP Charger Control + Local Config Webpage — Design

**Date:** 2026-06-01
**Status:** Approved (design); pending implementation plan

## Goal

Make the EMS server's OCPP subsystem usable to **manage charging sessions for a local
car charger**, and where the charger supports it, to **read active charging power and set
the maximum charging power over OCPP** — making OCPP a drop-in replacement for the existing
Webasto Modbus charger. Also expose a **local webpage** to configure the OCPP server and
view live status.

The Webasto Modbus module is retained as a parallel option (for the case where a Webasto
charger talks to a *different* OCPP server and we can only reach it over Modbus).

## Background — current state

The OCPP code (`src/main/kotlin/ocpp/`) is OCPP 1.6J and consists of:

- `OcppMessages.kt` — a fairly complete serializable message model (Core + Smart Charging +
  Trigger + Firmware + Reservation). The strongest part; reused mostly as-is.
- `OcppServer.kt` — Ktor `/ocpp/{chargePointId}` (+ `/ocpp/1.6/{chargePointId}`) WebSocket
  endpoint; parses the `[type, id, action, payload]` frame format and dispatches inbound CALLs.
- `OcppSessionManager.kt` — in-memory session registry; inbound handlers; outbound
  RemoteStart/Stop/Reset.

### Gaps that block the goal

1. **Disconnected from the system.** `configureOcppServer()` instantiates its own
   `OcppSessionManager` inline. Not in DI; no reference to `World`, the `Charger` interface,
   or `EnergyManager`. The EMS cannot use OCPP to control anything today.
2. **No power throttling.** No send method for `SetChargingProfile` (only RemoteStart/Stop/Reset).
3. **No request/response correlation.** `handleCallResult`/`handleCallError` are empty stubs;
   outbound sends return `Accepted` optimistically without awaiting the charger's reply.
4. **Likely subprotocol handshake bug.** `install(WebSockets)` is commented out in
   `OcppServer.kt`; nothing negotiates/echoes `Sec-WebSocket-Protocol: ocpp1.6`. Real chargers
   often refuse the connection without it.
5. **No webpage** for OCPP config/status (only `/status` EMS topology and `/` "Hello World").
6. **Smaller:** `println` instead of the project's `klogging`; `OcppConfig.heartbeatInterval`/
   `connectionTimeout` defined but unused (interval hard-coded 300); BootNotification accepts any
   charger; no persistence; `encodeDefaults=true` emits `"dummy":null` on empty payloads.

### Existing infra relevant to the design

- Only DB today is **H2 in-memory** (`jdbc:h2:mem:test`) used for a demo `Users` table via
  Exposed. No real persistence.
- Config is static `config.yaml` via Hoplite — cannot hold runtime-editable settings.
- `Charger` interface: `update()`, `getState(): DeviceUpdate<ChargerState>?`,
  `setMaxChargerPower(power: Watt)`. `Webasto` implements it; `World.fromConfig` builds chargers
  from `config.devices.charger`. `SurplusPriorityStrategy` drives `setMaxChargerPower`.

## Decisions (from brainstorming)

- **Integration:** Approach A — `OcppCharger` is a first-class `Charger`, EMS-controlled,
  with runtime capability detection and graceful fallback to session-only (Webasto handles
  power). Webasto module retained.
- **Charger hardware:** generic / any OCPP 1.6J charger → design defensively, detect
  capabilities at runtime.
- **Webpage configures:** charge-point allow-list, authorization idTags, charging defaults
  (+ EMS auto-control toggle), and manual session controls (start/stop/reset actions).
- **Access:** LAN-only, no auth (matches the existing `/status` page; OCPP endpoint open on LAN).
- **Persistence:** introduce **SQLite** (via Exposed, replacing H2 in-memory) as the real
  database for all non-time-series data. **ClickHouse is deferred** to a later, separate spec
  (time-series meter-value history + charts). High-frequency meter samples stay in memory for now.

## OCPP capability notes (protocol facts driving the design)

- **Read active power:** `MeterValues` pushed by the charger on `MeterValueSampleInterval`;
  `Power.Active.Import` measurand = current active power. Can be forced via `TriggerMessage`.
  Some chargers report only `Energy.Active.Import.Register` (derive power by differencing) — out
  of scope for v1; v1 treats absence of `Power.Active.Import` as "not power-readable".
- **Set max power:** `SetChargingProfile` with `chargingRateUnit = W` or `A` and a
  `chargingSchedulePeriod.limit`. Requires the charger's **SmartCharging** feature profile.
- Both are charger-dependent → runtime capability detection with fallback.

## Architecture

### 1. Persistence — SQLite via Exposed

Replace the H2 in-memory connection in `Databases.kt` with a file-based SQLite connection
(`jdbc:sqlite:<path>`; add the SQLite JDBC dependency; DB file path comes from config).

New Exposed tables + services:

- **`OcppChargePoints`** — allow-list and identity: `chargePointId` (PK), `accepted` (Boolean),
  last-seen `vendor`/`model`/`firmwareVersion`, capability flags (`smartChargingSupported`,
  `powerImportSeen`), `lastBootAt`, `lastHeartbeatAt`.
- **`OcppIdTags`** — authorized idTags: `idTag` (PK), `status` (AuthorizationStatus),
  `expiryDate` (nullable). Consulted by `Authorize` and `StartTransaction`.
- **`OcppChargerSettings`** — per charge point: `chargePointId` (PK/FK), `maxChargingCurrentA`
  (or power), `emsAutoControl` (Boolean).
- **`OcppTransactions`** — *completed* transaction records (discrete, not time-series):
  `transactionId`, `chargePointId`, `connectorId`, `idTag`, `meterStart`, `meterStop`,
  `startTime`, `stopTime`, `stopReason`. Powers the page's session-history list.

Live high-frequency `MeterValues` power samples are kept **in memory** (latest value per
connector). Persisting the full time-series is deferred to the ClickHouse spec.

### 2. `OcppService` (DI singleton — refactor of `OcppSessionManager`)

- Holds in-memory live session/connector/transaction state; exposes a `StateFlow<OcppState>`
  consumed by the webpage WS and by `OcppCharger`.
- Inbound handlers consult SQLite (allow-list, idTags), persist completed transactions on
  StopTransaction, and use `klogging` (no `println`).
  - `BootNotification`: look up / create the charge-point record; `Accepted` only if allow-listed
    (configurable default-accept for first-time discovery so unknown chargers appear in the UI as
    pending — see Error handling). Kick off capability detection.
  - `Authorize` / `StartTransaction`: validate idTag against `OcppIdTags`.
  - `StatusNotification` / `MeterValues`: update live state; set `powerImportSeen` when
    `Power.Active.Import` first observed.
- **Request/response correlation** for outbound CALLs: a `ConcurrentHashMap<uniqueId,
  CompletableDeferred<JsonObject>>`; `handleCallResult`/`handleCallError` complete/except the
  deferred; sends `await` with a timeout (from `OcppConfig`) and return the real charger response.
- Outbound methods needed by page/EMS: `setChargingProfile`, `getConfiguration`,
  `changeConfiguration`, `triggerMessage`, plus existing `remoteStart`/`remoteStop`/`reset`.
- **Capability detection** per charge point: on Boot, send `GetConfiguration` and check
  `SupportedFeatureProfiles` for `SmartCharging`; independently flip `powerImportSeen` when a
  `Power.Active.Import` sample arrives. Persisted on the charge-point record and surfaced in the UI.

### 3. `OcppCharger : Charger` (in `devices/charger/`)

- Constructed with a `chargePointId` + the `OcppService`.
- `update()` / `getState()` → latest power from live state (`ChargerState(currentPower)`),
  `null` until the first MeterValue arrives.
- `setMaxChargerPower(power: Watt)` → if the charge point is power-control-capable
  (`smartChargingSupported`), send `SetChargingProfile` (prefer `W`, fall back to `A` using the
  grid voltage convention `value/230` as Webasto does); otherwise a logged no-op (the fallback
  where a Webasto entry handles power).
- Config: new `ChargerType.OCPP`. The `Charger` config entry uses a `chargePointId` (the value in
  the connection URL) instead of `host`; `host` becomes nullable / variant-specific. `connectorId`
  defaults to 1. Registered in `World.fromConfig`. `SurplusPriorityStrategy` throttles it exactly
  like Webasto.

### 4. `OcppServer.kt` (Ktor endpoint)

- Drop the dead commented `install(WebSockets)`; rely on the shared plugin from
  `configureSockets`.
- **Negotiate/echo the `ocpp1.6` subprotocol** on the charger WebSocket route.
- Delegate to the injected `OcppService` (no inline instantiation).
- Keep `/ocpp/{chargePointId}` (and the `/ocpp/1.6/{chargePointId}` alias) for charger connections.

### 5. Webpage

Follows the existing `/status` pattern: a static HTML page served from resources + a live
WebSocket + a small REST API.

- **Routes (namespaced under `/ocpp-ui` to avoid colliding with `/ocpp/{chargePointId}`):**
  - `GET /ocpp-ui` — the page.
  - `GET /ocpp-ui/ws` — live `OcppState` push.
  - `GET/POST/DELETE /ocpp-ui/api/...` — config CRUD (allow-list, idTags, charging defaults/
    auto-toggle) + manual action endpoints (start/stop/reset).
- **View:** connected charge points; per-connector status; active transaction; live power;
  capability flags (SmartCharging / power-readable); recent completed sessions.
- **Configure:** allow-list add/accept/reject; idTag management; charging defaults + EMS-auto
  toggle.
- **Actions:** remote start/stop, reset.

### 6. Dependency injection

`AppModule`/`AppComponent` provide `OcppService` (with its DB services). `configureOcppServer`
and the webpage routes take the injected `OcppService`. `World.fromConfig` gains access to
`OcppService` so it can build `OcppCharger` instances for `ChargerType.OCPP` entries.

## Data flow

1. Charger connects to `/ocpp/{chargePointId}` (subprotocol `ocpp1.6`) → `OcppService` registers
   the session, looks up/creates the SQLite charge-point record, runs capability detection.
2. Charger pushes `StatusNotification` / `MeterValues` → live state updates → `StateFlow` →
   webpage WS + `OcppCharger.getState()`.
3. `DataCollector` polls `OcppCharger.update()` like any device; `EnergyManager` runs
   `SurplusPriorityStrategy`, which calls `OcppCharger.setMaxChargerPower()` →
   `SetChargingProfile` (if capable).
4. Operator uses `/ocpp-ui` to edit allow-list/idTags/defaults (→ SQLite) or trigger manual
   start/stop/reset (→ outbound CALL, awaited via correlation).
5. On `StopTransaction`, the completed transaction is persisted to `OcppTransactions`.

## Error handling

- **Unknown charge point connects:** create a `pending` (not accepted) record so it shows in the
  UI; `BootNotification` returns `Pending`/`Rejected` per allow-list policy. Default-accept on
  first discovery is configurable to ease initial setup on a trusted LAN.
- **Outbound CALL timeout / CALL_ERROR:** the awaiting deferred completes exceptionally; the
  caller (EMS or webpage) logs and surfaces failure; EMS treats a failed `SetChargingProfile`
  as "power control unavailable" for that cycle.
- **Charger lacks SmartCharging / never sends `Power.Active.Import`:** `OcppCharger` reports
  `null`/no-op for power; the EMS skips OCPP power control; a parallel Webasto entry (if
  configured) handles power.
- **Connection drop:** session unregistered; live state for that charge point cleared/marked
  offline; in-flight deferreds for it are cancelled.
- **SQLite unavailable at boot:** fail fast on startup (the DB is required infra).

## Testing

- **Message round-trip / protocol:** unit tests for frame parse/serialize, subprotocol echo,
  and request/response correlation (CALL → CALL_RESULT/CALL_ERROR/timeout) using a fake
  WebSocket session.
- **Inbound handlers:** Boot/Authorize/Start/Stop/Status/MeterValues against an in-memory or
  temp-file SQLite DB; assert allow-list/idTag enforcement and transaction persistence.
- **Capability detection:** simulate GetConfiguration responses with/without SmartCharging and
  MeterValues with/without Power.Active.Import; assert flags.
- **`OcppCharger`:** `setMaxChargerPower` sends `SetChargingProfile` when capable and no-ops when
  not; `getState` reflects latest MeterValue.
- **EMS integration:** `SurplusPriorityStrategy` drives `OcppCharger` like Webasto (extends the
  pending realistic cascade test where feasible).
- **Webpage:** API endpoint tests for config CRUD and manual actions; page-serve smoke test.

## Build order (phasing)

- **Phase 1 — Foundation & protocol:** SQLite (replace H2) + config tables/services; `OcppService`
  in DI; subprotocol negotiation; request/response correlation; `klogging`; capability detection.
- **Phase 2 — EMS integration:** `OcppCharger` + `ChargerType.OCPP` config + `World.fromConfig`
  wiring + capability fallback; `SetChargingProfile` send path.
- **Phase 3 — Webpage:** `/ocpp-ui` page, live WS, config CRUD + manual action API.
- **Later (separate spec):** ClickHouse time-series meter-value history + history charts
  (see `history-charts-clickhouse` memory note).

## Out of scope (v1)

- ClickHouse / time-series meter history and charts.
- Deriving power from `Energy.Active.Import.Register` differencing.
- Authentication on the webpage or OCPP endpoint (LAN-only trust).
- OCPP 2.0.1.
- Multi-connector load balancing beyond per-charge-point SetChargingProfile.
