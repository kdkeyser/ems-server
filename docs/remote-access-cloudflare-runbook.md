# Remote Access Runbook — Cloudflare Tunnel

Exposes the EMS app's two WebSocket endpoints (`/ws`, `/status-ws`) at
`https://ec29.ems.konektis.io` over TLS, without a fixed IP or open ports. The charger
endpoint (`/ocpp/{id}`) and the local admin pages (`/ocpp-ui`, `/status`, `/users`) stay LAN-only.

New to this? Start with the [Getting Started guide](remote-access-getting-started.md).
See the design spec: `docs/superpowers/specs/2026-06-03-remote-access-cloudflare-design.md`.

## Prerequisites

- TerraMaster NAS with Docker + docker-compose on the home LAN (`192.168.129.0/24`).
- A build machine with **Podman** and this repo (image built there, shipped as a tarball).
- A Cloudflare account; `konektis.io` managed by Cloudflare DNS (step 1).

## 1. Move konektis.io to Cloudflare

In the Cloudflare dashboard, add the `konektis.io` zone and switch the registrar's nameservers to
the pair Cloudflare assigns. Verify all existing records imported (MX/email, other subdomains).
Wait until the zone status is **Active**.

## 2. Prepare the config

```bash
cp deploy/config.yaml.template deploy/config.yaml
# Edit: real device IPs + a STRONG websocket username/password (the app uses the same).
chmod 644 deploy/config.yaml   # the container's non-root user (uid 10001) reads it read-only
```

## 3. Create the Tunnel (token-managed)

Dashboard → **Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared**. Name it
`ems-nas`. Copy the **tunnel token**. On the build machine:

```bash
echo 'TUNNEL_TOKEN=<paste-tunnel-token>' > .env
```

## 4. Build, ship, run

On the build machine (in the repo):

```bash
deploy/build-podman.sh          # builds ems-server + pulls cloudflared -> ems-images.tar.gz
scp ems-images.tar.gz docker-compose.yml .env admin@<NAS>:/Volume1/docker/ems/
ssh admin@<NAS> 'mkdir -p /Volume1/docker/ems/deploy'
scp deploy/config.yaml admin@<NAS>:/Volume1/docker/ems/deploy/
```

On the NAS:

```bash
cd /Volume1/docker/ems
ss -tlnp | grep 8080        # must be EMPTY (TOS uses 8181/8443, so 8080 is normally free)
docker load -i ems-images.tar.gz
docker compose up -d
docker compose logs -f cloudflared   # expect "Registered tunnel connection"
```

The tunnel shows **Healthy** in the dashboard.

## 5. Public hostname + path scoping

In the tunnel's **Public Hostname** config, add (in order):

1. Hostname `ec29.ems.konektis.io`, Path `ws`, Service `http://ems-server:8080`
2. Hostname `ec29.ems.konektis.io`, Path `status-ws`, Service `http://ems-server:8080`
3. A final **catch-all** rule → Service **`http_status:404`**

Only `/ws` and `/status-ws` reach the server; every other path returns 404, so the admin pages and
charger endpoint are never exposed.

## 6. Access service token (edge auth)

**Zero Trust → Access → Service Auth → Create Service Token**; copy the **Client ID** and
**Client Secret** (shown once).

**Zero Trust → Access → Applications → Add an application → Self-hosted**:

- Application domain: `ec29.ems.konektis.io`, path `ws`. Add a second application for path
  `status-ws` (or one app whose path matches both).
- Policy: action **Service Auth**, include the **service token** created above.

Requests without valid `CF-Access-Client-Id`/`CF-Access-Client-Secret` headers are rejected at the
edge. Save the Client ID + Secret for the app's Settings.

## 7. Validate end-to-end

```bash
# with the service token -> expect 101 upgrade + StatusState JSON frames
websocat -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>" \
  "wss://ec29.ems.konektis.io/status-ws"

# without it -> rejected by Access, no upgrade
websocat "wss://ec29.ems.konektis.io/status-ws"

# /ws with token + correct creds: after the Authenticate frame, expect Authenticated + updates
websocat -H "CF-Access-Client-Id: <id>" -H "CF-Access-Client-Secret: <secret>" \
  "wss://ec29.ems.konektis.io/ws"
# then type: {"type":"Authenticate","username":"<cfg user>","password":"<cfg pass>"}

# not exposed
curl -i https://ec29.ems.konektis.io/ocpp-ui   # expect 404
```

**LAN unchanged:** `http://<NAS-LAN-IP>:8080/status` still serves `status.html` with no creds;
the charger still connects to `ws://<NAS-LAN-IP>:8080/ocpp/CP01`.

## 8. Configure the app

App **Settings**: server `ec29.ems.konektis.io`, **Use TLS on**, **CF Access Client ID** + **CF
Access Client Secret** from step 6, username/password = the `websocket` creds from
`deploy/config.yaml`.

## Operations

- **Rotate the service token:** create a new one in Access, update the app, delete the old token.
- **Rotate websocket creds:** edit `deploy/config.yaml`, `docker compose restart ems-server`,
  update the app.
- **Update the server:** rebuild on the build machine (`deploy/build-podman.sh`), copy the new
  `ems-images.tar.gz` to the NAS, then `docker load -i ems-images.tar.gz && docker compose up -d`.
- **Logs:** `docker compose logs -f cloudflared` / `docker compose logs -f ems-server`.
