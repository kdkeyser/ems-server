#!/bin/bash
#
# Ship the built deployment bundle to the NAS and restart the stack.
#
# Prerequisite: build the bundle first with `deploy/build-podman.sh`, which produces
# ems-deploy.zip in the repo root. The zip is self-contained:
#   - ems-images.tar.gz   ems-server (local build) + cloudflared
#   - docker-compose.yml   the 4-service stack (ems-server, cloudflared, clickhouse, grafana)
#   - deploy/              config the compose mounts (EMS config.yaml, ClickHouse + Grafana)
#   - .env.example         template for the secrets compose needs
# The clickhouse + grafana images are NOT in the tarball; `docker compose up` pulls them
# on the NAS (which already needs outbound internet for cloudflared + the Grafana plugin).
#
# This script then: scp's the zip over, unzips it (refreshing docker-compose.yml + deploy/
# config in place), `docker load`s the images, and bounces the compose stack. scp + ssh share
# one multiplexed SSH connection, so a password (if any) is entered once.
#
# Note: the NAS's .env (TUNNEL_TOKEN + the two Grafana passwords) is NOT shipped — the zip only
# carries .env.example. The .env you filled in on the first deploy persists; named volumes
# (ems-data SQLite, clickhouse-data, grafana-data) survive too since we never pass `down -v`.
set -euo pipefail

# --- target ---
NAS_HOST="nasmanager@nas-terramark.local"
SSH_PORT=9222
REMOTE_DIR="/home/nasmanager/ems"
BUNDLE="ems-deploy.zip"

# Resolve paths relative to the repo root regardless of where this is invoked from.
cd "$(dirname "$0")"

if [[ ! -f "$BUNDLE" ]]; then
    echo "error: $BUNDLE not found — build it first with deploy/build-podman.sh" >&2
    exit 1
fi

# --- authenticate once, reuse for scp + ssh ---
# Open ONE multiplexed master connection; the scp and ssh below reuse it over the same socket,
# so any password is typed once — straight to ssh's prompt, never stored in a variable, file, or
# shell history. Works unchanged with key/agent auth. Multiplexing is a client-side feature, so
# the NAS's old SSH is irrelevant. The trap tears the master down on exit.
CTRL_SOCKET="$(mktemp -u "${TMPDIR:-/tmp}/ems-deploy.XXXXXX")"
MUX=(-o "ControlPath=$CTRL_SOCKET")
trap 'ssh "${MUX[@]}" -O exit "$NAS_HOST" 2>/dev/null || true' EXIT

echo "==> Connecting to $NAS_HOST (enter the password once if prompted)"
ssh "${MUX[@]}" -o ControlMaster=yes -o ControlPersist=300 -p "$SSH_PORT" "$NAS_HOST" true

echo "==> Copying $BUNDLE -> $NAS_HOST:$REMOTE_DIR/"
# -O forces the legacy SCP protocol (the NAS's old OpenSSH lacks SFTP-server support).
scp "${MUX[@]}" -P "$SSH_PORT" -O "$BUNDLE" "$NAS_HOST:$REMOTE_DIR/$BUNDLE"

echo "==> Unzipping bundle, loading images + restarting the stack on the NAS"
# Quoted heredoc (no local expansion); REMOTE_DIR/BUNDLE are passed as remote env vars so the
# remote script can use them without escaping, and remote-side $PATH stays literal. '-ls' runs a
# login shell.
ssh "${MUX[@]}" -p "$SSH_PORT" "$NAS_HOST" \
    "REMOTE_DIR='$REMOTE_DIR' BUNDLE='$BUNDLE' bash -ls" <<'REMOTE'
set -euo pipefail

# A non-interactive SSH session starts with a minimal PATH that often omits docker/unzip (it works
# when you log in by hand because the login profile sets PATH). The login shell (-ls) picks that up;
# also fold in the usual NAS install dirs as a fallback, then fail loudly if a tool is still gone.
for d in /usr/local/bin /usr/local/sbin /opt/bin /opt/sbin /usr/bin /usr/sbin; do
    case ":$PATH:" in *":$d:"*) ;; *) [ -d "$d" ] && PATH="$PATH:$d" ;; esac
done
export PATH
for tool in docker unzip; do
    command -v "$tool" >/dev/null 2>&1 || { echo "error: $tool not found on the NAS. PATH=$PATH" >&2; exit 127; }
done

cd "$REMOTE_DIR"

# Refresh docker-compose.yml + the mounted deploy/ config from the bundle, in place. -o overwrites
# without prompting; this updates the compose file (new clickhouse/grafana services) and config the
# containers mount. .env is NOT in the zip, so the secrets you filled in earlier are left untouched.
unzip -o "$BUNDLE"

# .env must already exist from a prior deploy: compose refuses to start without TUNNEL_TOKEN and the
# two Grafana passwords. Bail early with a clear message rather than a cryptic compose error.
if [[ ! -f .env ]]; then
    echo "error: $REMOTE_DIR/.env is missing. Copy .env.example to .env and fill in" >&2
    echo "       TUNNEL_TOKEN, GRAFANA_ADMIN_PASSWORD, GRAFANA_CH_PASSWORD, then re-run." >&2
    exit 1
fi

# Load the freshly-built images from the archive (replaces ems-server:latest in place; also
# refreshes cloudflared). clickhouse + grafana are pulled by `up` below, not in this archive.
docker load -i ems-images.tar.gz

# The NAS runs an old Docker, so it's docker-compose (v1), NOT "docker compose".
# 'down' stops all four services; the named volumes (ems-data SQLite, clickhouse-data,
# grafana-data) survive because we don't pass -v. 'up -d' recreates everything on the
# just-loaded image and pulls clickhouse + grafana if absent. Secrets come from .env.
docker-compose down
docker-compose up -d

docker-compose ps
echo "--- ems-server (last 20 log lines) ---"
docker-compose logs --tail=20 ems-server
REMOTE

echo "==> Done. Follow logs with:"
echo "    ssh -p $SSH_PORT $NAS_HOST 'cd $REMOTE_DIR && docker-compose logs -f ems-server'"
