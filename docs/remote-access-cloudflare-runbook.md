# Remote Access Runbook — Cloudflare Tunnel

Exposes the EMS app's two WebSocket endpoints (`/ws`, `/status-ws`) at
`https://ems.kenas.be` over TLS, without a fixed IP or open ports. The charger
endpoint (`/ocpp/{id}`) and the local admin pages (`/ocpp-ui`, `/status`, `/users`) stay LAN-only.

New to this? Start with the [Getting Started guide](remote-access-getting-started.md).
See the design spec: `docs/superpowers/specs/2026-06-03-remote-access-cloudflare-design.md`.

## Prerequisites

- TerraMaster NAS with Docker + docker-compose on the home LAN (`192.168.129.0/24`).
- A build machine with **Podman** and this repo (image built there, shipped as a tarball).
- A Cloudflare account; `kenas.be` managed by Cloudflare DNS (step 1).

## 1. Confirm kenas.be is on Cloudflare

`kenas.be` is already on Cloudflare, so there is nothing to migrate — just confirm the zone status
is **Active** in the dashboard. You do **not** need to create a DNS record for `ems.kenas.be`
by hand: when you add the tunnel's public hostname (step 5), Cloudflare creates the proxied
CNAME for `ems.kenas.be` automatically.

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

1. Hostname `ems.kenas.be`, Path `ws`, Service `http://ems-server:8080`
2. Hostname `ems.kenas.be`, Path `status-ws`, Service `http://ems-server:8080`
3. A final **catch-all** rule → Service **`http_status:404`**

Only `/ws` and `/status-ws` reach the server; every other path returns 404, so the admin pages and
charger endpoint are never exposed.

## 6. Edge auth — WAF custom rule (shared-secret header)

`/status-ws` is app-unauthenticated (the local `status.html` needs no creds), so we gate the public
hostname at the edge with a shared secret in a header. Cloudflare **Access** would also work but its
dashboard now requires a payment method on file; the Free-plan **WAF custom rules** (5 included) do
the same job — a secret over TLS — without one.

Generate a strong secret:

```bash
openssl rand -hex 32
```

**Security → Security rules → Custom rules** (older layouts: **Security → WAF → Custom rules**) →
**Create rule**. Use the expression editor (header names must be lowercase):

```
(http.host eq "ems.kenas.be" and not any(http.request.headers["x-ems-edge-key"][*] eq "<SECRET>"))
```

Action: **Block**. The `not any(...)` form also blocks when the header is absent. This applies to
both `/ws` and `/status-ws`, adding defense-in-depth to `/ws` on top of its app-layer auth. Save the
secret for the app's Settings.

> A neutral header (`x-ems-edge-key`) is used on purpose, rather than the `CF-Access-*` names, to
> avoid any collision with Cloudflare's reserved `CF-*` namespace and any future header handling on
> their end.

## 7. Validate end-to-end

```bash
# with the edge key -> expect 101 upgrade + StatusState JSON frames
websocat -H "x-ems-edge-key: <secret>" "wss://ems.kenas.be/status-ws"

# without it (or a wrong value) -> blocked by the WAF (HTTP 403, no upgrade)
websocat "wss://ems.kenas.be/status-ws"

# /ws with the key + correct creds: after the Authenticate frame, expect Authenticated + updates
websocat -H "x-ems-edge-key: <secret>" "wss://ems.kenas.be/ws"
# then type: {"type":"Authenticate","username":"<cfg user>","password":"<cfg pass>"}

# not exposed
curl -i https://ems.kenas.be/ocpp-ui   # expect 404
```

**LAN unchanged:** `http://<NAS-LAN-IP>:8080/status` still serves `status.html` with no creds;
the charger still connects to `ws://<NAS-LAN-IP>:8080/ocpp/CP01`.

## 8. Configure the app

App **Settings**: server `ems.kenas.be`, **Use TLS on**, **Edge key** = the `<SECRET>` from step 6,
username/password = the `websocket` creds from `deploy/config.yaml`.

## Operations

- **Rotate the edge key:** generate a new secret, update the WAF rule's expression, then update the
  app's **Edge key**.
- **Rotate websocket creds:** edit `deploy/config.yaml`, `docker compose restart ems-server`,
  update the app.
- **Update the server:** rebuild on the build machine (`deploy/build-podman.sh`), copy the new
  `ems-images.tar.gz` to the NAS, then `docker load -i ems-images.tar.gz && docker compose up -d`.
- **Logs:** `docker compose logs -f cloudflared` / `docker compose logs -f ems-server`.
