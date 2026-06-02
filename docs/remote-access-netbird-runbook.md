# Remote Access Runbook — NetBird Reverse Proxy

Exposes the EMS app's two WebSocket endpoints (`/ws`, `/status-ws`) at
`https://ec29.ems.konektis.io` over TLS, without a fixed IP or open ports. The charger
endpoint (`/ocpp/{id}`) and the local admin pages (`/ocpp-ui`, `/status`) stay LAN-only.

See the design spec: `docs/superpowers/specs/2026-06-02-remote-access-netbird-design.md`.

## Prerequisites

- TerraMaster F4-423 NAS on the home LAN (`192.168.129.0/24`) with Docker + docker-compose.
- A NetBird Cloud account (dashboard at https://app.netbird.io).
- DNS control for `konektis.io`.
- This repo checked out on the NAS.

## 1. Prepare the config

```bash
cp deploy/config.yaml.template deploy/config.yaml
# Edit deploy/config.yaml: set real device IPs and a STRONG websocket username/password.
# Record those credentials — the app's Settings must use the same username/password.
```

## 2. Create a NetBird setup key

In the NetBird dashboard → **Setup Keys** → create a **reusable** key. Copy it.

```bash
echo "NB_SETUP_KEY=<paste-setup-key>" > .env   # docker-compose reads this
```

## 3. Start the containers

> **TerraMaster NAS (no Compose):** TOS does not support `docker compose`. Use
> [`remote-access-terramaster-containers.md`](remote-access-terramaster-containers.md) instead of
> this step — it sets up `ems-server` and `netbird` as individual `docker run` containers with the
> same network/volume/capability settings — then return to step 4 below.

```bash
docker compose up -d --build
docker compose ps           # ems-server + netbird both "running"
docker compose logs -f ems-server   # confirm "Responding at ... 8080" and device polling
```

The `netbird` container enrolls the NAS as a peer. Confirm it appears under
**Peers** in the dashboard (hostname `ems-nas`) with status connected.

## 4. Create the Reverse Proxy service (HTTP / L7)

In the dashboard → **Network / Reverse Proxy** (beta) → **Add service**:

- **Protocol:** HTTP (L7).
- **Custom domain:** `ec29.ems.konektis.io`.
- **Target:** peer `ems-nas`, port `8080`, protocol HTTP.
- **Path routes:** add **only** `/ws` and `/status-ws`. Do NOT expose `/`, `/ocpp`,
  `/ocpp-ui`, `/status`, or `/users` — they must stay LAN-only.

## 5. Enable Header Authentication (edge auth)

On the service, enable **Header Authentication**:

- Header: `Authorization`, scheme **Bearer**, with a long random API key.
- Save the key — it goes into the app's Settings ("API key").

Requests without this header are rejected at the NetBird proxy cluster and never reach
the NAS. (Optionally also add a country/IP allow-list for defense in depth.)

## 6. Point the custom domain at NetBird

NetBird shows a CNAME target for the custom domain. At the `konektis.io` DNS host, add:

```
ec29.ems   CNAME   <netbird-proxy-cluster-hostname>
```

Wait until the RP service status reaches **active** (TLS issued via Let's Encrypt).
If status is `certificate_failed`, recheck the CNAME and that `ec29.ems.konektis.io`
resolves to the NetBird target.

## 7. Validate end-to-end

Install `websocat` (a generic WebSocket client) to test independently of the app.

**Edge auth — must be rejected without the header:**
```bash
websocat "wss://ec29.ems.konektis.io/ws"
# expect an HTTP error (401/403) from the NetBird edge, no upgrade
```

**`/status-ws` with the header — must stream StatusState (no app-layer auth):**
```bash
websocat -H "Authorization: Bearer <api-key>" "wss://ec29.ems.konektis.io/status-ws"
# expect JSON StatusState frames
```

**`/ws` with header but wrong app creds — server closes after Authenticate:**
```bash
websocat -H "Authorization: Bearer <api-key>" "wss://ec29.ems.konektis.io/ws"
# then type: {"type":"Authenticate","username":"wrong","password":"wrong"}
# expect an Unauthorized message / socket close
```

**`/ws` with header + correct creds — authenticates and streams updates:**
```bash
websocat -H "Authorization: Bearer <api-key>" "wss://ec29.ems.konektis.io/ws"
# type: {"type":"Authenticate","username":"<cfg user>","password":"<cfg pass>"}
# expect Authenticated + PowerUsageUpdate/ModeUpdate frames
```

**LAN unchanged:** `http://<NAS-LAN-IP>:8080/status` still serves `status.html` with no
credentials; the charger still connects to `ws://<NAS-LAN-IP>:8080/ocpp/CP01`.

**Not exposed:** `https://ec29.ems.konektis.io/ocpp-ui` must NOT load (no path route).

## Risk: WebSocket over the L7 HTTP service

The primary risk is whether the RP HTTP/L7 service forwards the WebSocket `Upgrade`
handshake (header auth runs on that first HTTP request, so the upgrade must survive it).
Validate with the `websocat` checks above **before** wiring the app.

**Fallback if L7 does not proxy WebSockets:** switch the RP service to **L4 TLS
passthrough** and terminate TLS on the NAS with a small reverse proxy (Caddy) holding a
DNS-01 Let's Encrypt cert for `ec29.ems.konektis.io`. That path loses edge header auth,
so `/status-ws` would then need app-layer auth added on the server (and matching
`status.html` / app changes). Prefer L7; only fall back if validation fails.

## Operations

- **Rotate the API key:** update it on the RP service and in the app's Settings.
- **Rotate websocket creds:** edit `deploy/config.yaml`, `docker compose restart ems-server`,
  update the app's username/password.
- **Logs:** `docker compose logs -f ems-server` / `docker compose logs -f netbird`.
- **Update the server:** `git pull && docker compose up -d --build`.
