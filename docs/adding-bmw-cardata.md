# Showing the car's charge level (BMW CarData)

The app's main screen can show the BMW i5's battery percentage, streamed live from **BMW CarData** over
MQTT. This is **read-only** — the EMS only displays the value, it never controls the car. The OCPP
charger cannot provide this (AC charging has no channel for State-of-Charge), so it comes from the car's
own cloud instead.

CarData is free for the vehicle owner. The integration uses the **streaming (MQTT)** service, which is
real-time and unlimited (the separate REST API is capped at 50 calls/day and is not used here).

## One-time setup in the BMW CarData portal

You do this once, as the vehicle owner, before enabling the integration.

1. Open the **BMW CarData portal**
   (`https://bmw-cardata.bmwgroup.com`) and sign in with your BMW account.
2. Under **CARDATA API**, generate a **client ID**. Note it down — this is `cardata.clientId`.
3. Under **Configure data stream** ("Configure data stream" → load all descriptors), **tick the
   State-of-Charge descriptor** for your vehicle and save. This is `cardata.socDescriptor`. (Without
   this, the stream carries no SoC.) For the BMW i5 the displayed HV-battery charge in percent is
   `vehicle.powertrain.electric.battery.stateOfCharge.displayed`.
4. Note your vehicle's **VIN** — this is `cardata.vin`.

> Only **one MQTT connection per BMW account** is allowed at a time. If you also run Home Assistant or
> another CarData client against the same account, they will fight over the single connection — run only
> one.

## Configure the server

Set the `cardata` block in `config.yaml`:

```yaml
cardata:
  enabled: true
  clientId: "<your client id>"
  vin: "<your VIN>"
  socDescriptor: "<the SoC descriptor you enabled>"
  # brokerHost/brokerPort default to customer.streaming-cardata.bmwgroup.com:9000
```

When `enabled: false` (the default) the whole integration is dormant and the app simply shows no car
charge level.

## First start: one-time device approval

CarData uses OAuth 2.0 **device-code** authorization. The first time the server starts with CarData
enabled and no stored token, it prints a verification URL and a user code in the logs:

```
docker logs <container>     # or watch the server's stdout
```

Look for a line telling you to visit a BMW URL and enter the shown code. **Approve it once.** The
server then stores a **refresh token** in the SQLite database (the same `database.path` volume), so
subsequent restarts reconnect automatically without prompting.

> Do **not** click "Authenticate device" in the BMW portal itself — approve via the URL/code the server
> prints.

If the refresh token ever lapses (for example after a long period with the server off), refresh fails
and the approval prompt reappears in the logs — just approve again.

## Verifying

Once approved and connected, the app's main screen shows the car battery percentage. On the server side
the value flows as a `CarStateUpdate` WebSocket message (pushed when it changes and once on connect).

If the percentage never appears, check the logs: confirm the device approval succeeded, the MQTT
connection is up, and that the SoC descriptor you configured matches one the car actually streams.
