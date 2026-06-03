# Getting Started — Remote Access for the EMS App

This guide is the on-ramp for exposing the EMS server to the phone app over the internet,
securely, **without a fixed IP or any open ports** at home. It orients you and links to the
detailed [runbook](remote-access-cloudflare-runbook.md) for each step.

The server runs on a TerraMaster NAS in two containers (the EMS server + a Cloudflare Tunnel
agent, `cloudflared`). Cloudflare gives you a TLS URL `https://ec29.ems.konektis.io` and tunnels
it over an outbound-only connection to the NAS — nothing is exposed on your router.

> **Why Cloudflare and not NetBird?** NetBird's L7 reverse proxy cannot proxy WebSocket upgrades
> ([#5329](https://github.com/netbirdio/netbird/issues/5329)); a handshake returned `200` instead
> of `101`. Cloudflare Tunnel proxies WebSockets natively. See the design spec.

## How it fits together

```
  Phone app            Cloudflare edge                NAS (home LAN 192.168.129.0/24)
 (anywhere)  ──wss──▶  TLS + Access service token ──▶ ┌──────── docker bridge ─────────┐
  + CF-Access-*        (proxies WebSockets)  tunnel   │ cloudflared ──▶ ems-server:8080 │
                                             (outbound)│                  │ (EMS server) │
                                                       └──────────────────┼─────────────┘
   LAN clients ──────────────────────────────────────────────────────────┤ :8080 published
   (charger OCPP, http://NAS:8080/status, /ocpp-ui)                       │ to the NAS
   solar / battery / heat pump / P1 meter  ◀── Modbus/HTTP (outbound) ─────┘
```

- **Remote traffic:** `wss://ec29.ems.konektis.io` → Cloudflare edge (terminates TLS, checks the
  Access service token) → tunnel → `cloudflared` → `ems-server:8080`.
- **LAN traffic** (charger, admin pages): straight to the NAS's published port 8080, unchanged.
- **Devices:** the EMS server reaches them outbound from the bridge via the NAS.

## What you need

- A TerraMaster NAS with Docker + docker-compose, on the home LAN.
- A build machine with **Podman** and this repo (the NAS gets a tarball).
- A Cloudflare account, with `konektis.io` moved to Cloudflare DNS.

## The path, end to end

Each step links to the full runbook. Do them in order.

| # | Step | Runbook |
|---|------|---------|
| 1 | **Move DNS** — add `konektis.io` to Cloudflare; switch nameservers. | [§1](remote-access-cloudflare-runbook.md#1-move-konektisio-to-cloudflare) |
| 2 | **Config + tunnel** — fill `deploy/config.yaml`; create a token-managed tunnel; `TUNNEL_TOKEN` → `.env`. | [§2](remote-access-cloudflare-runbook.md#2-prepare-the-config), [§3](remote-access-cloudflare-runbook.md#3-create-the-tunnel-token-managed) |
| 3 | **Build + deploy** — `deploy/build-podman.sh`, `scp`, `docker load`, `docker compose up -d`. | [§4](remote-access-cloudflare-runbook.md#4-build-ship-run) |
| 4 | **Publish + scope** — public hostname routes only `/ws` + `/status-ws`; catch-all 404. | [§5](remote-access-cloudflare-runbook.md#5-public-hostname--path-scoping) |
| 5 | **Edge auth** — Access self-hosted app + Service Auth policy + service token. | [§6](remote-access-cloudflare-runbook.md#6-access-service-token-edge-auth) |
| 6 | **Validate** — `websocat` with the CF headers → expect `101` + frames. | [§7](remote-access-cloudflare-runbook.md#7-validate-end-to-end) |
| 7 | **Configure the app** — server host, TLS on, CF Client ID/Secret, creds. | [§8](remote-access-cloudflare-runbook.md#8-configure-the-app) |

## App configuration

In the app's **Settings**:

| Field | Remote (over the internet) | Local LAN / emulator |
|-------|----------------------------|----------------------|
| Server address | `ec29.ems.konektis.io` | `<NAS-LAN-IP>:8080` (emulator: `10.0.2.2:8080`) |
| Use TLS (wss) | **On** | Off |
| CF Access Client ID | from the Access service token | leave blank |
| CF Access Client Secret | from the Access service token | leave blank |
| Username / Password | the `websocket` creds from `deploy/config.yaml` | same |

The CF values are sent as `CF-Access-Client-Id` / `CF-Access-Client-Secret` on both WebSocket
endpoints (edge auth). The username/password go in the app-layer `Authenticate` frame on `/ws` only.

## Security model

- **No open ports / no fixed IP** — `cloudflared` only makes outbound connections.
- **Two auth layers:** Cloudflare Access rejects anything without a valid service token before it
  reaches the NAS; `/ws` additionally requires the app-layer username/password.
- **Least exposure:** only `/ws` + `/status-ws` are routed; every other path returns 404.
  `/ocpp/{id}`, `/ocpp-ui`, `/status`, `/users` stay LAN-only.
- **`/status-ws`** is app-unauthenticated so the local `status.html` works without creds; remotely
  it's gated by the Access service token.
- Replace the template's `CHANGE_ME_*` websocket creds before exposing anything.

## Where things live

| Path | Purpose |
|------|---------|
| `deploy/build-podman.sh` | Build both images → one `ems-images.tar.gz`. |
| `docker-compose.yml` | Runs ems-server + cloudflared on a shared bridge. |
| `deploy/config.yaml.template` | Production config template (copy → `deploy/config.yaml`). |
| `docs/remote-access-cloudflare-runbook.md` | Step-by-step deployment + validation. |

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| `docker compose up` fails to publish 8080 | Port 8080 already in use — `ss -tlnp \| grep 8080` should be empty. |
| Server logs can't read config | `deploy/config.yaml` not world-readable — `chmod 644`. |
| Tunnel not **Healthy** | Bad `TUNNEL_TOKEN`, or `cloudflared` can't reach the edge — `docker compose logs cloudflared`. |
| `wss://` returns 403 / login redirect | Missing/invalid CF Access service token headers. |
| `/ws` connects then closes | Wrong app username/password. |
| Remote path returns 404 | Public-hostname path scoping doesn't include the path, or hits the catch-all. |
