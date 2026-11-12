#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
docker_command=${DOCKER_COMMAND:-docker}
image_tag=${SKULKER_IMAGE_TAG:-local}

if (( $# != 0 )); then
  printf 'Usage: %s (configure DOCKER_COMMAND and SKULKER_IMAGE_TAG through the environment)\n' "$0" >&2
  exit 2
fi
if ! command -v "$docker_command" >/dev/null 2>&1; then
  printf 'Docker command was not found: %s\n' "$docker_command" >&2
  exit 1
fi
if [[ ! "$image_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  printf 'SKULKER_IMAGE_TAG must be a valid Docker image tag.\n' >&2
  exit 2
fi

for module in api-service worker-service; do
  image="skulker-${module%-service}:$image_tag"
  printf 'Building %s\n' "$image"
  # A streamed root context also works with Docker Desktop's Windows CLI from WSL.
  # Keep local credentials, Git metadata, caches, and build artifacts out of that archive.
  tar -C "$repo_root" \
    --exclude='*/build' --exclude='*/.gradle' --exclude='*/.git' \
    --exclude='*/.tooling' --exclude='*/.aws' \
    --exclude='*/.env' --exclude='*/.env.*' --exclude='*.pem' --exclude='*.key' \
    -cf - Dockerfile .dockerignore backend |
    "$docker_command" build --file Dockerfile --build-arg "APP_MODULE=$module" --tag "$image" -
done
