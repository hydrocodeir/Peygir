#!/usr/bin/env bash
# Simulate Docker failures against isolated synthetic installation paths. Never touches a real daemon.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/peygir-server-smoke.XXXXXX")
trap '[[ "$test_dir" == */peygir-server-smoke.* ]] && rm -rf -- "$test_dir"' EXIT
mkdir "$test_dir/bin"
cat > "$test_dir/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$SMOKE_LOG"
case "$1" in
  compose)
    if [[ " $* " == *' ps -q api '* ]]; then printf '%064d\n' 1
    elif [[ " $* " == *' version --short '* ]]; then echo 2.39.0
    elif [[ " $* " == *' config --format json '* ]]; then
      name=peygir_data; [[ "$SMOKE_MODE" != volume-failure ]] || name=wrong_volume
      printf '{"services":{"api":{"volumes":[{"target":"/data","source":"data","type":"volume"},{"target":"/backups","source":"backups","type":"volume"}]}},"volumes":{"data":{"name":"%s"},"backups":{"name":"peygir_backups"}}}\n' "$name"
    elif [[ " $* " == *' up '* ]]; then
      if [[ "$SMOKE_MODE" == start-failure ]] && grep -q 'ci-' "$SMOKE_APP/.device-private/vps/deployed-api.yaml"; then exit 1; fi
    else echo "Unexpected Compose command: $*" >&2; exit 1; fi ;;
  inspect)
    if [[ "$*" == *'.Image'* ]]; then printf 'sha256:%064d\n' 2
    elif [[ "$*" == *'.Config.Labels'* ]]; then echo peygir
    else printf '[{"Mounts":[{"Destination":"/data","Type":"volume","RW":true,"Name":"peygir_data"},{"Destination":"/backups","Type":"volume","RW":true,"Name":"peygir_backups"}]}]\n'; fi ;;
  image)
    if [[ "$SMOKE_MODE" == unchanged ]]; then cat "$SMOKE_APP/digest"; else echo '<no value>'; fi ;;
  exec)
    if [[ "$*" == *'fetch('* ]]; then
      last=${!#}; fallback=4.10.0
      [[ "$SMOKE_MODE" != unchanged ]] || fallback=4.11.0
      printf '%s\n' "${last:-$fallback}"
    fi ;;
  run)
    if [[ "$*" == *'/backup.mjs'* ]]; then
      [[ "$SMOKE_MODE" != backup-failure ]] || exit 1
      echo /backups/synthetic-backup
    elif [[ "$*" == *'/verify-upgrade.mjs'* ]]; then
      [[ "$SMOKE_MODE" != migration-failure ]] || exit 1
    fi ;;
  build) [[ "$SMOKE_MODE" != build-failure ]] || exit 1 ;;
  tag|stop) : ;;
  *) echo "Unexpected Docker command: $*" >&2; exit 1 ;;
esac
DOCKER
cat > "$test_dir/bin/curl" <<'CURL'
#!/usr/bin/env bash
[[ "$SMOKE_MODE" != public-failure ]] || exit 1
printf '{"service":"peygir-vps","version":"4.11.0"}\n'
CURL
chmod +x "$test_dir/bin/docker" "$test_dir/bin/curl"
export PATH="$test_dir/bin:$PATH"
export SMOKE_MODE SMOKE_LOG SMOKE_APP
for mode in check success unchanged build-failure volume-failure backup-failure migration-failure start-failure public-failure; do
  SMOKE_MODE=$mode
  SMOKE_APP="$test_dir/$mode"
  SMOKE_LOG="$test_dir/$mode.log"
  mkdir -p "$SMOKE_APP/.device-private/vps" "$SMOKE_APP/vps/scripts"
  printf 'PRIVATE SETTINGS MUST BE PRESERVED\n' > "$SMOKE_APP/.device-private/vps/compose.env"
  printf 'original compose\n' > "$SMOKE_APP/vps/compose.nginx.yaml"
  cp "$root/vps/scripts/peygir-nginx.sh" "$SMOKE_APP/vps/scripts/peygir-nginx.sh"
  stage=$(mktemp -d "$SMOKE_APP/.device-private/vps/server-release.XXXXXX")
  mkdir -p "$stage/vps/scripts"
  cp "$root/vps/scripts/peygir-nginx.sh" "$stage/vps/scripts/peygir-nginx.sh"
  printf 'FROM node:24\n' > "$stage/vps/Dockerfile"
  python3 - "$stage" "$SMOKE_APP" <<'PY'
import hashlib,json,sys
from pathlib import Path
stage,app=map(Path,sys.argv[1:])
files={name:hashlib.sha256((stage/name).read_bytes()).hexdigest() for name in ['vps/Dockerfile','vps/scripts/peygir-nginx.sh']}
digest=hashlib.sha256(json.dumps(files,sort_keys=True,separators=(',',':')).encode()).hexdigest()
(stage/'deployment.json').write_text(json.dumps(dict(format=1,version='4.11.0',commit='a'*40,files=files,digest=digest)))
(app/'digest').write_text(digest+'\n')
PY
  status=0
  if [[ "$mode" == check ]]; then
    bash "$root/scripts/ci/remote_server_deploy.sh" check "$SMOKE_APP" > "$test_dir/$mode.out" 2>&1 || status=$?
  else
    bash "$root/scripts/ci/remote_server_deploy.sh" publish "$SMOKE_APP" "$stage" 4.11.0 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa https://peygir.test > "$test_dir/$mode.out" 2>&1 || status=$?
  fi
  case "$mode" in
    check)
      [[ "$status" == 0 ]] && grep -q SERVER_CONNECTION_OK "$test_dir/$mode.out"
      ! grep -qE '^(build|stop|run|tag) ' "$SMOKE_LOG" ;;
    success)
      if [[ "$status" != 0 ]]; then cat "$test_dir/$mode.out"; exit 1; fi
      grep -q SERVER_DEPLOYED "$test_dir/$mode.out"
      grep -q 'build: !reset null' "$SMOKE_APP/.device-private/vps/deployed-api.yaml"
      build_line=$(grep -n '^build ' "$SMOKE_LOG" | head -1 | cut -d: -f1)
      stop_line=$(grep -n '^stop ' "$SMOKE_LOG" | head -1 | cut -d: -f1)
      ((build_line<stop_line))
      grep -q '/verify-upgrade.mjs' "$SMOKE_LOG" ;;
    unchanged)
      [[ "$status" == 0 ]]
      grep -q SERVER_ALREADY_CURRENT "$test_dir/$mode.out"
      ! grep -qE '^(build|stop|run|tag) ' "$SMOKE_LOG" ;;
    build-failure|volume-failure)
      [[ "$status" != 0 ]]
      ! grep -q '^stop ' "$SMOKE_LOG" ;;
    *)
      [[ "$status" != 0 ]]
      grep -q SERVER_ROLLBACK_STARTED "$test_dir/$mode.out"
      grep -q 'before-' "$SMOKE_APP/.device-private/vps/deployed-api.yaml" ;;
  esac
  grep -qx 'PRIVATE SETTINGS MUST BE PRESERVED' "$SMOKE_APP/.device-private/vps/compose.env"
  grep -qx 'original compose' "$SMOKE_APP/vps/compose.nginx.yaml"
done
echo 'Server deploy smoke passed: connection, volume guard, build-before-stop, backup, migration, restart, public health and rollback failures'
