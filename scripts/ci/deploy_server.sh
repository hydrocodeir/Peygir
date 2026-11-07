#!/usr/bin/env bash
set -euo pipefail
mode=${1:-}
[[ "$mode" == check || "$mode" == publish ]] || { echo 'Expected check or publish' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/ssh_common.sh"
if [[ "$mode" == check ]]; then
  remote check "$VPS_APP_DIR" < "$script_dir/remote_server_deploy.sh"
  exit 0
fi
archive=${2:?server archive}; version=${3:?server version}; commit=${4:?Git SHA}
[[ -f "$archive" && "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$commit" =~ ^[a-f0-9]{40}$ ]] || exit 1
origin=${UPDATE_ORIGIN:-https://peygir.hydrocode.ir}
remote check "$VPS_APP_DIR" < "$script_dir/remote_server_deploy.sh"
stage=$(remote "$VPS_APP_DIR" <<'REMOTE'
set -euo pipefail
test -d "$1/.device-private/vps"
mktemp -d "$1/.device-private/vps/server-release.XXXXXX"
REMOTE
)
[[ "$stage" == "$VPS_APP_DIR/.device-private/vps/server-release."* && "${stage##*/}" =~ ^server-release\.[a-zA-Z0-9]+$ ]] || exit 1
checksum=$(sha256sum "$archive"); checksum=${checksum%% *}
"${ssh[@]}" "$VPS_USER@$VPS_HOST" "umask 077; cat > $stage/package.tar.gz" < "$archive"
# Execute a transferred release only after checking the complete archive checksum.
remote "$VPS_APP_DIR" "$stage" "$version" "$commit" "$origin" "$checksum" <<'REMOTE'
set -euo pipefail
printf '%s  %s\n' "$6" "$2/package.tar.gz" | sha256sum -c -
tar --no-same-owner -xzf "$2/package.tar.gz" -C "$2"
exec bash "$2/scripts/ci/remote_server_deploy.sh" publish "$1" "$2" "$3" "$4" "$5"
REMOTE
