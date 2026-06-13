#!/usr/bin/env bash
#
# Build the EMS deployment bundle with Podman.
#
# Produces ONE self-contained zip (ems-deploy.zip) for transfer to the NAS, containing
# everything needed to run `docker compose up -d`:
#   - ems-images.tar.gz   multi-image docker-archive: ems-server (local build) + cloudflared
#   - docker-compose.yml
#   - .env.example        the secrets the compose needs (copy to .env and fill in on the NAS)
#   - DEPLOY.md           step-by-step deploy instructions
#   - deploy/             all config the compose mounts: EMS config.yaml, ClickHouse schema +
#                         read-only user, Grafana provisioning + dashboards
#
# The clickhouse and grafana images are NOT bundled — `docker compose up` pulls them on the NAS
# (which already needs outbound internet for the Grafana plugin and cloudflared:latest).
#
# Speed: by default the fat jar is built ON THE HOST (`./gradlew shadowJar` — warm Gradle cache,
# daemon, incremental compile: seconds) and packaged into a slim single-stage image. Pass
# --in-container to do a fully self-contained clean build inside Docker instead (the old behaviour;
# minutes, but needs no host JDK).
#
# Usage:
#   deploy/build-podman.sh                         # host-build the jar + bundle -> ems-deploy.zip
#   deploy/build-podman.sh --in-container          # build the jar inside Docker (slow, hermetic)
#   deploy/build-podman.sh --tag v1.2.3            # tag the ems-server image
#   deploy/build-podman.sh --output /tmp/x.zip     # write the zip elsewhere
#
# Deploy on the NAS:
#   scp ems-deploy.zip admin@<NAS>:/Volume1/docker/ems/
#   ssh admin@<NAS>
#   cd /Volume1/docker/ems && unzip -o ems-deploy.zip
#   docker load -i ems-images.tar.gz
#   cp .env.example .env && nano .env        # fill TUNNEL_TOKEN + the two Grafana passwords
#   docker compose up -d

set -euo pipefail

# --- defaults ---
# Podman namespaces local builds under localhost/. Keep that prefix so the image name in
# the archive matches docker-compose.yml after `docker load` on the NAS (a bare
# ems-server:latest would be normalized to docker.io/library/... and not match).
IMAGE="localhost/ems-server"
TAG="latest"
CLOUDFLARED_IMAGE="docker.io/cloudflare/cloudflared:latest"
OUTPUT=""        # resolved to <repo>/ems-deploy.zip after we know the repo root
IN_CONTAINER=false

# --- parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --in-container)
            IN_CONTAINER=true
            shift
            ;;
        --tag)
            TAG="${2:?--tag needs a value}"
            shift 2
            ;;
        --output|-o)
            OUTPUT="${2:?--output needs a path}"
            shift 2
            ;;
        -h|--help)
            sed -n '2,34p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Run with --help for usage." >&2
            exit 2
            ;;
    esac
done

# --- locate repo root (this script lives in deploy/) ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="${OUTPUT:-$REPO_ROOT/ems-deploy.zip}"

# podman + zip are always needed; the host build path also needs the Gradle wrapper.
for tool in podman zip; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: $tool is not installed or not on PATH." >&2
        exit 1
    fi
done

if [[ ! -f "$REPO_ROOT/docker-compose.yml" ]]; then
    echo "ERROR: docker-compose.yml not found at $REPO_ROOT/docker-compose.yml" >&2
    exit 1
fi

# Single cleanup hook for every temp dir we create below (bash keeps only one EXIT trap).
STAGE=""
PKGCTX=""
cleanup() { [[ -n "$STAGE" ]] && rm -rf "$STAGE"; [[ -n "$PKGCTX" ]] && rm -rf "$PKGCTX"; }
trap cleanup EXIT

if [[ "$IN_CONTAINER" == "true" ]]; then
    # Fully self-contained clean build inside Docker (the multi-stage Dockerfile). No host JDK
    # needed, but no warm cache/daemon either, so it re-downloads deps and clean-compiles: minutes.
    [[ -f "$REPO_ROOT/Dockerfile" ]] || { echo "ERROR: Dockerfile not found at $REPO_ROOT/Dockerfile" >&2; exit 1; }
    echo "==> Building $IMAGE:$TAG in-container (multi-stage) from $REPO_ROOT/Dockerfile"
    podman build -t "$IMAGE:$TAG" -f "$REPO_ROOT/Dockerfile" "$REPO_ROOT"
else
    # Host build: reuse the warm Gradle cache + daemon + incremental compile (seconds), then drop
    # the prebuilt jar into a slim single-stage image.
    [[ -f "$REPO_ROOT/Dockerfile.package" ]] || { echo "ERROR: Dockerfile.package not found at $REPO_ROOT/Dockerfile.package" >&2; exit 1; }
    [[ -x "$REPO_ROOT/gradlew" ]] || { echo "ERROR: $REPO_ROOT/gradlew not found or not executable" >&2; exit 1; }

    echo "==> Building the fat jar on the host: ./gradlew shadowJar"
    ( cd "$REPO_ROOT" && ./gradlew shadowJar )

    JAR="$REPO_ROOT/build/libs/ems-server-all.jar"
    [[ -f "$JAR" ]] || { echo "ERROR: expected fat jar not found at $JAR after shadowJar" >&2; exit 1; }

    echo "==> Packaging $IMAGE:$TAG from the prebuilt jar (slim single-stage image)"
    # Build from a tiny dedicated context (just the jar + Dockerfile) so the image build is a fast
    # COPY, and so .dockerignore's `build/` exclusion doesn't hide the jar from the build context.
    PKGCTX="$(mktemp -d)"
    cp "$JAR" "$PKGCTX/ems-server.jar"
    cp "$REPO_ROOT/Dockerfile.package" "$PKGCTX/Dockerfile"
    podman build -t "$IMAGE:$TAG" "$PKGCTX"
    rm -rf "$PKGCTX"; PKGCTX=""
fi

echo "==> Pulling $CLOUDFLARED_IMAGE"
podman pull "$CLOUDFLARED_IMAGE"

# --- assemble the deployment bundle in a staging dir ---
STAGE="$(mktemp -d)"

echo "==> Saving images -> ems-images.tar.gz"
# -m / --multi-image-archive: several images in one docker-archive (docker-loadable). gzip -1: the
# image layers are largely incompressible already, so the fastest level gives near-identical size
# for far less CPU. Kept as gzip (not zstd) so the NAS's old `docker load` can decompress it.
podman save -m "$IMAGE:$TAG" "$CLOUDFLARED_IMAGE" | gzip -1 > "$STAGE/ems-images.tar.gz"

echo "==> Collecting compose + mounted config"
cp "$REPO_ROOT/docker-compose.yml" "$STAGE/"

# Config the compose mounts, preserving the ./deploy/... layout it expects.
mkdir -p "$STAGE/deploy/clickhouse/users.d" "$STAGE/deploy/grafana"
cp "$REPO_ROOT/deploy/clickhouse-init.sql"            "$STAGE/deploy/"
cp "$REPO_ROOT/deploy/config.yaml.template"           "$STAGE/deploy/"
cp "$REPO_ROOT/deploy/clickhouse/users.d/grafana.xml" "$STAGE/deploy/clickhouse/users.d/"
cp -r "$REPO_ROOT/deploy/grafana/provisioning"        "$STAGE/deploy/grafana/"
cp -r "$REPO_ROOT/deploy/grafana/dashboards"          "$STAGE/deploy/grafana/"

# The real, filled-in EMS config is gitignored (it has device IPs + credentials). Include it if
# present so the bundle is turnkey; otherwise seed config.yaml from the template and warn.
if [[ -f "$REPO_ROOT/deploy/config.yaml" ]]; then
    cp "$REPO_ROOT/deploy/config.yaml" "$STAGE/deploy/config.yaml"
else
    cp "$REPO_ROOT/deploy/config.yaml.template" "$STAGE/deploy/config.yaml"
    echo "    WARNING: deploy/config.yaml not found — seeded deploy/config.yaml from the template."
    echo "             Edit it on the NAS before starting (device IPs + websocket credentials)."
fi

# Secrets the compose reads from .env (kept out of the repo). Ship a template, not real values;
# docker compose auto-loads .env from the compose directory.
cat > "$STAGE/.env.example" <<'ENV'
# Copy to .env and fill in. docker compose refuses to start if any of these are unset.
# Cloudflare Tunnel token (from the Cloudflare dashboard tunnel).
TUNNEL_TOKEN=
# Grafana admin login password (username: admin).
GRAFANA_ADMIN_PASSWORD=
# Password for the read-only ClickHouse user that Grafana queries with.
GRAFANA_CH_PASSWORD=
ENV

cat > "$STAGE/DEPLOY.md" <<'MD'
# EMS deployment bundle

Self-contained bundle: container images (`ems-images.tar.gz`), `docker-compose.yml`, the
config the compose mounts (under `deploy/`), and `.env.example`.

## Steps (on the NAS)

```bash
unzip -o ems-deploy.zip
docker load -i ems-images.tar.gz          # loads ems-server + cloudflared
cp .env.example .env && nano .env         # fill TUNNEL_TOKEN + the two Grafana passwords
docker compose up -d                      # pulls clickhouse + grafana, starts everything
```

`docker compose` pulls `clickhouse/clickhouse-server` and `grafana/grafana-oss` from their
registries — the NAS needs outbound internet (also required for Grafana's ClickHouse plugin).

## What runs

| Service     | Purpose                                                           |
|-------------|-------------------------------------------------------------------|
| ems-server  | the EMS service (port 8080: OCPP inbound + LAN admin pages)        |
| cloudflared | Cloudflare Tunnel (remote `/ws` + `/status-ws`)                   |
| clickhouse  | power-history store (internal to the bridge, no published ports)  |
| grafana     | dashboards on `http://<nas-ip>:3000` (LAN-only; log in as `admin`)|

Edit `deploy/config.yaml` for device IPs and credentials before the first start.
MD

echo "==> Zipping -> $OUTPUT"
rm -f "$OUTPUT"
# Zip from inside the staging dir so paths are relative (docker-compose.yml + deploy/... at the
# root of the archive). -X drops extra file attributes; recursion includes dotfiles (.env.example).
( cd "$STAGE" && zip -q -r -X "$OUTPUT" . )

echo "==> Done. Wrote $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) containing:"
( cd "$STAGE" && find . -type f | sed 's|^\./|      |' | sort )
echo
echo "    Deploy: scp ems-deploy.zip to the NAS, unzip, 'docker load -i ems-images.tar.gz',"
echo "    fill .env, then 'docker compose up -d'. See DEPLOY.md in the bundle."
