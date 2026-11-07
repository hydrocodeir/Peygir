#!/usr/bin/env bash
# Runs on the existing VPS. The application Compose files, secrets and volumes are retained.
set -euo pipefail
umask 077
mode=${1:?check or publish}; app=${2:?installation directory}
[[ "$mode" == check || "$mode" == publish ]] || exit 1
[[ "$app" =~ ^/[a-zA-Z0-9_./-]+$ && "$app" != *..* ]] || exit 1
cd -- "$app"
test -f .device-private/vps/compose.env
test -f vps/compose.nginx.yaml
for command in docker python3 flock curl; do command -v "$command" >/dev/null; done
exec 9> .device-private/vps/server-deploy.lock
flock -n 9 || { echo 'Another server deployment is in progress' >&2; exit 1; }
container=$(bash vps/scripts/peygir-nginx.sh ps -q api)
[[ "$container" =~ ^[a-f0-9]{12,64}$ ]] || { echo 'One running API container is required' >&2; exit 1; }
project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container")
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || exit 1
old_image=$(docker inspect --format '{{.Image}}' "$container")
[[ "$old_image" =~ ^sha256:[a-f0-9]{64}$ ]] || exit 1
health() {
  docker exec --user node "$1" node --input-type=module -e '
    const r=await fetch("http://127.0.0.1:3000/health",{signal:AbortSignal.timeout(10000)});
    const p=await r.json();
    if(!r.ok || p.service!=="peygir-vps" || !/^\d+\.\d+\.\d+$/.test(p.version)) throw new Error("API health check failed");
    if(process.argv[1] && p.version!==process.argv[1]) throw new Error("Unexpected API version");
    console.log(p.version);
  ' "${2:-}"
}
old_version=$(health "$container")
docker exec --user node "$container" test -w /data
docker exec --user node "$container" test -w /backups
docker exec --user node "$container" test -f /app/vps/scripts/backup.mjs
compose=(docker compose --project-directory "$app/vps" --env-file "$app/.device-private/vps/compose.env" -p "$project" -f "$app/vps/compose.nginx.yaml")
if [[ "$mode" == check ]]; then
  "${compose[@]}" version --short
  echo "SERVER_CONNECTION_OK: existing API $old_version, Docker, backup tool and private installation are ready. No upgrade performed."
  exit 0
fi

stage=${3:?release staging directory}; version=${4:?server version}; commit=${5:?Git SHA}; origin=${6:?public origin}
[[ "$stage" == "$app/.device-private/vps/server-release."* && "${stage##*/}" =~ ^server-release\.[a-zA-Z0-9]+$ ]] || exit 1
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$commit" =~ ^[a-f0-9]{40}$ ]] || exit 1
python3 - "$stage" "$version" "$commit" "$old_version" "$origin" <<'PY'
import hashlib,json,sys
from pathlib import Path,PurePosixPath
from urllib.parse import urlsplit
stage=Path(sys.argv[1]); manifest=json.loads((stage/'deployment.json').read_text())
assert manifest['format']==1 and manifest['version']==sys.argv[2] and manifest['commit']==sys.argv[3]
assert tuple(map(int,sys.argv[2].split('.')))>=tuple(map(int,sys.argv[4].split('.'))), 'Refusing to downgrade the API'
origin=urlsplit(sys.argv[5])
assert origin.scheme=='https' and origin.hostname and not origin.username and not origin.password and not origin.query and not origin.fragment and origin.path in ('','/'), 'Invalid public origin'
for name,expected in manifest['files'].items():
    rel=PurePosixPath(name)
    assert not rel.is_absolute() and '..' not in rel.parts
    file=stage/name
    assert file.is_file() and not file.is_symlink()
    assert hashlib.sha256(file.read_bytes()).hexdigest()==expected, 'Release input checksum mismatch: '+name
assert hashlib.sha256(json.dumps(manifest['files'],sort_keys=True,separators=(',',':')).encode()).hexdigest()==manifest['digest']
PY
digest=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["digest"])' "$stage/deployment.json")
current_digest=$(docker image inspect --format '{{index .Config.Labels "ir.peygir.server-digest"}}' "$old_image")
if [[ "$current_digest" == "$digest" && "$old_version" == "$version" ]]; then
  echo "SERVER_ALREADY_CURRENT: Peygir $version has the same server sources; API was not restarted."
  exit 0
fi
image="$project-api:ci-$commit"
rollback_image="$project-api:before-${commit:0:12}-$(date +%s)"
docker tag "$old_image" "$rollback_image"
managed="$app/.device-private/vps/deployed-api.yaml"
candidate="$stage/candidate.yaml"
write_override() {
  printf 'name: %s\nservices:\n  api:\n    image: %s\n    pull_policy: never\n    build: !reset null\n' "$project" "$1" > "$2"
}
write_override "$image" "$candidate"
# Validate !reset support and compare the exact existing data/backup volume names before stopping anything.
"${compose[@]}" -f "$candidate" config --format json > "$stage/compose.json"
docker inspect "$container" > "$stage/container.json"
python3 - "$stage" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1]); config=json.loads((root/'compose.json').read_text()); current=json.loads((root/'container.json').read_text())[0]
assert not config['services']['api'].get('build'), 'Compose must support !reset null'
for target in ['/data','/backups']:
    actual=next(m for m in current['Mounts'] if m['Destination']==target)
    wanted=next(m for m in config['services']['api']['volumes'] if m['target']==target)
    assert actual['Type']=='volume' and wanted['type']=='volume' and actual['RW']
    assert config['volumes'][wanted['source']]['name']==actual['Name'], 'Existing volume would change: '+target
PY
# Build while the old API is still available. No private source files enter this build context.
docker build --label "org.opencontainers.image.revision=$commit" --label "ir.peygir.version=$version" --label "ir.peygir.server-digest=$digest" -t "$image" -f "$stage/vps/Dockerfile" "$stage"
docker run --rm --network none --entrypoint node "$image" -e 'if(require("./vps/package.json").version!==process.argv[1])process.exit(1)' "$version"

stopped=0; success=0; backup=''
finish() {
  local code=$?
  trap - EXIT INT TERM HUP
  if [[ "$stopped" == 1 && "$success" == 0 ]]; then
    echo 'Deployment failed. Attempting to restart the previous API image without restoring or deleting data.' >&2
    write_override "$rollback_image" "$managed.tmp"
    mv -- "$managed.tmp" "$managed"
    # This helper must understand the managed image override on the first deployment too.
    install -m 755 "$stage/vps/scripts/peygir-nginx.sh" "$app/vps/scripts/peygir-nginx.sh"
    if "${compose[@]}" -f "$managed" up -d --no-deps --no-build --wait --wait-timeout 120 api &&
      health "$("${compose[@]}" -f "$managed" ps -q api)" "$old_version" >/dev/null; then
      echo "SERVER_ROLLBACK_STARTED: previous image restored and API health verified. Database backup: ${backup:-not completed}" >&2
    else
      echo "SERVER_ROLLBACK_FAILED: inspect the API using the existing Compose helper. Backup: ${backup:-not completed}" >&2
    fi
  fi
  exit "$code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
stopped=1
docker stop --time 90 "$container" >/dev/null
backup=$(docker run --rm --network none --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  --user node --volumes-from "$container" --entrypoint node "$old_image" /app/vps/scripts/backup.mjs)
[[ "$backup" == /backups/* ]] || { echo 'The existing backup tool did not return a backup directory' >&2; exit 1; }
echo "SERVER_BACKUP_READY: $backup"
docker run --rm --network none --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  --user node --volumes-from "$container" --entrypoint node "$image" /app/vps/scripts/verify-upgrade.mjs
install -m 755 "$stage/vps/scripts/peygir-nginx.sh" "$app/vps/scripts/peygir-nginx.sh"
write_override "$image" "$managed.tmp"
mv -- "$managed.tmp" "$managed"
"${compose[@]}" -f "$managed" up -d --no-deps --no-build --wait --wait-timeout 120 api
new_container=$("${compose[@]}" -f "$managed" ps -q api)
health "$new_container" "$version" >/dev/null
curl --fail --silent --show-error --max-time 30 "${origin%/}/health" > "$stage/public-health.json"
python3 - "$stage/public-health.json" "$version" <<'PY'
import json,sys
with open(sys.argv[1]) as f: value=json.load(f)
assert value.get('service')=='peygir-vps' and value.get('version')==sys.argv[2], 'Public API did not switch to the requested version'
PY
python3 - "$stage" "$version" "$commit" "$image" "$rollback_image" "$backup" <<'PY'
import json,sys,datetime
from pathlib import Path
stage,version,commit,image,previous,backup=sys.argv[1:]
value=dict(version=version,commit=commit,image=image,previousImage=previous,backup=backup,created=datetime.datetime.now(datetime.timezone.utc).isoformat())
(Path(stage)/'result.json').write_text(json.dumps(value,indent=2)+'\n')
PY
success=1
echo "SERVER_DEPLOYED: Peygir $version; database backup $backup; previous image $rollback_image"
