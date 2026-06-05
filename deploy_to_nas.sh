#!/bin/bash
#
# Ship the built image bundle to the NAS and restart the stack.
#
# Prerequisite: build the bundle first with `deploy/build-podman.sh`, which produces
# ems-images.tar.gz (ems-server:latest + cloudflared) in the repo root.
#
# This script then: scp's the tarball over, `docker load`s it, and bounces the
# docker-compose stack on the new image. scp + ssh share one multiplexed SSH
# connection, so a password (if any) is entered once.
set -euo pipefail

# --- target ---
NAS_HOST="nasmanager@nas-terramark.local"
SSH_PORT=9222
REMOTE_DIR="/home/nasmanager/ems"
TARBALL="ems-images.tar.gz"

# Resolve paths relative to the repo root regardless of where this is invoked from.
cd "$(dirname "$0")"

if [[ ! -f "$TARBALL" ]]; then
    echo "error: $TARBALL not found — build it first with deploy/build-podman.sh" >&2
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

echo "==> Copying $TARBALL -> $NAS_HOST:$REMOTE_DIR/"
# -O forces the legacy SCP protocol (the NAS's old OpenSSH lacks SFTP-server support).
scp "${MUX[@]}" -P "$SSH_PORT" -O "$TARBALL" "$NAS_HOST:$REMOTE_DIR/$TARBALL"

echo "==> Loading image + restarting the stack on the NAS"
# Unquoted heredoc: $REMOTE_DIR / $TARBALL are expanded locally so the paths stay defined
# in one place above; nothing else in the remote script uses '$'.
ssh "${MUX[@]}" -p "$SSH_PORT" "$NAS_HOST" "bash -s" <<REMOTE
set -euo pipefail
cd "$REMOTE_DIR"

# Load the freshly-built images from the archive (replaces ems-server:latest in place).
docker load -i "$TARBALL"

# The NAS runs an old Docker, so it's docker-compose (v1), NOT "docker compose".
# 'down' stops both services; the named ems-data volume (SQLite DB) survives because
# we don't pass -v. 'up -d' recreates everything on the just-loaded image. TUNNEL_TOKEN
# is read from the .env file already in $REMOTE_DIR.
docker-compose down
docker-compose up -d

docker-compose ps
echo "--- ems-server (last 20 log lines) ---"
docker-compose logs --tail=20 ems-server
REMOTE

echo "==> Done. Follow logs with:"
echo "    ssh -p $SSH_PORT $NAS_HOST 'cd $REMOTE_DIR && docker-compose logs -f ems-server'"
