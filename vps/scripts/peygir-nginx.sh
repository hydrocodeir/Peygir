#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
exec docker compose --env-file "$root/.device-private/vps/compose.env" \
  -f "$root/vps/compose.nginx.yaml" "$@"
