# Runtime device & settings configuration (DB-backed) + config API

**Status:** Phases 1–3 implemented (2026-06-14). Phase 3 (live hot-reload) is code-complete
and unit-tested but **not yet hardware-verified** (the battery 803 hand-back on swap).

## Goal

Today every device and setting is read once at startup from `config.yaml` (Hoplite →
immutable `Config`). This adds the ability to configure devices and settings at runtime
through a clean REST API — consumed by both a new web page and the Android app — with
changes applied **live, without a restart**.

The yaml file stays a fully supported option. A new top-level switch decides whether the
file or the database is the source of truth:

```yaml
configSource: file        # default — behaves exactly like today
# configSource: database  # effective config lives in SQLite, editable via the API at runtime
```

Decisions taken up front (2026-06-14):

| Decision | Choice |
|----------|--------|
| Apply model | **Full hot-reload** — rebuild the device graph on change, never restart |
| File ↔ DB relationship | **Explicit `configSource` switch**, DB **seeded from yaml** on first use |
| API scope | **Devices + tuning** (see [Scope](#scope)) |

## Background — why this is non-trivial

`Config` is an immutable Hoplite object injected straight into `World`, `DataCollector`,
`EnergyManager`, `OcppService`. `World.fromConfig()` builds all hardware connections
(Modbus/HTTP/OCPP) **once**, and `DataCollector`/`EnergyManager` hold `world` as a
constructor `val`. Hot-reload therefore needs two new capabilities:

1. a mutable, observable **source of truth** for the effective config, and
2. a **swappable live `World`** so device connections can be rebuilt and the old ones torn
   down cleanly.

The riskiest part is tearing down live Modbus connections around battery control: the
battery must be handed back to the inverter (write `803` to register `40151`) before its
connection is dropped, exactly like the existing graceful-shutdown hook, because the SMA
watchdog is too slow (≥15 min) to recover a stale setpoint.

## Scope

In scope (editable via the API in `database` mode):

- **Device topology + connection**: grid, solar, charger, battery, heat pump, car —
  add / remove / re-IP / retype.
- **Tuning / behavioural settings**: charging-current min/max, OCPP accept flags
  (`acceptUnknownChargePoints`, `acceptUnknownIdTags`, …), poll interval, strategy
  selection.

Out of scope — these stay **yaml-only / bootstrap** (the API neither reads nor writes them):

- `database.path`, `clickhouse`, the HTTP listen port, `websocket` admin credentials.
- `refreshThreads` — sizes a fixed thread pool created at startup; kept yaml-only so there
  is no editable field that silently needs a restart.

## Design

### Components (all new unless noted)

```
config/
├── Config.kt            # (changed) add `configSource: ConfigSource = file`
├── ConfigService.kt     # DI singleton: StateFlow<Config> source of truth; seed; update+reload
├── ConfigStore.kt       # Exposed: single-row JSON document of the configurable subtree
├── ConfigValidation.kt  # reusable validator (rules extracted from World.fromConfig)
└── ConfigApi.kt         # configureConfigApi(): REST + /config-ui page, behind auth-basic
devices/World.kt         # (changed) add suspend shutdown() that releases battery + disconnects
WorldHolder.kt           # @Volatile var world, exposed as StateFlow; the swap point
resources/config.html    # editor web page (mirrors ocpp.html)
```

### 1. `ConfigService` — source of truth

Holds `MutableStateFlow<Config>` = the **effective** config currently in use.

- **Boot:** load yaml as today.
  - `configSource == file` → serve the file config; the StateFlow is effectively immutable
    and the API rejects writes (read-only mode).
  - `configSource == database` → load the document from `ConfigStore`, **seeding it from the
    yaml config if the DB row is empty** (idempotent first-run migration). Serve the DB value.
- **`update(newConfig)`:** validate → persist to `ConfigStore` → trigger `WorldHolder` reload
  → emit on the StateFlow. Atomic: on validation failure nothing is persisted or swapped.

### 2. `ConfigStore` — persistence

Mirrors the existing `ocpp/db` pattern (Exposed + SQLite `ems.db`, `dbQuery` helper). One
table holding the configurable subtree as a **single JSON document**:

```
ems_config(id PK = 1, json TEXT, version INT, updated_at LONG)
```

Rationale for one JSON blob rather than per-field tables: every config class is already
`@Serializable`, so the stored document and the API payloads are the same shape as the
domain model — no per-field columns, no schema migrations when a device type gains a field,
and trivial whole-document atomic replace. `version` lets us evolve the document later.

### 3. `WorldHolder` — swappable device graph

Wraps `@Volatile var world: World` (exposed as `StateFlow<World>`). `DataCollector` and
`EnergyManager` read the live `world` through the holder **each tick** instead of capturing
it once in the constructor.

Reload (serialised behind a `Mutex`, applied between the 5s ticks):

1. Build the new `World` from the new effective config (`World.fromConfig`).
2. Swap the holder reference.
3. **Gracefully tear down the old `World`**: `battery.releaseToInverter()` (the `803`
   hand-back) and Modbus `disconnect()` for any device that was replaced or removed.

OCPP is unaffected by a swap: the charger dials in to a persistent `/ocpp/{id}` WebSocket
endpoint owned by `OcppService` (a DI singleton independent of `World`); the `OcppCharger`
device only re-binds its `chargePointId`. Care is needed only if the `chargePointId` itself
changes.

### 4. `ConfigValidation`

Extract the rules currently inline in `World.fromConfig` into a reusable validator:

- at most one charger, one battery, one heat pump;
- required fields per type (`host` for Modbus/HTTP devices; `chargePointId` for OCPP);
- unique device names.

Used by `ConfigService.update` so the **API rejects bad config before persisting or
swapping**, returning structured per-field errors (HTTP 422).

### 5. REST API — `configureConfigApi()`

Behind the existing `auth-basic` (same Basic-auth realm as `/ocpp-ui`). Designed as a
standalone API (automation-friendly), not just for the UI.

```
GET    /api/config                              → { source, version, config }
PUT    /api/config                              → replace whole document (atomic, validated)

GET/PUT  /api/config/grid

GET    /api/config/devices/{kind}               kind ∈ solar|charger|battery|heatPump|car
POST   /api/config/devices/{kind}               add one
PUT    /api/config/devices/{kind}/{name}        update by name
DELETE /api/config/devices/{kind}/{name}        remove by name

GET/PUT  /api/config/settings                   ocpp flags, charging-current, poll interval, strategy
```

Semantics:

- **`file` mode:** all mutations → `409 Conflict` with a clear "config is file-sourced,
  read-only" body. Reads work normally.
- **`database` mode:** mutation → validate → persist → hot-reload → respond with the new
  effective document.
- **Validation failure:** `422` with per-field detail.
- Every successful read/mutation returns the full effective document plus `source` and
  `version`, so a client always knows what is actually in effect.

A `GET /config-ui` route serves `config.html`, an editor page mirroring the existing
`/ocpp-ui` + `ocpp.html` precedent. The Android app uses the same `/api/config` endpoints.

## Secrets

Device credentials (BMW CarData `clientId` / `vin`) would be stored in SQLite in plaintext —
the same trust model as today's `config.yaml` and the existing CarData token store. No
encryption planned. `websocket` admin credentials stay out of the API scope entirely.

## Delivery plan

Three independently shippable, testable phases converging on full hot-reload. The high-risk
live-teardown work is isolated in the last phase so the plumbing can be verified first.

**Phase 1 — source of truth + persistence (no reload yet)** — ✅ done (2026-06-14)
- `ConfigService`, `ConfigStore`, `configSource` switch, seed-from-yaml on first DB use.
- In `database` mode the system reads its config from the DB at boot but otherwise behaves
  like today. Verify read/seed path; no behaviour change in `file` mode.
- Implemented as: `config/ConfigStore.kt` (single-row `ems_config` JSON document),
  `config/ConfigService.kt` (`resolve()` — seed + bootstrap-override), `configSource` enum +
  `withBootstrapFrom` in `config/Config.kt`, wired in `Application.kt` before DI is built.
  The stored document is the **whole** `Config`; bootstrap fields are overridden from the
  file on load (`withBootstrapFrom`), so the DB can never change `database.path`, `clickhouse`,
  `websocket`, `refreshThreads`, or `configSource`. Tests: `ConfigStoreTest`, `ConfigServiceTest`.
  Note: boot opens a short-lived `Database` for the config read in addition to the DI-owned one
  (same SQLite file, sequential) — candidate to unify in a later cleanup.

**Phase 2 — validation + API + web page (restart to apply)** — ✅ done (2026-06-14)
- `config/ConfigValidation.kt` (`validate()`/`validatedOrThrow()`, `ValidationError`,
  `ConfigValidationException`); `World.fromConfig` now delegates to it (single source of truth).
- `ConfigService.update()` — rejects writes in `file` mode (`ConfigReadOnlyException`),
  forces bootstrap fields from the file, validates, persists (revisioned), publishes on
  `configFlow`. `ConfigStore` gained a revision counter; `ConfigService` exposes
  `source`/`version`/`current()`/`configFlow`.
- `config/ConfigApi.kt` (`configureConfigApi`) behind `auth-basic`: `GET/PUT /api/config`,
  `GET/PUT /api/config/grid`, `GET/PUT /api/config/settings`, `GET /api/config/devices`,
  per-kind `GET/POST/PUT/DELETE /api/config/devices/{kind}[/{name}]`. `file` mode → 409,
  validation → 422 (per-field), success → full effective document + `source` + `version`.
- `resources/config.html` editor page (structured grid/settings/device forms + raw full-config
  editor; read-only banner in `file` mode).
- **Schema-driven forms** (2026-06-14): `config/ConfigSchema.kt` derives a form schema from the
  `@Serializable` descriptors of the device classes (field names, primitive/enum/object kinds,
  enum options, required-vs-optional from defaults/nullability); served at
  `GET /api/config/schema`. `config.html` renders grid + device add/edit forms generically from
  it, so a new device type or field — or a whole new device kind — needs **no frontend change**.
  Conditional requiredness a descriptor can't express (charger `host` for Webasto vs
  `chargePointId` for OCPP) stays in `Config.validate` and surfaces as 422. Test: `ConfigSchemaTest`.
- Added editable tuning fields `strategy` (StrategyType) and `pollIntervalMs` to `Config`,
  wired live at boot (`provideStrategy` resolves from config; main loop uses `pollIntervalMs`).
- Tests: `ConfigApiTest` (auth, GET, file-mode 409, db-mode PUT round-trip, add device,
  validation 422); `ConfigServiceTest`/`ConfigStoreTest` extended. Full `./gradlew build` green.
- Changes take effect on the next restart; the live device graph is still the boot snapshot.

**Phase 3 — live hot-reload** — ✅ code-complete (2026-06-14), ⚠️ hardware verification pending
- `devices/WorldHolder.kt`: `@Volatile current` + `swap()` returning the old graph. The DI
  singleton; `DataCollector` and `EnergyManager` read the live world through it.
- `DataCollector`/`EnergyManager` primary constructors now take the holder (+ a `() -> Config`
  provider for the manager); secondary constructors keep the old `(world, config, …)` form so
  every existing test compiles unchanged. `EnergyManager` re-reads `world`/`config` per access,
  so device topology, connection details and per-tick config (charging current) hot-reload.
  Strategy and `refreshThreads` stay boot-fixed (documented as restart-to-change).
- `World.shutdown()`: hands the battery back to the inverter (803). Orphaned Modbus sockets on
  replaced devices are left to the driver's reconnect/GC (noted follow-up, not plumbed).
- Reload orchestration in `Application.kt`: a single `Mutex` serialises the poll/control tick
  against graph rebuilds; a coroutine collects `ConfigService.configFlow` (`drop(1)`), rebuilds
  `World`, swaps it in, and `shutdown()`s the old graph. Main loop now uses the live
  `pollIntervalMs`. Reload failures are logged and keep the previous graph.
- Tests: `WorldHotReloadTest` (holder swap, `shutdown()` releases battery, `EnergyManager`
  steers the swapped-in battery). Full `./gradlew build` green.
- **Still required: hardware verification** — confirm on real hardware that the 803 hand-back
  fires on swap before the old connection drops, and that a re-IP/retype reconnects cleanly.
  Reference: the process shutdown hook and the battery hand-back work.

## Files touched

New: `config/ConfigService.kt`, `config/ConfigStore.kt`, `config/ConfigValidation.kt`,
`config/ConfigApi.kt`, `WorldHolder.kt`, `resources/config.html`.

Changed: `config/Config.kt` (add `configSource`), `devices/World.kt` (add `shutdown()`),
`DataCollector.kt` + `EnergyManager.kt` (read world via holder), `di/AppModule.kt` +
`di/AppComponent.kt` (provide `ConfigService` / `WorldHolder`; world via holder),
`Application.kt` (boot-time source selection, mount `configureConfigApi`, loop reads live
world).

Tests: store JSON round-trip; validation rejects (duplicate names, >1 charger, missing
host); seed-from-yaml on empty DB; `file`-mode writes rejected; hot-reload swaps the world
and releases the old battery.

## Resolved decisions

- **`refreshThreads`:** stays bootstrap / yaml-only and is **not** part of the API surface.
  It sizes a fixed thread pool created at startup; rather than reject a write mid-session, we
  keep it out of the editable set entirely so there is no "this field silently needs a
  restart" confusion. Listed alongside the other out-of-scope bootstrap fields.
- **Strategy selection:** exposed in `settings` as a name chosen from the known strategies —
  `SurplusPriority` and `SimpleGridCompensation`. `provideStrategy` in DI changes from a
  fixed `SurplusPriority` to resolving the strategy from `ConfigService`. Hot-reloadable like
  the rest of `settings`.
- **Poll interval:** promoted to a real config field (default `5000` ms) and made live —
  the main loop reads it from `ConfigService` each tick instead of the hardcoded
  `delay(5_000)`. Small change, included in the first cut so `settings` is coherent.
