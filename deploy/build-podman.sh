#!/usr/bin/env bash
#
# Build the EMS deployment images with Podman and bundle them into a single
# tarball for transfer to the NAS.
#
# Produces ONE gzipped multi-image archive containing both containers:
#   - ems-server  (built from this repo's Dockerfile)
#   - netbird     (rootless image, pulled from Docker Hub)
#
# The archive is in docker-archive format, so `docker load` on the NAS reads it
# directly. Then `docker compose up -d` runs both (see docker-compose.yml).
#
# Usage:
#   deploy/build-podman.sh                      # build + pull + bundle -> ems-images.tar.gz
#   deploy/build-podman.sh --tag v1.2.3         # tag the ems-server image
#   deploy/build-podman.sh --output /tmp/x.tar.gz   # write the bundle elsewhere
#
# Transfer + run on the NAS:
#   scp ems-images.tar.gz docker-compose.yml deploy/config.yaml admin@<NAS>:/Volume1/docker/ems/
#   ssh admin@<NAS>
#   cd /Volume1/docker/ems
#   docker load -i ems-images.tar.gz
#   NB_SETUP_KEY=<key> docker compose up -d

set -euo pipefail

# --- defaults ---
# Podman namespaces local builds under localhost/. Keep that prefix in the tag so the
# image name in the archive matches docker-compose.yml after `docker load` on the NAS
# (a bare `ems-server:latest` would be normalized to docker.io/library/... and not match).
IMAGE="localhost/ems-server"
TAG="latest"
NETBIRD_IMAGE="docker.io/netbirdio/netbird:rootless-latest"
OUTPUT=""   # resolved to <repo>/ems-images.tar.gz after we know the repo root

# --- parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)
            TAG="${2:?--tag needs a value}"
            shift 2
            ;;
        --output|-o)
            OUTPUT="${2:?--output needs a path}"
            shift 2
            ;;
        -h|--help)
            sed -n '2,26p' "$0"
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
OUTPUT="${OUTPUT:-$REPO_ROOT/ems-images.tar.gz}"

if ! command -v podman >/dev/null 2>&1; then
    echo "ERROR: podman is not installed or not on PATH." >&2
    exit 1
fi

if [[ ! -f "$REPO_ROOT/Dockerfile" ]]; then
    echo "ERROR: Dockerfile not found at $REPO_ROOT/Dockerfile" >&2
    exit 1
fi

echo "==> Building $IMAGE:$TAG from $REPO_ROOT/Dockerfile"
podman build -t "$IMAGE:$TAG" -f "$REPO_ROOT/Dockerfile" "$REPO_ROOT"

echo "==> Pulling $NETBIRD_IMAGE"
podman pull "$NETBIRD_IMAGE"

echo "==> Bundling both images into $OUTPUT"
# -m / --multi-image-archive: put several images in one docker-archive (docker-loadable).
podman save -m "$IMAGE:$TAG" "$NETBIRD_IMAGE" | gzip > "$OUTPUT"

echo "==> Done. Wrote $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) containing:"
echo "      $IMAGE:$TAG"
echo "      $NETBIRD_IMAGE"
echo
echo "    Load on the NAS with:  docker load -i $(basename "$OUTPUT")"
