#!/usr/bin/env bash
# Secret values must never enter shell tracing, even when invoked with bash -x.
set +x
set -euo pipefail
umask 077

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -d "$repo_root/.tooling/bin" ]]; then
  export PATH="$repo_root/.tooling/bin:$PATH"
fi
cluster_name=skulker
context_name=kind-skulker
namespace=skulker
release_name=skulker
secret_name=skulker-credentials
image_tag=${SKULKER_IMAGE_TAG:-local}
kind_wait=${SKULKER_KIND_WAIT:-120s}
kubeconfig=${KUBECONFIG:-"$repo_root/.tooling/kubeconfig"}
keda_version=2.21.0
keda_sha256=a7da56bf41a33dad9d2fc82a6fdecee552fff947d80bf1e1dbcf4db0f9e26dee
export KUBECONFIG="$kubeconfig"

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command was not found: $1"
}

cluster_exists() {
  local clusters
  clusters=$(kind get clusters) || die "Unable to inspect local kind clusters."
  [[ $'\n'"$clusters"$'\n' == *$'\n'"$cluster_name"$'\n'* ]]
}

assert_owned_config() {
  [[ -s "$kubeconfig" ]] || die "Kubeconfig is missing; run $0 create first."
  local active_context
  active_context=$(kubectl --kubeconfig "$kubeconfig" config current-context)
  [[ "$active_context" == "$context_name" ]] || die "Refusing mutation: current context must be $context_name."
}

assert_local_context() {
  assert_owned_config
  cluster_exists || die "Local kind cluster $cluster_name does not exist."
  local configured_ca expected_ca
  configured_ca=$(kubectl --kubeconfig "$kubeconfig" --context "$context_name" config view \
    --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')
  expected_ca=$(kind get kubeconfig --name "$cluster_name" |
    kubectl --kubeconfig /dev/stdin config view --raw --minify \
      -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')
  [[ -n "$configured_ca" && "$configured_ca" == "$expected_ca" ]] ||
    die "Refusing mutation: kubeconfig certificate authority does not match the local kind cluster."
}

kube() {
  kubectl --kubeconfig "$kubeconfig" --context "$context_name" "$@"
}

helmk() {
  helm --kubeconfig "$kubeconfig" --kube-context "$context_name" "$@"
}

create_cluster() {
  [[ "$kind_wait" =~ ^[0-9]+(s|m)$ ]] || die "SKULKER_KIND_WAIT must be a duration such as 120s or 0s."
  local kind_version
  kind_version=$(kind version)
  [[ "$kind_version" == "kind v0.33.0 "* ]] || die "Skulker requires kind v0.33.0."
  if [[ -s "$kubeconfig" ]]; then
    assert_owned_config
  fi
  if cluster_exists; then
    if [[ ! -s "$kubeconfig" ]]; then
      mkdir -p -- "$(dirname -- "$kubeconfig")"
      kind export kubeconfig --name "$cluster_name" --kubeconfig "$kubeconfig"
    fi
    assert_local_context
    printf 'Local cluster %s already exists.\n' "$cluster_name"
    return
  fi
  mkdir -p -- "$(dirname -- "$kubeconfig")"
  kind create cluster --name "$cluster_name" \
    --config "$repo_root/infrastructure/kubernetes/kind.yaml" \
    --kubeconfig "$kubeconfig" --wait "$kind_wait"
  assert_local_context
  printf 'Created local cluster %s; kubeconfig: %s\n' "$cluster_name" "$kubeconfig"
}

uri_encode() {
  local LC_ALL=C
  local input=$1 encoded='' character escaped index
  for (( index = 0; index < ${#input}; index++ )); do
    character=${input:index:1}
    case "$character" in
      [a-zA-Z0-9.~_-]) encoded+=$character ;;
      *) printf -v escaped '%%%02X' "'$character"; encoded+=$escaped ;;
    esac
  done
  printf '%s' "$encoded"
}

ensure_credentials() {
  local existing
  existing=$(kube --namespace "$namespace" get secret "$secret_name" --ignore-not-found -o name)
  if [[ -n "$existing" ]]; then
    printf 'Preserving existing credentials Secret.\n'
    return
  fi
  require_command openssl
  require_command base64
  local db_password rabbit_password rabbit_encoded rabbit_uri db_data rabbit_data uri_data
  db_password=${SKULKER_DB_PASSWORD:-$(openssl rand -hex 24)}
  rabbit_password=${SKULKER_RABBIT_PASSWORD:-$(openssl rand -hex 24)}
  rabbit_encoded=$(uri_encode "$rabbit_password")
  rabbit_uri="amqp://skulker:$rabbit_encoded@${release_name}-rabbitmq.${namespace}.svc.cluster.local:5672/%2f"
  db_data=$(printf '%s' "$db_password" | base64 | tr -d '\n')
  rabbit_data=$(printf '%s' "$rabbit_password" | base64 | tr -d '\n')
  uri_data=$(printf '%s' "$rabbit_uri" | base64 | tr -d '\n')
  # Send credentials over stdin; preserve an existing Secret even during a concurrent deploy.
  printf '{"apiVersion":"v1","kind":"Secret","metadata":{"name":"%s","namespace":"%s"},"type":"Opaque","data":{"db-password":"%s","rabbit-password":"%s","rabbit-uri":"%s"}}' \
    "$secret_name" "$namespace" "$db_data" "$rabbit_data" "$uri_data" |
    kube create -f -
}

install_keda() {
  require_command curl
  require_command sha256sum
  local archive temporary_archive
  archive="$repo_root/.tooling/charts/keda-$keda_version.tgz"
  mkdir -p -- "$(dirname -- "$archive")"
  if [[ ! -f "$archive" ]] || ! printf '%s  %s\n' "$keda_sha256" "$archive" | sha256sum -c >/dev/null 2>&1; then
    temporary_archive=$(mktemp "$repo_root/.tooling/charts/keda-$keda_version.XXXXXX")
    if ! curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 120 \
      "https://kedacore.github.io/charts/keda-$keda_version.tgz" -o "$temporary_archive"; then
      rm -f -- "$temporary_archive"
      die "Unable to download pinned KEDA chart."
    fi
    if ! printf '%s  %s\n' "$keda_sha256" "$temporary_archive" | sha256sum -c >/dev/null 2>&1; then
      rm -f -- "$temporary_archive"
      die "KEDA chart checksum verification failed."
    fi
    mv -- "$temporary_archive" "$archive"
  fi
  assert_local_context
  helmk upgrade --install keda "$archive" --namespace keda --create-namespace --wait --timeout 10m
}

deploy_cluster() {
  require_command helm
  [[ "$image_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "SKULKER_IMAGE_TAG must be a valid Docker image tag."
  assert_local_context
  kind load docker-image "skulker-api:$image_tag" "skulker-worker:$image_tag" --name "$cluster_name"
  install_keda
  assert_local_context
  kube create namespace "$namespace" --dry-run=client -o yaml | kube apply -f -
  ensure_credentials
  helmk upgrade --install "$release_name" "$repo_root/infrastructure/helm/skulker" \
    --namespace "$namespace" --set-string "existingSecret=$secret_name" \
    --set-string "api.image.tag=$image_tag" --set-string "worker.image.tag=$image_tag" \
    --wait --timeout 10m
  kube --namespace "$namespace" get pods,services,scaledobjects
}

status_cluster() {
  assert_local_context
  kube get nodes
  kube --namespace "$namespace" get pods,services,scaledobjects
  kube --namespace keda get deployments
}

stop_cluster() {
  require_command helm
  assert_local_context
  local release
  release=$(helmk list --namespace "$namespace" --filter '^skulker$' --short)
  if [[ -z "$release" ]]; then
    printf 'Skulker application release is already stopped.\n'
    return
  fi
  helmk uninstall "$release_name" --namespace "$namespace" --wait --timeout 5m
  printf 'Application release removed; credentials, retained PVCs, KEDA, and cluster remain.\n'
}

delete_cluster() {
  if ! cluster_exists; then
    printf 'Local cluster %s is already absent.\n' "$cluster_name"
    return
  fi
  assert_local_context
  kind delete cluster --name "$cluster_name"
  printf 'Deleted local cluster %s and its local volumes.\n' "$cluster_name"
}

if (( $# != 1 )); then
  die "Usage: $0 create|deploy|status|stop|delete"
fi
[[ "$kubeconfig" != *:* ]] || die "Use one Linux kubeconfig path, not a KUBECONFIG path list."
require_command kind
require_command kubectl
case "$1" in
  create) create_cluster ;;
  deploy) deploy_cluster ;;
  status) status_cluster ;;
  stop) stop_cluster ;;
  delete) delete_cluster ;;
  *) die "Usage: $0 create|deploy|status|stop|delete" ;;
esac
