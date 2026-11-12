#!/usr/bin/env bash
# Keep generated credentials out of tracing, arguments, Helm values, and output.
set +x
set -euo pipefail
umask 077

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export PATH="$repo_root/.tooling/bin:$PATH"
export KUBECONFIG=${KUBECONFIG:-"$repo_root/.tooling/kubeconfig"}

if (( $# != 0 )); then
  printf 'Usage: %s\n' "$0" >&2
  exit 2
fi
for command in kubectl helm openssl base64; do
  command -v "$command" >/dev/null 2>&1 || { printf 'Missing command: %s\n' "$command" >&2; exit 1; }
done

# The status path is read-only and validates context plus the local kind cluster CA.
"$repo_root/scripts/local-cluster.sh" status >/dev/null

secret_name=skulker-grafana-credentials
existing=$(kubectl --context kind-skulker --namespace skulker get secret "$secret_name" --ignore-not-found -o name)
if [[ -z "$existing" ]]; then
  password=$(openssl rand -hex 24)
  encoded=$(printf '%s' "$password" | base64 | tr -d '\n')
  printf '{"apiVersion":"v1","kind":"Secret","metadata":{"name":"%s","namespace":"skulker"},"type":"Opaque","data":{"admin-password":"%s"}}' \
    "$secret_name" "$encoded" |
    kubectl --context kind-skulker --namespace skulker create -f -
  unset password encoded
else
  printf 'Preserving existing Grafana credentials Secret.\n'
fi

# Include new chart defaults, retain prior release overrides, then enable monitoring.
helm upgrade skulker "$repo_root/infrastructure/helm/skulker" \
  --kube-context kind-skulker --namespace skulker \
  --reset-then-reuse-values --set monitoring.enabled=true \
  --set-string "monitoring.grafana.existingSecret=$secret_name" --wait --timeout 10m

printf 'Private monitoring is ready: services skulker-prometheus:9090 and skulker-grafana:3000.\n'
printf 'Grafana administrator credentials remain only in Secret %s.\n' "$secret_name"
