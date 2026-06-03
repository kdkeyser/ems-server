# Remote Access Runbook — NetBird Reverse Proxy

Exposes the EMS app's two WebSocket endpoints (`/ws`, `/status-ws`) at
`https://ec29.ems.konektis.io` over TLS, without a fixed IP or open ports. The charger
endpoint (`/ocpp/{id}`) and the local admin pages (`/ocpp-ui`, `/status`) stay LAN-only.

New to this? Start with the [Getting Started guide](remote-access-getting-started.md) for the
big picture, then come back here for the detailed steps.

See the design spec: `docs/superpowers/specs/2026-06-02-remote-access-netbird-design.md`.

## Prerequisites

- TerraMaster F4-423 NAS on the home LAN (`192.168.129.0/24`) with Docker + docker-compose.
- A build machine with **Podman** and this repo checked out. The image is built there and
  shipped to the NAS as a tarball — the NAS needs no repo or build toolchain.
- A NetBird Cloud account (dashboard at https://app.netbird.io).
- DNS control for `konektis.io`.

## 1. Prepare the config

```bash
cp deploy/config.yaml.template deploy/config.yaml
# Edit deploy/config.yaml: set real device IPs and a STRONG websocket username/password.
# Record those credentials — the app's Settings must use the same username/password.
chmod 644 deploy/config.yaml   # the container's non-root user (uid 10001) reads it read-only
```

## 2. Create a NetBird setup key

In the NetBird dashboard → **Setup Keys** → create a **reusable** key. Copy it.

```bash
echo "NB_SETUP_KEY=<paste-setup-key>" > .env   # docker-compose reads this
```

## 3. Build the images and start the containers

Build both images into one tarball on the build machine (with Podman), copy it to the NAS
along with the compose file and config, then load and run.

On the build machine (in the repo):

```bash
deploy/build-podman.sh          # builds ems-server + pulls rootless netbird -> ems-images.tar.gz
scp ems-images.tar.gz docker-compose.yml .env admin@<NAS>:/Volume1/docker/ems/
ssh admin@<NAS> 'mkdir -p /Volume1/docker/ems/deploy'
scp deploy/config.yaml admin@<NAS>:/Volume1/docker/ems/deploy/   # compose mounts ./deploy/config.yaml
```

On the NAS:

```bash
cd /Volume1/docker/ems
ss -tlnp | grep 8080        # must be EMPTY — compose fails to publish 8080 if it's taken
docker load -i ems-images.tar.gz
docker compose up -d
docker compose ps                    # ems-server + netbird both "running"
docker compose logs -f ems-server    # confirm startup on :8080 and device polling
```

(The TOS web UI uses 8181/8443, so 8080 is normally free.)

The rootless `netbird` container enrolls the NAS as a peer — WireGuard runs in userspace, so
it needs no `NET_ADMIN` or `/dev/net/tun`. Confirm it appears under **Peers** in the dashboard
(hostname `ems-nas`) with status connected.

## 4. Create the Reverse Proxy service (HTTP / L7)

In the dashboard → **Network / Reverse Proxy** (beta) → **Add service**:

- **Protocol:** HTTP (L7).
- **Custom domain:** `ec29.ems.konektis.io`.
- **Target:** Type **Domain**, host `ems-server.lan`, port `8080`, protocol HTTP,
  routing peer `ems-nas`. `ems-server.lan` is a Docker network alias on the ems-server
  container; the rootless peer resolves it via Docker's embedded DNS (validated with the
  `deploy/netbird-whoami-test/` harness). The name must be dotted — a bare `ems-server`
  fails NetBird's domain validation. (Under host networking this was instead peer
  `ems-nas`:8080; on the bridge the peer and server are separate containers, so we target
  the server's alias.)
- **Path routes:** add **only** `/ws` and `/status-ws`. Do NOT expose `/`, `/ocpp`,
  `/ocpp-ui`, `/status`, or `/users` — they must stay LAN-only.

> This is the same Domain-target + routing-peer setup you proved with the whoami harness. If
> NetBird asks you to define the resource first, create `ems-server.lan` as a **Domain** resource
> in a Network with `ems-nas` as its routing peer, then select it as the service target.

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
- **Update the server:** rebuild on the build machine (`deploy/build-podman.sh`), copy the new
  `ems-images.tar.gz` to the NAS, then `docker load -i ems-images.tar.gz && docker compose up -d`.
