#!/usr/bin/env bash
#
# Bring up (and tear down) the NetBird + traefik/whoami validation harness.
#
# Goal: confirm whether a NetBird Reverse Proxy "Domain" target resolves a Docker
# network alias (whoami.lan) via the routing peer's resolver. See docker-compose.yml.
#
# Usage:
#   NB_SETUP_KEY=<reusable-setup-key> ./run.sh up     # start + local DNS sanity check
#   ./run.sh logs                                     # follow peer logs
#   ./run.sh down                                     # stop + remove the volume
#
# The NetBird cloud Reverse Proxy service itself is configured in the dashboard
# (can't be scripted); `up` prints the exact steps and the final curl to run.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

NET_NAME="netbird-whoami-testnet"
PEER_NAME="whoami-test"
ALIAS="whoami.lan"

# Pick a working "docker compose" invocation.
if docker compose version >/dev/null 2>&1; then
    DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    DC=(docker-compose)
else
    echo "ERROR: need 'docker compose' (v2) or 'docker-compose' on PATH." >&2
    exit 1
fi

cmd="${1:-up}"
case "$cmd" in
    up)
        : "${NB_SETUP_KEY:?set NB_SETUP_KEY=<reusable netbird setup key> in the environment}"
        echo "==> Starting whoami + rootless netbird peer ($PEER_NAME)"
        "${DC[@]}" up -d

        echo "==> Recent netbird logs (expect login/connect success):"
        sleep 3
        "${DC[@]}" logs --tail 20 netbird || true

        echo
        echo "==> Local sanity check: does '$ALIAS' resolve + respond on the bridge?"
        if docker run --rm --network "$NET_NAME" curlimages/curl:latest \
                -fsS --max-time 5 "http://$ALIAS" ; then
            echo "    OK — Docker DNS resolves '$ALIAS' to whoami and it responds."
        else
            echo "    FAILED — the alias did not resolve/respond on the bridge." >&2
            echo "    Fix this before touching the dashboard." >&2
        fi

        cat <<EOF

==> Now validate end-to-end through the NetBird Reverse Proxy (manual, in the dashboard):

  1. Peers       → confirm peer '$PEER_NAME' is Connected.
  2. Reverse Proxy → Add service (HTTP). Use the auto-assigned subdomain
                     (quickest), or attach a custom domain via CNAME.
  3. Add target  → Type: Domain
                   Host: $ALIAS
                   Protocol: HTTP   Port: 80
                   Routing peer: $PEER_NAME
  4. Wait for the service to go active (TLS issued), then from anywhere:

       curl https://<assigned-domain>/

     EXPECT the whoami echo (Hostname / IP / request headers). If you see it,
     NetBird resolved the Domain target via the peer's resolver -> option 2 works.
     If it errors at the edge with no backend, the peer is NOT using Docker DNS
     for Domain targets -> fall back to a Host target with a static container IP.

  Tear down when done:  ./run.sh down
EOF
        ;;
    logs)
        "${DC[@]}" logs -f
        ;;
    down)
        echo "==> Stopping and removing the harness (incl. volume)"
        "${DC[@]}" down -v
        ;;
    *)
        echo "Usage: NB_SETUP_KEY=<key> ./run.sh [up|logs|down]" >&2
        exit 2
        ;;
esac
