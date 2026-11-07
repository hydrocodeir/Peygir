#!/usr/bin/env bash
# Executed on the GitHub Linux runner; no private key or password is printed.
set -euo pipefail

mode=${1:-}
[[ "$mode" == check || "$mode" == publish ]] || { echo 'Expected check or publish mode' >&2; exit 1; }
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/ssh_common.sh"

container=$(remote "$VPS_APP_DIR" <<'REMOTE'
set -euo pipefail
cd -- "$1"
test -f vps/scripts/peygir-nginx.sh
container=$(bash vps/scripts/peygir-nginx.sh ps -q api)
[[ "$container" =~ ^[a-f0-9]{12,64}$ ]] || { echo 'A single running Peygir API container is required' >&2; exit 1; }
docker exec --user node "$container" test -w /data
docker exec --user node "$container" node --input-type=module -e '
  import {accessSync} from "node:fs";
  accessSync("/app/vps/scripts/install-android-update.mjs");
  const r=await fetch("http://127.0.0.1:3000/health");
  const value=await r.json();
  if(!r.ok || value.service!=="peygir-vps") throw new Error("Peygir API health check failed");
  console.error("Peygir API ready, version "+value.version);
'
printf '%s\n' "$container"
REMOTE
)
[[ "$container" =~ ^[a-f0-9]{12,64}$ ]] || { echo 'Unexpected container response' >&2; exit 1; }
if [[ "$mode" == check ]]; then
  echo 'VPS_CONNECTION_OK: SSH host key, login, installation path, API health and update publisher verified. Nothing was published.'
  exit 0
fi

package_dir=${2:?Expected a package directory}
python3 scripts/ci/android_release.py package --directory "$package_dir"
code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1],encoding="utf-8"))["versionCode"])' "$package_dir/latest.json")
[[ "$code" =~ ^[1-9][0-9]*$ ]] || exit 1
update_dir=$(remote "$container" <<'REMOTE'
set -euo pipefail
docker exec --user node "$1" mktemp -d /tmp/peygir-update.XXXXXX
REMOTE
)
[[ "$update_dir" =~ ^/tmp/peygir-update\.[a-zA-Z0-9]+$ ]] || { echo 'Unexpected staging path' >&2; exit 1; }
# Trap only removes the two named temporary files and their unique empty directory.
cleanup_update() {
  remote "$container" "$update_dir" "$code" <<'REMOTE' || true
docker exec --user node "$1" rm -f -- "$2/latest.json" "$2/peygir-$3.apk"
docker exec --user node "$1" rmdir -- "$2"
REMOTE
}
trap 'cleanup_update; rm -f -- "$ssh_dir/key" "$ssh_dir/known_hosts"; rmdir -- "$ssh_dir"' EXIT

tar -C "$package_dir" -cf - latest.json "peygir-$code.apk" |
  "${ssh[@]}" "$VPS_USER@$VPS_HOST" \
    "docker exec -i --user node $container tar --no-same-owner -xf - -C $update_dir"

remote "$container" "$update_dir" "$code" <<'REMOTE'
set -euo pipefail
# Refuse to move latest.json backwards if a newer release is already installed.
docker exec --user node "$1" node --input-type=module -e '
  import {readFileSync} from "node:fs";
  let current;
  try {current=JSON.parse(readFileSync("/data/updates/android/latest.json","utf8"));}
  catch(e) {if(e.code!=="ENOENT") throw e;}
  if(current && (!Number.isInteger(current.versionCode) || current.versionCode>Number(process.argv[1])))
    throw new Error("Refusing to replace a newer release or an invalid existing manifest");
' "$3"
docker exec --user node "$1" node /app/vps/scripts/install-android-update.mjs --source "$2" --data-dir /data
REMOTE

python3 scripts/ci/verify_public_update.py --directory "$package_dir" --origin "${UPDATE_ORIGIN:-https://peygir.hydrocode.ir}"
echo 'ANDROID_UPDATE_PUBLISHED: the verified update is available from Peygir settings.'
