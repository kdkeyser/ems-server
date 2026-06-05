#!/bin/bash
#
# Ship the built image bundle to the NAS and restart the stack.
#
# Prerequisite: build the bundle first with `deploy/build-podman.sh`, which produces
# ems-images.tar.gz (ems-server:latest + cloudflared) in the repo root.
#
# This script then: scp's the tarball over, `docker load`s it, and bounces the
# docker-compose stack on the new image.
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

# --- optional non-interactive auth ---
# Set NAS_PASSWORD to skip the prompts; it's reused for both scp and ssh. Requires sshpass
# (Debian/Ubuntu: apt install sshpass). Leave it unset to authenticate normally (prompt, or
# an SSH key / agent). A key via `ssh-copy-id -p 9222 $NAS_HOST` is the cleaner long-term fix.
SCP=(scp)
SSH=(ssh)
if [[ -n "${NAS_PASSWORD:-}" ]]; then
    if ! command -v sshpass >/dev/null 2>&1; then
        echo "error: NAS_PASSWORD is set but sshpass is not installed (apt install sshpass)" >&2
        exit 1
    fi
    export SSHPASS="$NAS_PASSWORD"   # sshpass -e reads the password from $SSHPASS, not argv
    SCP=(sshpass -e scp)
    SSH=(sshpass -e ssh)
fi

echo "==> Copying $TARBALL -> $NAS_HOST:$REMOTE_DIR/"
# -O forces the legacy SCP protocol (the NAS's old OpenSSH lacks SFTP-server support).
"${SCP[@]}" -P "$SSH_PORT" -O "$TARBALL" "$NAS_HOST:$REMOTE_DIR/$TARBALL"

echo "==> Loading image + restarting the stack on the NAS"
# Unquoted heredoc: $REMOTE_DIR / $TARBALL are expanded locally so the paths stay defined
# in one place above; nothing else in the remote script uses '$'.
"${SSH[@]}" -p "$SSH_PORT" "$NAS_HOST" "bash -s" <<REMOTE
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
