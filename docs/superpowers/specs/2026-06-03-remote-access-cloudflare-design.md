# Remote Access via Cloudflare Tunnel — Design

**Status:** approved 2026-06-03. Supersedes the NetBird Reverse Proxy approach
(`2026-06-02-remote-access-netbird-design.md`), which is retained only as a historical record.

## Why this replaces NetBird

NetBird's built-in HTTP/L7 reverse proxy **cannot proxy WebSocket upgrades** — confirmed both
empirically (a handshake to `wss://…/status-ws` returned `200 OK` instead of `101 Switching
Protocols`) and by the upstream issue [netbirdio/netbird#5329](https://github.com/netbirdio/netbird/issues/5329)
(its Go reverse proxy wraps the `ResponseWriter` in access-log middleware that can't hijack the
connection for the protocol switch). The backend is healthy — a local handshake to
`http://127.0.0.1:8080/status-ws` returns `101`. NetBird's L4 proxy carries WebSockets but is
TLS-passthrough only with no path routing or header auth, so it can't terminate our TLS, limit
exposure to two paths, or do edge auth. Cloudflare Tunnel does all of that natively.

## Goal

The phone app reaches the EMS server from anywhere at `https://ec29.ems.konektis.io` (`wss://`),
TLS on all public traffic, **no fixed IP and no open ports** at home. Charger (`/ocpp/{id}`) and
local admin pages (`/ocpp-ui`, `/status`, `/users`) stay LAN-only.

## Architecture

```
Phone app ──wss:// + CF-Access-Client-Id/Secret──▶ Cloudflare edge (TLS termination,
          ──Cloudflare Tunnel (outbound only)──▶    Access service-token check)
                                              ──▶ cloudflared (NAS, docker bridge)
                                              ──▶ ems-server:8080
LAN clients ──ws/http──▶ NAS:8080  (charger OCPP, /status, /ocpp-ui)  [unchanged; bypasses Cloudflare]
EMS server  ──Modbus/HTTP outbound──▶ solar / battery / heat pump / P1 (192.168.129.x)
```

- `cloudflared` makes only outbound connections to the Cloudflare edge — no inbound ports.
- Cloudflare terminates TLS at the edge (auto cert for the hostname, since the zone is on
  Cloudflare) and proxies the WebSocket upgrade end to end.
- `cloudflared` reaches the server over the shared Compose bridge as `http://ems-server:8080`.

## Decisions

- **DNS:** move the `konektis.io` zone to Cloudflare (free plan). Verify MX/email and existing
  subdomains import correctly before cutover.
- **Tunnel config:** token-managed (Cloudflare's standard). `cloudflared` runs with a
  `TUNNEL_TOKEN`; public hostnames, path scoping, the 404 catch-all, and the Access policy live in
  the Cloudflare dashboard.
- **Edge auth:** Cloudflare **Access** self-hosted application on the exposed paths, with a
  **Service Auth** policy bound to a **service token** (free tier; non-interactive). The app sends
  `CF-Access-Client-Id` + `CF-Access-Client-Secret`.
- **App-layer auth:** unchanged — `/ws` still requires the `websocket` username/password via the
  `Authenticate` frame. `/status-ws` stays app-unauthenticated (edge-protected remotely; open on
  the LAN so local `status.html` works without creds).

## Components and changes

### Server — no changes
Cloudflare handles the edge. The server keeps app-layer auth on `/ws`, leaves `/status-ws`
app-unauthenticated, and needs no awareness of Cloudflare. (`cloudflared`→origin is plain HTTP over
the tunnel; nothing reads client IP, so no trusted-proxy/forwarded-header handling is required.)

### Deployment — `docker-compose.yml`
- **Remove** the `netbird` service, its volume, and the `ems-server.lan` network alias (all
  NetBird-specific).
- **Add** `cloudflared`:
  - image `cloudflare/cloudflared:latest`
  - `command: tunnel --no-autoupdate run`
  - `environment: TUNNEL_TOKEN: ${TUNNEL_TOKEN:?…}` (from `.env`)
  - on the default Compose bridge (reaches `ems-server:8080` by name); no published ports, no
    capabilities, `restart: unless-stopped`.
- `ems-server` unchanged: stays on the bridge, keeps `ports: ["8080:8080"]` for LAN admin pages and
  the charger's inbound OCPP.

### Build — `deploy/build-podman.sh`
Bundle `localhost/ems-server:latest` + `docker.io/cloudflare/cloudflared:latest` into the single
`ems-images.tar.gz` (replace the netbird pull with cloudflared).

### App — `android/`
- `Settings` (and DataStore keys/defaults/flow/save): drop `apiKey`; add
  `cfAccessClientId: String = ""` and `cfAccessClientSecret: String = ""`.
- `ControlWsClient` and `StatusWsClient`: when both values are set, send headers
  `CF-Access-Client-Id` and `CF-Access-Client-Secret` (replacing the previous
  `Authorization: Bearer` header). `wsUrl` helper unchanged.
- `SettingsScreen`: replace the single API-key field with two fields — Client ID and Client Secret
  (secret masked with `PasswordVisualTransformation`).
- Tests: update `SettingsRepositoryTest` (defaults + round-trip with the two new fields); add/adjust
  a WS-client header test asserting both headers are sent when set and omitted when blank.

### Cloudflare dashboard (documented in the new runbook)
1. Add `konektis.io` to Cloudflare; switch nameservers; verify record import.
2. **Networks → Tunnels → Create tunnel** (cloudflared, token-managed). Put `TUNNEL_TOKEN` in the
   NAS `.env`.
3. Public hostname `ec29.ems.konektis.io`: route **only** `/ws` and `/status-ws` →
   `http://ems-server:8080`; add a **catch-all → HTTP 404** for all other paths.
4. **Access → Applications → self-hosted**, scoped to the exposed paths; add a **Service Auth**
   policy that includes a **service token**. Save the token's Client ID + Secret for the app.
5. Confirm **Network → WebSockets** is enabled on the zone (default on).

## Security model

| Layer | Control |
|-------|---------|
| Edge auth | Cloudflare Access service token required on `/ws` + `/status-ws` |
| Exposure | Only `/ws` + `/status-ws` routed; every other path → 404. `/ocpp/{id}`, `/ocpp-ui`, `/status`, `/users` are never routed (LAN-only) |
| App auth | `/ws` additionally requires the `websocket` username/password |
| Transport | TLS terminated at the Cloudflare edge; no open ports at home |

## Docs & cleanup

- **New:** `docs/remote-access-cloudflare-runbook.md` (DNS move → tunnel → public hostnames + path
  scoping → Access service token → app config → `websocat` validation).
- **Rewrite:** `docs/remote-access-getting-started.md` for the Cloudflare flow.
- **Remove (dead):** `docs/remote-access-netbird-runbook.md`, `deploy/netbird-whoami-test/`.
- **Keep (historical):** the dated NetBird design spec in `docs/superpowers/specs/`.
- **Memory:** update the remote-access note with the NetBird→Cloudflare pivot and the WS-over-L7
  rationale.

## Testing & validation

- **App:** unit tests green locally (`:app:testDebugUnitTest`) — settings round-trip and WS-client
  header injection.
- **Server:** existing tests unaffected (no server change).
- **End-to-end (on the NAS / from a client):**
  - `websocat -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>" "wss://ec29.ems.konektis.io/status-ws"`
    → expect `101` + StatusState frames.
  - Same without the headers → rejected by Access (no upgrade).
  - `https://ec29.ems.konektis.io/ocpp-ui` → 404 (not routed).
  - LAN: `http://<NAS>:8080/status` still serves `status.html`; charger still connects on
    `ws://<NAS>:8080/ocpp/CP01`.

## Out of scope

Remote `/ocpp-ui`/charger access, OIDC/multi-user, HA. Time-series history remains separate.
Release-signing for the Android app is tracked separately and still pending.
