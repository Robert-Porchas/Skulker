#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
version=1.17.2
if (( $# != 0 )); then
  printf 'Usage: %s\n' "$0" >&2
  exit 2
fi
[[ $(uname -s) == Linux ]] || { printf 'This bootstrap supports Linux only.\n' >&2; exit 1; }
case $(uname -m) in
  x86_64) architecture=amd64; checksum=e6f16b2f0b137ee5729caa8c4e79a3bae4ee261acd198a1fcfa8584a6296605b ;;
  aarch64|arm64) architecture=arm64; checksum=858e08c181a76b6b223e8c767a8e983922eebe2d062db949a2de66ce040c368c ;;
  *) printf 'Unsupported architecture.\n' >&2; exit 1 ;;
esac
for command in curl python3 sha256sum; do
  command -v "$command" >/dev/null 2>&1 || { printf 'Missing command: %s\n' "$command" >&2; exit 1; }
done
archive=$(mktemp)
trap 'rm -f -- "$archive"' EXIT
curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 180 \
  "https://releases.hashicorp.com/terraform/$version/terraform_${version}_linux_${architecture}.zip" -o "$archive"
printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c >/dev/null
mkdir -p "$repo_root/.tooling/bin"
python3 - "$archive" "$repo_root/.tooling/bin/terraform" <<'PY'
import os
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

target = Path(sys.argv[2])
temporary = None
try:
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
        temporary = Path(output.name)
        with zipfile.ZipFile(sys.argv[1]) as archive, archive.open("terraform") as binary:
            shutil.copyfileobj(binary, output)
    temporary.chmod(0o755)
    os.replace(temporary, target)
finally:
    if temporary is not None:
        temporary.unlink(missing_ok=True)
PY
printf 'Verified Terraform %s installed in %s/.tooling/bin.\n' "$version" "$repo_root"
