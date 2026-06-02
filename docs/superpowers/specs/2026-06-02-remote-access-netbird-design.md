# Remote Access via NetBird Reverse Proxy — Design

**Date:** 2026-06-02
**Status:** Approved (design); pending implementation plan

## Goal

Let the phone app reach the EMS server from anywhere over the public internet at
`https://ec29.ems.konektis.io` (WebSocket `wss://`), with TLS on all public traffic, **without a
fixed IP and without opening any ports** at home. The car charger and the local admin/status pages
remain reachable only on the home LAN. Deployment target is a TerraMaster F4-423 NAS running Docker.

## Decisions (from brainstorming)

- **Transport/ingress:** NetBird **Cloud** Reverse Proxy (managed), **HTTP (L7)** service. NetBird
  terminates TLS at its proxy cluster (automatic Let's Encrypt) and tunnels traffic over WireGuard
  to the NAS peer. No port-forwarding, no dynamic DNS, works behind CGNAT.
- **Custom domain:** `ec29.ems.konektis.io`, via a CNAME to the NetBird proxy cluster (NetBird
  custom-domain flow). Auto-TLS.
- **Exposure scope:** only the app's two WebSocket endpoints — `/ws` and `/status-ws` — are
  path-routed through the proxy. `/ocpp/{id}` (charger), `/ocpp-ui`, `/status`, `/users` are **not**
  publicly routed; they stay LAN-only.
- **Authentication:**
  1. **Edge — NetBird Header Authentication (both endpoints):** the RP service requires a static
     API-key/Bearer header on `/ws` and `/status-ws`. Requests without it are rejected at the
     NetBird proxy cluster and **never reach the NAS**. This is what protects the otherwise-open
     `/status-ws` on the public path.
  2. **Application — `Authenticate(username,password)` on `/ws` only:** `/ws` keeps its existing
     app-layer auth. **`/status-ws` is left unauthenticated at the app layer** so the local
     `status.html` page keeps working on the LAN without credentials; its remote exposure is covered
     by the edge header. No server auth change and no `status.html` change.
- **Deployment:** the server is containerized; the NAS runs two containers (`ems-server` + the
  `netbird` agent). Config and the SQLite DB live on NAS volumes; `config.yaml` is loaded from a
  mounted file so device IPs/credentials can change without rebuilding the image.
- **App changes (separate repo, `android/`):** dial `wss://` and send the API-key header on both
  WebSocket handshakes. `/ws` still sends its `Authenticate` frame as today; `/status-ws` needs no
  app-layer auth. The API key is stored in the app's Settings (rotatable without a rebuild).
- **Plan split:** two independent implementation plans — one for the **server + deployment** (incl.
  the NetBird/DNS runbook), one for the **app**.

## Background — current state

- Single Ktor server on port **8080**. Endpoints: `/ws` (app control + power, authenticated via
  `WebSocketConfig` username/password), `/status-ws` (device-health stream, **unauthenticated**),
  `/status` (serves `status.html`), `/ocpp/{chargePointId}` (charger), `/ocpp-ui` (charger admin,
  unauthenticated), `/users` (basic auth demo), `/`.
- Config is loaded with Hoplite `addResourceSource("/config.yaml")` — a **classpath resource baked
  into the build**. Device IPs/credentials live there. There is no Dockerfile yet.
- The NAS must sit on the home LAN (`192.168.129.0/24`) to reach inverters/battery/charger/heatpump
  over Modbus/HTTP.
- The app (`android/`) hardcodes the `ws://` scheme in both clients
  (`ControlWsClient` → `ws://{serverUrl}/ws`, `StatusWsClient` → `ws://{serverUrl}/status-ws`).
  `serverUrl` is a configurable `host:port` "Server address" setting; username/password are stored
  alongside. `ControlWsClient` sends `Authenticate` on connect; `StatusWsClient` does not.

## Architecture

```
 Phone (anywhere)
   │  wss://ec29.ems.konektis.io/ws  +  /status-ws        (TLS)
   │  header: Authorization: Bearer <api-key>             (edge auth)
   ▼
 NetBird Cloud proxy cluster
   ├─ validates the API-key header  → drops unauthenticated requests here
   ├─ terminates TLS (auto Let's Encrypt)
   ▼  WireGuard tunnel (no open ports at home)
 TerraMaster F4-423 NAS  (NetBird peer, home LAN 192.168.129.0/24, Docker)
   ├─ netbird agent  (host network, NET_ADMIN + /dev/net/tun)
   └─ ems-server     (:8080 plain HTTP)
        ├─ /ws, /status-ws   ← via RP; each requires Authenticate(user,pass)
        ├─ /ocpp/{id}        ← charger connects here (LAN only)
        ├─ /ocpp-ui, /status ← LAN only
        └─ Modbus/HTTP ──────→ inverters, battery, charger, heatpump (LAN)
```

Request paths:
- **App (remote):** `wss://ec29.ems.konektis.io/{ws,status-ws}` + API-key header → NetBird proxy
  (header check + TLS terminate) → WireGuard → NAS `:8080` (plain `ws`) → app-layer `Authenticate`.
- **Charger (local):** `ws://<NAS-LAN-IP>:8080/ocpp/CP01` — unchanged, never leaves the LAN.
- **Devices:** `ems-server` → Modbus/HTTP to `192.168.129.x` — unchanged.

The NAS `:8080` listener is reachable on the LAN (charger + local admin) but is **not** port-forwarded
to the internet. The only public route is the NetBird RP, which path-routes just `/ws` + `/status-ws`.

## Components & changes

### A. Server (`ems-server` repo)

1. **`Dockerfile`** — multi-stage: stage 1 builds the fat jar (`./gradlew shadowJar`); stage 2 is a
   slim JRE running the jar. Exposes 8080. Reads config from a mounted file (see #3).
2. **`docker-compose.yml`** — two services:
   - `ems-server`: builds/runs the image; volumes mount `config.yaml` (read-only) and a data volume
     for `ems.db`; on the host network (or a bridge that can reach the LAN devices and be reached by
     the netbird agent at `:8080`).
   - `netbird`: the NetBird agent; host network; `cap_add: NET_ADMIN`; `/dev/net/tun`; setup-key/login
     via env; persistent volume for its state. Makes the NAS a peer.
3. **Externalize config loading** — change `loadConfig` to read a **file path** first
   (`EMS_CONFIG` env, default `/config/config.yaml`) via Hoplite `addFileSource`, falling back to the
   classpath resource when the file is absent (so existing tests/local runs keep working). This lets
   the NAS edit device IPs/credentials/DB path without rebuilding.
4. **Credentials** — the deployed `config.yaml` must set a strong `websocket.username`/`password`
   (the `user`/`password` defaults must not ship to an exposed instance). Documented in the runbook;
   not a code change.

   *(No `/status-ws` auth change and no `status.html` change — `/status-ws` stays open at the app
   layer so the local status page works without credentials; its remote path is gated by the edge
   header.)*

### B. App (`android` repo)

7. **`wss://` support** — replace the hardcoded `ws://` in `ControlWsClient` and `StatusWsClient`
   with a TLS-aware scheme. Add a "Use TLS" boolean to `Settings` (default on for remote); build
   `wss://{serverUrl}/…` when enabled, else `ws://`.
8. **API-key header** — add `apiKey` to `Settings`; send it as `Authorization: Bearer <apiKey>` (or
   a configurable header name) on both `client.webSocket(...)` handshakes via the request builder.
9. **Settings UI** — add fields for the API key and the TLS toggle; allow `serverUrl` to be a bare
   host (e.g. `ec29.ems.konektis.io`, no port) for the remote case.

   *(`StatusWsClient` does NOT authenticate — `/status-ws` has no app-layer auth. It only needs the
   `wss://` scheme + the API-key header. `ControlWsClient` keeps sending its `Authenticate` frame.)*

### C. NetBird / DNS runbook (manual, documented in `docs/`)

11. Enroll the NAS as a NetBird peer (setup key fed to the `netbird` container).
12. Create an RP **HTTP** service: custom domain `ec29.ems.konektis.io`; target = NAS peer, port
    `8080`, protocol HTTP; path routes limited to `/ws` and `/status-ws`.
13. Enable **Header Authentication** on the service with a static API key (the same value stored in
    the app). Optionally add a country/IP access restriction as extra defense.
14. Add the `ec29` CNAME at the `konektis.io` DNS host pointing to the NetBird proxy cluster; wait
    for the service status to reach `active` (TLS issued).

## Data flow (auth, end to end)

1. App opens `wss://ec29.ems.konektis.io/ws` with header `Authorization: Bearer <api-key>`.
2. NetBird proxy validates the header. **Missing/wrong → rejected at the edge; no NAS traffic.**
3. Valid → TLS terminated at the edge; request tunneled over WireGuard to NAS `:8080` as plain `ws`.
4. `ems-server` receives the WS. For `/ws`, the app sends `Authenticate(username,password)`; the
   server validates and streams `PowerUsageUpdate`/`ModeUpdate`. For `/status-ws`, the server streams
   `StatusState` immediately (no app-layer auth — already gated by the edge header).
5. Wrong `/ws` app-layer credentials → server closes the socket (unchanged behavior).

## Error handling

- **Edge auth fail:** NetBird returns an HTTP error before the upgrade; the app surfaces a
  connect/auth failure and backs off (existing reconnect loop).
- **App-layer auth fail (`/ws`):** server closes with a policy reason; the app shows
  `Unauthenticated`/`Disconnected` (existing handling).
- **NetBird tunnel/peer down:** RP service shows `tunnel_not_created`; the app sees connect failures
  and retries with backoff.
- **NAS offline / config error:** container restart policy `unless-stopped`; a bad mounted
  `config.yaml` fails fast at startup (logged), same as today.
- **TLS/cert issuance fail:** RP status `certificate_failed`; resolved at the NetBird/DNS layer
  (CNAME correct, domain resolves), not in app/server code.

## Risks to validate during implementation

- **WebSocket over NetBird RP HTTP/L7 (primary risk):** confirm the proxy passes the WS `Upgrade`
  handshake (header auth happens on that initial HTTP request, so it must survive the upgrade).
  **Fallback if L7 doesn't proxy WS:** use an L4 **TLS-passthrough** RP service and terminate TLS on
  the NAS with a small reverse proxy (Caddy) holding a DNS-01 Let's Encrypt cert. This loses edge
  header auth, so in that path `/status-ws` would need app-layer auth added on the server (and a
  matching `status.html`/`StatusWsClient` change) — i.e. it reverts to the earlier server-auth
  approach. HTTP/L7 is strongly preferred; validate it first with a generic `wss` client
  (`websocat`).
- **NetBird agent on TerraMaster:** verify the container gets `/dev/net/tun` + `NET_ADMIN` and that
  the host-network setup lets the RP reach `:8080`.
- **Container → LAN devices:** verify the `ems-server` container reaches `192.168.129.x` over
  Modbus/HTTP (host networking or an appropriate bridge).
- **Config-from-file change:** ensure Hoplite file loading + resource fallback behaves; covered by a test.

## Testing

- **Server unit test:** `loadConfig` reads from a file path when present and falls back to the
  classpath resource when absent (`testApplication`/Hoplite). `/status-ws` and `status.html` are
  unchanged, so existing status tests must still pass.
- **Build smoke:** `docker build` succeeds; container starts, loads the mounted config, and serves
  `/ws` + `/status-ws` locally.
- **Manual / E2E:**
  - `websocat` against `wss://ec29.ems.konektis.io/ws` **without** the header → rejected at edge;
    **with** the header but wrong `/ws` app creds → server closes; with both correct → authenticated.
    `/status-ws` with the header → streams; without the header → rejected at edge.
  - Local LAN: `http://<NAS-LAN-IP>:8080/status` (`status.html`) still works **without** credentials.
  - Phone on mobile data (off-LAN): both app screens (power/control + status) work via `wss://`;
    TLS cert valid; the charger remains controllable on the LAN; `/ocpp-ui` is **not** reachable via
    the public hostname.

## Implementation plans (two, split by repo)

One design, two independent plans:

1. **Server + deployment plan (`ems-server`):** externalize config loading (file + resource
   fallback) with a test, Dockerfile, `docker-compose.yml` (ems-server + netbird agent), and the
   **NetBird/DNS runbook** (peer enrollment, RP HTTP service with header auth + path routes,
   custom domain + CNAME, validation). No `/status-ws`/`status.html` changes.
2. **App plan (`android`):** `wss://` TLS toggle, API-key header on both WebSocket handshakes, and
   the Settings UI for the API key / TLS / bare-host server address. `StatusWsClient` gains no auth.

## Out of scope

- Exposing `/ocpp-ui` or the charger endpoint remotely (LAN/mesh only).
- Multi-user accounts / OIDC SSO (single shared credential + API key).
- High availability / failover of the NAS or multiple NetBird proxy instances.
- OCPP/ClickHouse work (tracked separately).
