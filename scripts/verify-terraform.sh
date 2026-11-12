#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export PATH="$repo_root/.tooling/bin:$PATH"
export TF_IN_AUTOMATION=1 CHECKPOINT_DISABLE=1
# Mock-provider validation must never consult developer AWS profiles or instance metadata.
unset AWS_PROFILE AWS_DEFAULT_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
unset AWS_CONTAINER_CREDENTIALS_FULL_URI AWS_CONTAINER_CREDENTIALS_RELATIVE_URI
unset AWS_WEB_IDENTITY_TOKEN_FILE AWS_ROLE_ARN AWS_ROLE_SESSION_NAME
export AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_CONFIG_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true
if (( $# != 0 )); then
  printf 'Usage: %s\n' "$0" >&2
  exit 2
fi
command -v terraform >/dev/null 2>&1 || { printf 'Run scripts/bootstrap-terraform.sh first.\n' >&2; exit 1; }
terraform fmt -check -recursive "$repo_root/infrastructure/terraform"
terraform -chdir="$repo_root/infrastructure/terraform/environments/aws" init -backend=false -input=false -lockfile=readonly
terraform -chdir="$repo_root/infrastructure/terraform/environments/aws" validate -no-color
# The named test uses only mock_provider and command=plan; no real AWS plan/apply is run.
terraform -chdir="$repo_root/infrastructure/terraform/environments/aws" test -no-color -filter=tests/foundation.tftest.hcl
