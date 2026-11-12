#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
docker_command=${DOCKER_COMMAND:-docker}
reports_dir="$repo_root/backend/build/docker-verification"

# Stream the build context so Docker Desktop's Windows CLI needs no WSL bind mount.
tar -C "$repo_root/backend" --exclude='./.gradle' --exclude='*/build' -cf - . |
  "$docker_command" build -t skulker-verify:local -f Dockerfile.verify -

container_id=$("$docker_command" create \
  --add-host host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v skulker-verification-gradle:/root/.gradle \
  skulker-verify:local | tr -d '\r')
trap '"$docker_command" rm -f "$container_id" >/dev/null' EXIT

"$docker_command" start -a "$container_id" || true
container_state=$("$docker_command" inspect -f '{{.State.Status}}' "$container_id" | tr -d '\r')
if [[ "$container_state" != exited ]]; then
  printf 'Verification container did not exit normally (state: %s).\n' "$container_state" >&2
  exit 1
fi
check_status=$("$docker_command" inspect -f '{{.State.ExitCode}}' "$container_id" | tr -d '\r')
mkdir -p "$reports_dir"
if "$docker_command" cp "$container_id:/workspace/api-service/build/test-results" - |
    tar -xf - -C "$reports_dir"; then
  printf 'Integration test XML copied to %s\n' "$reports_dir/test-results"
else
  printf 'No API test reports were produced; inspect build output above.\n' >&2
fi
exit "$check_status"
