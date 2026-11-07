#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
compose=(docker compose --env-file "$root/.device-private/vps/compose.env" -f "$root/vps/compose.nginx.yaml")
managed="$root/.device-private/vps/deployed-api.yaml"
if [[ -f "$managed" ]]; then compose+=(-f "$managed"); fi
exec "${compose[@]}" "$@"
