# BMW CarData — car State-of-Charge display

**Date:** 2026-06-06
**Status:** Server implemented (2026-06-06); pending live device-code bring-up, payload-shape confirmation, and the Android app companion.
**Scope:** Server (`ems-server`) end-to-end + a companion render task in the separate Android app repo.

## Problem

The app's main screen shows charger power, but nothing about the *car's* battery level. The OCPP
charger does not provide it: we added a per-connector "seen measurands" diagnostic to `/ocpp-ui`
(2026-06-06) and confirmed empirically that the BMW i5 charger/car combo **never sends the OCPP `SoC`
measurand** — AC charging over IEC 61851 has no channel for it. The charge level therefore has to come
from the car's own cloud.

The car is a **BMW i5**. BMW's official **CarData** owner-data programme exposes State-of-Charge via an
MQTT streaming service. This feature streams that SoC and surfaces it in the app as a read-only
percentage.

## Goal & non-goals

**Goal:** Show the car's live battery percentage in the app, sourced from BMW CarData over MQTT.

**Non-goals (YAGNI — explicitly out of scope):**
- No EMS control coupling. SoC does **not** feed `SurplusPriorityStrategy` or any control decision.
- No remote control (no start/stop charging, no charge-target setting) via BMW.
- No CarData **REST** pull path and therefore no 50-calls/day quota handling. MQTT streaming only.
- No other descriptors (range, location, doors, tyres). SoC percentage only.
- No multi-vehicle support. A single configured VIN.

## Background: BMW CarData facts that shape the design

- **Auth:** OAuth 2.0 **Device Code Flow** (RFC 8628). The owner generates a `client_id` in the CarData
  portal; a one-time device approval at a verification URL yields a refresh token. The server then
  mints a short-lived **ID token (~1 hour)** from the refresh token.
- **MQTT broker:** `customer.streaming-cardata.bmwgroup.com:9000`, **TLS**, **MQTT 5**.
  - Username = **GCID** (the BMW account id, obtained from the portal / token response).
  - Password = the current **ID token**.
  - Only **one MQTT connection per account (GCID)** at a time. Acceptable: this server is the sole
    consumer (no Home Assistant on the same account).
- **Token lifetime drives reconnection:** because the MQTT password *is* the ~1 h ID token, the client
  must proactively reconnect with a freshly-minted token before expiry. We own this loop regardless of
  any library auto-reconnect.
- **Portal prerequisite (manual, one-time, by the owner):** in the CarData portal, generate the
  `client_id` and tick the SoC descriptor under "Configure data stream". Without this the stream
  carries no SoC.
- **Descriptor:** the exact SoC descriptor name is confirmed against the portal's descriptor list
  during implementation (the i5 is not a "Neue Klasse" vehicle, so the Neue-Klasse-specific
  `stateOfCharge.displayed` may not apply). The parser is written to be tolerant (see Parsing).

## Architecture

A new CarData integration modelled on `OcppService` — a DI singleton background **service**, **not** a
polled `World` device, because CarData is *push* (MQTT) rather than *pull*. The service owns the BMW
connection and exposes the latest SoC as a `StateFlow<Int?>`. `EnergyManager` reads that value when it
builds `EMSState`; the WebSocket layer pushes it to the app.

All new server code lives under `src/main/kotlin/cardata/`.

```
                 (refresh token, persisted)
CarDataTokenStore ───────────────┐
        ▲                         │
        │ load/save               ▼
        └──────────────  CarDataAuth ── ID token (~1h) ──┐
                          (device-code bootstrap +        │
                           refresh)                       ▼
                                              CarDataMqttClient (ktor-mqtt, MQTT5/TLS)
                                              ── subscribe VIN stream ──> BMW broker
                                                         │ parse SoC descriptor
                                                         ▼
                                              CarDataService.socFlow: StateFlow<Int?>
                                                         │
                          EnergyManager.buildEMSState()  │  EnergyManager.carSocFlow
                          reads socFlow.value ───────────┤  (re-exposed for the socket)
                                                         ▼
                          EMSState.carCharge      Sockets.kt collects carSocFlow
                                                  -> Message.CarStateUpdate(soc)  -> app
```

## Components

All under `src/main/kotlin/cardata/` unless noted.

### `CarDataConfig` (in `config/Config.kt`)
New `cardata:` block, loaded by Hoplite like the rest of `Config`:
- `enabled: Boolean` (default false) — gates the whole integration. When false, nothing starts and
  `carCharge` stays null.
- `clientId: String` — from the CarData portal.
- `vin: String` — the vehicle to subscribe to.
- `socDescriptor: String` — the telemetry descriptor name carrying SoC (configurable so a portal/catalog
  change doesn't require a code change).
- `brokerHost: String` (default `customer.streaming-cardata.bmwgroup.com`), `brokerPort: Int`
  (default `9000`) — overridable but defaulted.

Config validation: when `enabled`, `clientId`/`vin`/`socDescriptor` must be non-blank (fail fast at
startup with a clear message).

### `CarDataTokenStore` (Exposed/SQLite)
A new table on the **existing** SQLite database (the one `ocpp/db` already uses), following the
`OcppStores`/`OcppTables` pattern. Persists the **refresh token** (and optionally the last GCID) so the
one-time device approval survives restarts. Single-row store keyed by `clientId`.
- `getRefreshToken(clientId): String?`
- `saveRefreshToken(clientId, refreshToken, gcid)`
- `init()` creates the table.

### `CarDataAuth`
Owns the OAuth2 device-code flow against BMW's token endpoints (URLs confirmed against the CarData API
docs during implementation; injected/constants in one place).
- **Bootstrap** (`ensureAuthorized()`): if `CarDataTokenStore` has no refresh token, start device
  authorization, **log the verification URL + user code at WARN**, poll the token endpoint until the
  owner approves (or a timeout), then persist the refresh token + GCID. If a refresh token exists, skip.
- **Refresh** (`currentIdToken(): String`): exchange the refresh token for a fresh ID token; cache it
  with its expiry; refresh proactively when within a safety margin (e.g. < 5 min remaining). Persist a
  rotated refresh token if the response returns one.
- Pure helpers (HTTP-response → token model, expiry math) are separated from the HTTP/IO so they can be
  unit-tested without a network.
- Operational note: if the refresh token ever lapses (e.g. long downtime), refresh fails; the service
  re-enters bootstrap and logs the approval prompt again.

### `CarDataMqttClient`
Thin abstraction over **ktor-mqtt** so the library choice stays swappable (the whole reason to wrap it).
Interface: start/stop, and a `StateFlow<CarState>` output. Responsibilities:
- Connect to `brokerHost:brokerPort` over **TLS, MQTT 5**, username = GCID, password =
  `auth.currentIdToken()`.
- Subscribe to the VIN's stream topic (topic format confirmed during implementation).
- On each message, run the **SoC parse function** (below) and, when it yields a value, update
  `socFlow`/`CarState`.
- **Token-aware reconnect loop:** reconnect with a freshly-minted ID token before the ~1 h token
  expires, and on any disconnect/error with bounded backoff. This loop is ours, not the library's.

`CarState(socPercent: Int?, lastUpdated: Instant?)`.

### Parsing (pure function)
`parseSoc(payload: String, descriptor: String): Int?` — extracts the SoC percentage for the configured
descriptor from a CarData MQTT JSON payload. Tolerant: returns null (rather than throwing) on unknown
shapes, missing descriptor, or non-numeric values; rounds to an `Int` percentage. This is the most
heavily unit-tested piece (fed sample BMW payloads).

### `CarDataService` (DI singleton)
Wires `CarDataTokenStore` + `CarDataAuth` + `CarDataMqttClient`. Provided via `di/AppModule.kt`,
constructed only meaningfully when `config.cardata.enabled`.
- `val socFlow: StateFlow<Int?>` — latest SoC percent, or null when disabled/unknown/not-yet-received.
- `start()` — called from `Application.kt` (guarded by `config.cardata.enabled`), mirroring how
  `OcppService` is started. Runs bootstrap, then the MQTT connect/reconnect loop in a supervised scope.
- When disabled: `start()` is a no-op and `socFlow` stays null, so downstream code degrades cleanly.

## Data flow & protocol changes

### `EMSState`
Add `carCharge: Int?` (percent SoC). `EnergyManager.buildEMSState()` sets
`carCharge = carDataService?.socFlow?.value`. `EnergyManager` gets an **optional** `carDataService`
(null when the feature is disabled or in tests).

### App-facing message (server `Messages.kt`)
The app currently receives only power values (`PowerUsageUpdate`) plus `ModeUpdate` /
`ChargerControlUpdate`. A percentage is not a power, so add a new message:

```kotlin
@Serializable @SerialName("CarStateUpdate")
data class CarStateUpdate(val soc: Int?) : Message()
```

`@SerialName` is mandatory and **must stay in sync with the app's `WsMessage.kt`** (per the existing
note in `Messages.kt`).

### Push (server `Sockets.kt`)
`EnergyManager` exposes `carSocFlow: StateFlow<Int?>` (re-exposing the service's flow, or null-flow when
disabled), so `Sockets.kt` reads only `EnergyManager` (consistent with `modeFlow` /
`chargerControlFlow`). The socket:
- collects `carSocFlow` and sends `CarStateUpdate(soc)` **change-only** while authenticated;
- sends the current value once **on successful auth** (same pattern used for mode / charger control,
  whose initial emissions are dropped pre-auth).

### App companion (separate repo — tracked, not built here)
In the Android app: add the matching `CarStateUpdate` to `WsMessage.kt`, hold the latest SoC in UI
state, and render the car battery % near the charger card. Out of this repo's scope; captured as a
follow-up.

## Error handling & degradation

- **Feature disabled / not yet authorized / no message yet:** `socFlow` is null → `carCharge` null →
  no `CarStateUpdate` content beyond `soc=null`; app shows "—". No errors surfaced to control logic.
- **Auth failure / token lapse:** logged; service re-enters bootstrap and logs the approval prompt.
  Control logic is unaffected (SoC is display-only).
- **MQTT disconnect:** token-aware reconnect with bounded backoff; last-known SoC remains displayed
  until a new value arrives (consistent with how other values behave; staleness here is benign).
- **Parse failure:** `parseSoc` returns null for that message; the previous value is kept.
- The integration is fully isolated from `EnergyManager`'s control tiers — a CarData outage never
  affects battery/charger/heat-pump steering.

## Testing

Unit tests (no live network/broker):
- **`parseSoc`** — the core: sample BMW CarData payloads for the configured descriptor → expected
  percent; unknown descriptor → null; malformed/non-numeric → null; rounding.
- **`CarDataAuth` pure helpers** — token-response parsing and expiry/refresh-margin logic (mock HTTP
  responses); rotated-refresh-token persistence.
- **`CarDataTokenStore`** — save/get round-trip on a fresh test DB (reuse the OCPP `TestDb` helper).
- **`EnergyManager.buildEMSState`** — includes `carCharge` from a stubbed `socFlow`; null when no
  service.
- **Sockets** — emits `CarStateUpdate` on change and on auth (mirroring existing socket tests if
  present).

Not unit-tested (manual/integration): the live TLS MQTT connection to BMW, end-to-end device-code
approval. Verified by hand against the real account once.

## Deployment notes

- New `cardata:` config in `config.yaml` (secrets: `clientId`, `vin` — same handling as existing device
  credentials).
- One-time owner setup in the CarData portal (client_id + SoC descriptor enabled) **before** first run.
- First start is interactive once: approve at the URL shown in `docker logs`; the refresh token then
  persists in the existing SQLite volume.
- Server redeploy via the usual image rebuild; the app render ships separately with a new APK.
