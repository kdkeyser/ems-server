# Getting Started — Remote Access for the EMS App

This guide is the on-ramp for exposing the EMS server to the phone app over the internet,
securely, **without a fixed IP or any open ports** at home. It orients you and links to the
detailed [runbook](remote-access-netbird-runbook.md) for each step.

The server runs on a TerraMaster NAS in two containers (the EMS server + a rootless NetBird
peer). NetBird's Cloud Reverse Proxy gives you a TLS URL like `https://ec29.ems.konektis.io`
and tunnels it over WireGuard to the NAS — nothing is exposed on your router.

## How it fits together

```
  Phone app          NetBird Cloud RP            NAS (home LAN 192.168.129.0/24)
 (anywhere)  ──────▶ TLS + Bearer header ──────▶ ┌───────────── docker bridge ─────────────┐
   wss://            (Let's Encrypt)   WireGuard  │  netbird (rootless) ──▶ ems-server.lan:8080│
                                                  │                              │ (EMS server)│
                                                  └──────────────────────────────┼────────────┘
                                                                                 │  :8080 published
   LAN clients ───────────────────────────────────────────────────────────────▶ │  to the NAS
   (charger OCPP, http://NAS:8080/status, /ocpp-ui)                               │
   solar / battery / heat pump / P1 meter  ◀── Modbus/HTTP (outbound from EMS) ───┘
```

- **Remote traffic** (phone, anywhere): `wss://ec29.ems.konektis.io` → NetBird edge (checks the
  Bearer API key, terminates TLS) → WireGuard → the rootless peer → `ems-server.lan:8080`.
- **LAN traffic** (charger, admin pages): straight to the NAS's published port 8080, unchanged.
- **Devices**: the EMS server reaches them outbound from the bridge via the NAS.

## What you need

- A TerraMaster NAS with Docker + docker-compose, on the home LAN.
- A build machine with **Podman** and this repo (the NAS needs neither — it gets a tarball).
- A NetBird Cloud account (https://app.netbird.io) and DNS control for your domain.

## The path, end to end

Each step links to the full runbook. Do them in order.

| # | Step | What happens | Runbook |
|---|------|--------------|---------|
| 1 | **Build the image** | `deploy/build-podman.sh` builds `ems-server`, pulls rootless netbird, and bundles both into `ems-images.tar.gz`. | [§3](remote-access-netbird-runbook.md#3-build-the-images-and-start-the-containers) |
| 2 | **Prepare config + key** | Fill in `deploy/config.yaml` (device IPs + strong websocket creds); create a reusable NetBird setup key in a `.env`. | [§1](remote-access-netbird-runbook.md#1-prepare-the-config), [§2](remote-access-netbird-runbook.md#2-create-a-netbird-setup-key) |
| 3 | **Deploy on the NAS** | `scp` the tarball + `docker-compose.yml` + `deploy/config.yaml` + `.env`; `docker load`; `docker compose up -d`. | [§3](remote-access-netbird-runbook.md#3-build-the-images-and-start-the-containers) |
| 4 | **Join the tunnel** | The netbird container enrolls as peer `ems-nas`; confirm it's **Connected** in the dashboard. | [§3](remote-access-netbird-runbook.md#3-build-the-images-and-start-the-containers) |
| 5 | **Publish the service** | Add an RP **HTTP** service: target **Domain `ems-server.lan:8080`**, routing peer `ems-nas`, path routes **only** `/ws` + `/status-ws`. Enable **Header Authentication** (Bearer API key). | [§4](remote-access-netbird-runbook.md#4-create-the-reverse-proxy-service-http--l7), [§5](remote-access-netbird-runbook.md#5-enable-header-authentication-edge-auth) |
| 6 | **Point DNS** | Add the CNAME NetBird shows; wait for the service to go **active** (TLS issued). | [§6](remote-access-netbird-runbook.md#6-point-the-custom-domain-at-netbird) |
| 7 | **Validate** | Use `websocat` to confirm edge auth rejects/accepts and the WebSocket streams. **This is the make-or-break check** (see the risk below). | [§7](remote-access-netbird-runbook.md#7-validate-end-to-end) |
| 8 | **Configure the app** | Point the app at the public host and enter the credentials (below). | — |

## Step 8: configure the app

In the app's **Settings**:

| Field | Remote (over the internet) | Local LAN / emulator |
|-------|----------------------------|----------------------|
| Server address | `ec29.ems.konektis.io` | `<NAS-LAN-IP>:8080` (emulator: `10.0.2.2:8080`) |
| Use TLS (wss) | **On** | Off |
| API key | the Bearer key from RP **Header Authentication** (step 5) | leave blank |
| Username / Password | the `websocket` creds from `deploy/config.yaml` | same |

The API key is sent as `Authorization: Bearer …` on both WebSocket endpoints (edge auth). The
username/password are sent in the app-layer `Authenticate` frame on `/ws` only.

## Security model (why this is safe to expose)

- **No open ports / no fixed IP** — the NAS only makes outbound WireGuard connections to NetBird.
- **Two auth layers:** the NetBird edge rejects anything without the Bearer API key before it
  reaches the NAS; `/ws` additionally requires the app-layer username/password.
- **Least exposure:** only `/ws` and `/status-ws` are path-routed. The charger endpoint
  (`/ocpp/{id}`) and admin pages (`/ocpp-ui`, `/status`, `/users`) stay LAN-only.
- **`/status-ws`** is intentionally app-unauthenticated so the local `status.html` works without
  creds; remotely it's still gated by the edge API key.
- Replace the template's `CHANGE_ME_*` websocket creds before exposing anything.

## The one risk to watch

NetBird's HTTP/L7 service must forward the WebSocket `Upgrade` handshake (header auth runs on
that first HTTP request). The whoami harness only proved plain-HTTP routing and Domain-name
resolution — **not** the WS upgrade. Confirm it with the `websocat` checks in
[runbook §7](remote-access-netbird-runbook.md#7-validate-end-to-end) **before** wiring the app.
If L7 won't proxy WebSockets, the runbook documents the L4 TLS-passthrough + Caddy fallback.

## Where things live

| Path | Purpose |
|------|---------|
| `deploy/build-podman.sh` | Build both images → one `ems-images.tar.gz`. |
| `docker-compose.yml` | Runs ems-server + rootless netbird on a shared bridge. |
| `deploy/config.yaml.template` | Production config template (copy → `deploy/config.yaml`). |
| `docs/remote-access-netbird-runbook.md` | Step-by-step deployment + validation. |
| `deploy/netbird-whoami-test/` | Throwaway harness that proved the Domain-target approach. |

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `docker compose up` fails to publish 8080 | Port 8080 already in use — `ss -tlnp \| grep 8080` should be empty. |
| Server logs can't read config | `deploy/config.yaml` not world-readable — `chmod 644`. |
| RP service stuck `certificate_failed` | CNAME wrong / not propagated yet. |
| Edge returns a backend error after auth passes | Peer can't reach `ems-server.lan` — check both containers share the bridge and the alias is set. |
| `wss://` rejected with 401/403 | Missing/wrong API key (that's the edge auth working). |
| `/ws` connects then closes | Wrong app username/password. |
