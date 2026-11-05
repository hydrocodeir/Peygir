#!/usr/bin/env bash
# Exercise connection-only mode with generated disposable keys and a local SSH stub.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/peygir-deploy-test.XXXXXX")
trap '[[ "$test_dir" == */peygir-deploy-test.* ]] && rm -rf -- "$test_dir"' EXIT
mkdir "$test_dir/bin"
ssh-keygen -q -t ed25519 -N '' -f "$test_dir/test-key"
cat > "$test_dir/bin/ssh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >> "$SMOKE_LOG.args"
cat > "$SMOKE_LOG.script"
if [[ "${SMOKE_FAIL:-0}" == 1 ]]; then
  echo 'Synthetic SSH failure' >&2
  exit 255
fi
printf '%064d\n' 1
STUB
chmod +x "$test_dir/bin/ssh"
export PATH="$test_dir/bin:$PATH" SMOKE_LOG="$test_dir/log"
export VPS_HOST=192.0.2.1 VPS_USER=root VPS_PORT=2222 VPS_APP_DIR=/root/peygir/Peygir-VPS
export VPS_SSH_KEY VPS_HOST_KEY RUNNER_TEMP="$test_dir"
VPS_SSH_KEY=$(cat "$test_dir/test-key")
VPS_HOST_KEY=$(cat "$test_dir/test-key.pub")
result=$(bash "$root/scripts/ci/deploy_android_update.sh" check)
[[ "$result" == *VPS_CONNECTION_OK* ]]
grep -qx 'StrictHostKeyChecking=yes' "$SMOKE_LOG.args"
grep -qx 'HostKeyAlias=peygir-vps' "$SMOKE_LOG.args"
grep -qx 'BatchMode=yes' "$SMOKE_LOG.args"
grep -q 'accessSync' "$SMOKE_LOG.script"
if grep -q -- '--source' "$SMOKE_LOG.script"; then echo 'Connection check attempted publication' >&2; exit 1; fi
if compgen -G "$test_dir/peygir-ci-ssh.*" >/dev/null; then echo 'Temporary SSH key was not removed' >&2; exit 1; fi
export SMOKE_FAIL=1
if bash "$root/scripts/ci/deploy_android_update.sh" check > /dev/null 2>&1; then echo 'SSH failure was ignored' >&2; exit 1; fi
if compgen -G "$test_dir/peygir-ci-ssh.*" >/dev/null; then echo 'Temporary key survived failed SSH' >&2; exit 1; fi
unset SMOKE_FAIL
export VPS_HOST='https://invalid.test'
if bash "$root/scripts/ci/deploy_android_update.sh" check > /dev/null 2>&1; then echo 'Invalid VPS host was accepted' >&2; exit 1; fi
echo 'Deploy smoke passed: pinned-host SSH, connection-only mode, failure propagation and key cleanup'
