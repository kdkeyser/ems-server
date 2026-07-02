# Adding an OCPP Charger

The EMS server can manage and control a car charger that speaks **OCPP 1.6J**. The charger
connects *to* the server (the server is the Central System), the server reads the charger's power
and — when the charger supports it — throttles its charging current to follow solar surplus, exactly
like the built-in Webasto Modbus charger. A local web page at `/ocpp-ui` lets you configure and
monitor everything.

## 1. Add the charger to `config.yaml`

Edit `src/main/resources/config.yaml` and add an entry under `devices.charger`:

```yaml
devices:
  charger:
    - type: OCPP
      name: Garage Charger
      chargePointId: CP01        # MUST match the id the charger uses in its WebSocket URL
      connectorId: 1             # optional, defaults to 1
      chargingCurrent:
        min: 6.0                 # amps — below this the EMS turns the charger off
        max: 32.0                # amps — EMS never requests more than this
```

### Field reference

| Field | Required | Notes |
|-------|----------|-------|
| `type` | yes | `OCPP` for an OCPP charger (vs `WebastoUnite` for the Modbus charger). |
| `name` | yes | Display name, shown in logs and the EMS topology. |
| `chargePointId` | **yes (OCPP)** | Must exactly match the `{chargePointId}` segment the charger dials (see §2). A missing value fails fast at startup. |
| `connectorId` | no | Defaults to `1`. The connector the EMS reads/controls. |
| `chargingCurrent.min` / `.max` | yes | Amps. Bounds what the surplus strategy will request. Below `min`, the charger is set to 0 A. |
| `host` | no | **Unused for OCPP** (the charger connects to us). Only the Webasto Modbus charger uses `host`. |

> Note: changes to `config.yaml` take effect on server restart (the config is loaded once at startup).

## 2. Point the charger at the server

Configure the charger's OCPP client (in its own settings UI) to connect to:

```
ws://<server-ip>:8080/ocpp/<chargePointId>
```

For the example above: `ws://192.168.1.50:8080/ocpp/CP01`.

- The server also accepts `ws://<server-ip>:8080/ocpp/1.6/<chargePointId>`.
- The charger **must** negotiate the WebSocket subprotocol **`ocpp1.6`** — virtually all OCPP 1.6J
  chargers do this automatically. The server echoes it back during the handshake.
- The server listens on port **8080**. The connection is plain `ws://` on the LAN (no TLS, no auth).

## 3. OCPP server settings (optional)

The `ocpp` section of `config.yaml` controls server-wide OCPP behavior. All fields except the first
three have sensible defaults:

```yaml
ocpp:
  enabled: true
  heartbeatInterval: 300        # seconds; value returned to chargers in BootNotification
  connectionTimeout: 60
  callTimeoutSeconds: 30        # how long the server waits for a charger's reply to a command
  acceptUnknownChargePoints: true   # auto-accept a charger the first time it connects
  acceptUnknownIdTags: true         # accept any RFID/idTag not explicitly listed
  autoProbeOnBoot: true             # on boot, ask the charger which OCPP features it supports
```

Persistent data (allow-list, idTags, charger settings, charging-session history) is stored in a
SQLite database:

```yaml
database:
  path: ems.db
```

### Allow-list and idTag behavior

- **`acceptUnknownChargePoints: true`** (default) — a charger that connects for the first time is
  automatically accepted and starts working immediately. Set to `false` to require manual approval:
  an unknown charger is recorded as *Pending* and rejected until you **Accept** it on the web page.
- **`acceptUnknownIdTags: true`** (default) — any RFID card / idTag can start a charging session. Set
  to `false` to only allow idTags you've added on the web page.

## 4. Use the web page: `http://<server-ip>:8080/ocpp-ui`

The page updates live over a WebSocket and provides:

- **Connected charge points** — per connector: status, current power, active transaction. Two
  capability pills per charger:
  - **SmartCharging** — green if the charger supports `SetChargingProfile` (required for the EMS to
    throttle it). See §5.
  - **Power read** — green once the charger has reported a `Power.Active.Import` meter value.
  - Per-charger **Max A** and **EMS auto** controls (see §6), plus **Start** and **Reset** buttons.
- **Allow-list** — every charger the server has seen; **Accept** / **Revoke** each one.
- **Authorized idTags** — add/remove idTags allowed to charge.
- **Recent sessions** — completed charging transactions (energy delivered).

## 5. Capability detection and the Webasto fallback

OCPP support varies by charger. The server detects two capabilities at runtime:

- **SmartCharging** — detected by asking the charger for its `SupportedFeatureProfiles` on boot
  (`autoProbeOnBoot`). Only if present can the EMS set a charging-current limit.
- **Power reading** — detected when the charger sends a `Power.Active.Import` measurand in its
  `MeterValues`.

**If the charger does not support SmartCharging**, the EMS cannot throttle it over OCPP:
`setMaxChargerPower` becomes a logged no-op. In that case you can run a **hybrid setup** — use OCPP
for session management/monitoring and the Webasto Modbus module for power read/throttle — by listing
both chargers:

```yaml
devices:
  charger:
    - type: WebastoUnite        # handles power read + throttle
      name: Webasto Unite
      host: 192.168.129.19
      chargingCurrent: { min: 6.0, max: 32.0 }
    - type: OCPP                # handles OCPP sessions / monitoring
      name: Garage Charger
      chargePointId: CP01
      chargingCurrent: { min: 6.0, max: 32.0 }
```

The EMS reads power from `chargers.values.firstOrNull()`, so **list the power-capable charger first**.

## 6. Per-charger settings (on the web page)

Each connected charger has two settings on `/ocpp-ui` (persisted in SQLite):

- **Max A** — caps the current the EMS will request for *this* charger, in addition to the
  config's `chargingCurrent.max`.
- **EMS auto** — when unchecked, the EMS leaves this charger alone (no automatic throttling); use
  this to charge manually/at full power regardless of solar surplus.

## 7. How control works (brief)

1. The charger connects to `/ocpp/<id>` (subprotocol `ocpp1.6`); the server registers the session
   and probes its capabilities.
2. The charger pushes `MeterValues`; the server records the live power.
3. The EMS control loop runs `SurplusPriorityStrategy` every 5 s and, if the charger is
   SmartCharging-capable and EMS-auto is on, sends a `SetChargingProfile` to cap the current to the
   available solar surplus (clamped to `[min, max]` amps and the per-charger Max A). Identical
   limits are not re-sent every tick — only on change, plus a refresh every ~60 s.
4. In solar mode, sessions are hysteresis-gated: charging starts only after the surplus has covered
   the charger minimum (`min` amps × 230 V) for ~60 s, and stops after ~5 min of sustained deficit
   (below half the minimum). While a session runs, the current never drops below `min` — the
   shortfall is imported briefly rather than chattering the car's contactor.
5. The **Start / Stop** buttons and the manual current field on `/ocpp-ui` set the same persisted
   charging intent the app uses (the control loop enacts them on its next tick); **Reset** and
   **Clear profile** talk to the charger directly.

## 8. Troubleshooting

- **Charger doesn't appear in `/ocpp-ui`** — check the WebSocket URL and that it includes the
  `chargePointId` matching `config.yaml`; confirm the charger negotiates the `ocpp1.6` subprotocol;
  check the server logs for `New OCPP connection from <id>`.
- **Charger shows as Pending / can't charge** — `acceptUnknownChargePoints` is `false`; click
  **Accept** on the web page (or set it back to `true`).
- **SmartCharging pill is grey** — the charger didn't advertise the SmartCharging profile; the EMS
  can't throttle it. Use the Webasto hybrid setup (§5) if you need surplus control.
- **Power read pill is grey** — the charger isn't sending `Power.Active.Import` meter values; some
  chargers only report cumulative energy. Power-based EMS control needs the active-power reading.
- **A charging session won't start** — if `acceptUnknownIdTags` is `false`, add the card's idTag on
  the web page.

## Out of scope (today)

- Authentication on the OCPP endpoint or web page (LAN-only, trusted network).
- OCPP 2.0.1.
- Long-term time-series meter history / charts (planned separately, ClickHouse-backed).
- Deriving power from cumulative `Energy.Active.Import.Register` when `Power.Active.Import` is absent.
