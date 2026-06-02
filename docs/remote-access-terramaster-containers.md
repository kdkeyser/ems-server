# Deploying on TerraMaster (individual containers, no Compose)

TerraMaster TOS runs Docker but does **not** support `docker compose`. This guide sets up the
same two containers as `docker-compose.yml` — `ems-server` and the `netbird` agent — as
**individual containers** via SSH `docker run`. It replaces **step 3** of
[`remote-access-netbird-runbook.md`](remote-access-netbird-runbook.md); steps 1–2 (config +
setup key) and steps 4–7 (NetBird Reverse Proxy service, DNS/CNAME, validation) of that runbook
still apply unchanged.

> **Why SSH and not the TOS Docker GUI?** The `netbird` container needs a Linux capability
> (`NET_ADMIN`) and a device mapping (`/dev/net/tun`) to bring up its WireGuard interface. The TOS
> Docker GUI does not expose those, so the `netbird` container must be created from the command
> line. `ems-server` can be done either way; CLI is shown here so both are consistent.

## 0. Enable SSH

TOS → **Control Panel → Terminal & SNMP → enable SSH**. Then from your computer:

```bash
ssh admin@<NAS-LAN-IP>
```

If `docker` needs root on your TOS version, prefix the commands below with `sudo`.

Pick a folder on a storage volume to hold the config and (optionally) the database, e.g.
`/Volume1/docker/ems`. Adjust `/Volume1` to your actual volume name (`/Volume1`, `/Volume2`, …).

```bash
mkdir -p /Volume1/docker/ems
```

## 1. Get the `ems-server` image onto the NAS

The `netbird` image is pulled from Docker Hub automatically (step 4). The `ems-server` image is
built from this repo — choose **one** of these:

### Option A — build on the NAS (repo checked out on the NAS)

```bash
cd /Volume1/docker/ems-server-src     # wherever you cloned the repo
docker build -t ems-server:latest .
```

### Option B — build on your dev machine, transfer the image

On your dev machine (in the repo):

```bash
docker build -t ems-server:latest .
docker save ems-server:latest | gzip > ems-server.tar.gz
scp ems-server.tar.gz admin@<NAS-LAN-IP>:/Volume1/docker/ems/
```

Then on the NAS:

```bash
gunzip -c /Volume1/docker/ems/ems-server.tar.gz | docker load
```

Confirm the image exists either way:

```bash
docker images | grep ems-server
```

## 2. Prepare the config file

Copy the template (from the repo) to the folder you created and edit it — set real device IPs and
**strong** `websocket` credentials (these must match the app's username/password):

```bash
cp deploy/config.yaml.template /Volume1/docker/ems/config.yaml
vi /Volume1/docker/ems/config.yaml
chmod 644 /Volume1/docker/ems/config.yaml   # readable by the container's non-root user
```

The container runs as a non-root user (`uid 10001`). The config is mounted read-only, so it only
needs to be world-readable (`644`). Leave `database.path: /data/ems.db` as-is — `/data` is a
container volume (next step).

## 3. Create the data volume

The image writes its SQLite DB to `/data`. Use a **named volume** — when first mounted it inherits
the image's `/data` ownership (the non-root `ems` user), so SQLite can write immediately:

```bash
docker volume create ems-data
```

> **Bind-mount alternative:** if you'd rather keep `ems.db` in a visible folder, mount a host dir
> instead of the named volume — but a bind mount uses the *host* dir's ownership, so you must give
> the container's user write access first:
> ```bash
> mkdir -p /Volume1/docker/ems/data
> chown 10001:10001 /Volume1/docker/ems/data
> ```
> and in step 4 replace `-v ems-data:/data` with `-v /Volume1/docker/ems/data:/data`.

## 4. Run the `ems-server` container

```bash
docker run -d \
  --name ems-server \
  --restart unless-stopped \
  --network host \
  -e EMS_CONFIG=/config/config.yaml \
  -v /Volume1/docker/ems/config.yaml:/config/config.yaml:ro \
  -v ems-data:/data \
  ems-server:latest
```

What each flag does:

| Flag | Why |
|------|-----|
| `--network host` | The server must reach LAN devices (Modbus/HTTP at `192.168.129.x`) **and** accept the charger's inbound OCPP connection on `:8080`. Host mode shares the NAS network stack so both work. |
| `-e EMS_CONFIG=/config/config.yaml` | Tells the server to load the mounted file (falls back to the bundled config if absent). |
| `-v …/config.yaml:/config/config.yaml:ro` | Mounts your edited config read-only. Edit on the host + restart to change device IPs/credentials — no rebuild. |
| `-v ems-data:/data` | Persists `ems.db` outside the container so it survives restarts/updates. |
| `--restart unless-stopped` | Comes back automatically after a NAS reboot. |

> **Host networking note:** in `--network host` mode, `-p 8080:8080` is **ignored** — the server
> binds directly to the NAS's port 8080. Make sure nothing else on the NAS already uses 8080
> (`ss -tlnp | grep 8080` should be empty before you start). The TOS web UI uses 8181/8443 by
> default, so 8080 is normally free.

## 5. Run the `netbird` agent container

Use the **reusable setup key** from runbook step 2.

```bash
docker volume create netbird-config

docker run -d \
  --name netbird \
  --restart unless-stopped \
  --network host \
  --cap-add NET_ADMIN \
  --device /dev/net/tun:/dev/net/tun \
  -e NB_SETUP_KEY=<paste-setup-key> \
  -e NB_HOSTNAME=ems-nas \
  -v netbird-config:/etc/netbird \
  netbirdio/netbird:latest
```

| Flag | Why |
|------|-----|
| `--network host` | The agent runs WireGuard on the NAS host and must reach `ems-server` at `127.0.0.1:8080` to forward Reverse-Proxy traffic to it. |
| `--cap-add NET_ADMIN` + `--device /dev/net/tun:/dev/net/tun` | Required to create/manage the WireGuard tunnel interface. (These are why this container needs the CLI, not the GUI.) |
| `-e NB_SETUP_KEY=…` | Enrolls the NAS as a NetBird peer non-interactively. |
| `-e NB_HOSTNAME=ems-nas` | The peer name you'll select as the Reverse-Proxy target in runbook step 4. |
| `-v netbird-config:/etc/netbird` | Persists the peer identity so it doesn't re-enroll on every restart. |

If `/dev/net/tun` does not exist on the NAS, load the module once (and persist if needed):

```bash
[ -e /dev/net/tun ] || (modprobe tun && ls -l /dev/net/tun)
```

## 6. Verify

```bash
docker ps                                   # both containers "Up"
docker logs -f ems-server                   # expect startup on :8080, device polling
docker logs -f netbird                       # expect "peer connected" / login success
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/status   # expect 200 (LAN, no auth)
```

In the NetBird dashboard → **Peers**, confirm `ems-nas` is connected. Then continue with
**runbook steps 4–7** (create the RP HTTP service with header auth + `/ws` and `/status-ws` path
routes, add the `ec29.ems` CNAME, validate end-to-end with `websocat`).

## 7. Operations (individual containers)

```bash
# Change device IPs / credentials: edit the host config, then restart just the server
vi /Volume1/docker/ems/config.yaml && docker restart ems-server

# Update the server after a code change (rebuild/reload the image, then recreate the container)
docker rm -f ems-server
# (rebuild or re-load the image per step 1)
docker run -d --name ems-server --restart unless-stopped --network host \
  -e EMS_CONFIG=/config/config.yaml \
  -v /Volume1/docker/ems/config.yaml:/config/config.yaml:ro \
  -v ems-data:/data \
  ems-server:latest

# Logs / stop / start
docker logs --tail 100 ems-server
docker stop ems-server netbird
docker start ems-server netbird
```

The `ems-data` and `netbird-config` volumes persist across `docker rm`/recreate, so the database
and the NetBird peer identity are preserved when you update the containers.
