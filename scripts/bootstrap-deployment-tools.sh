#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
docker_command=${DOCKER_COMMAND:-docker}
tooling_dir="$repo_root/.tooling/bin"

# Binary downloads and hashes are pinned in this development-only tools image.
tar -C "$repo_root" --exclude='*/.git' --exclude='*/.tooling' --exclude='*/build' \
  --exclude='*/.gradle' --exclude='*/.env' --exclude='*/.env.*' --exclude='*/.aws' \
  --exclude='*.key' --exclude='*.pem' --exclude='*.tfstate' --exclude='*.tfstate.*' \
  -cf - . | "$docker_command" build -f infrastructure/docker/Dockerfile.tools -t skulker-tools:local -

container_id=$("$docker_command" create skulker-tools:local | tr -d '\r')
trap '"$docker_command" rm -v "$container_id" >/dev/null' EXIT
mkdir -p "$tooling_dir"
for tool in kind kubectl helm; do
  "$docker_command" cp "$container_id:/usr/local/bin/$tool" - | tar -xf - -C "$tooling_dir"
  chmod +x "$tooling_dir/$tool"
done
printf 'Pinned deployment tools installed in %s\n' "$tooling_dir"
