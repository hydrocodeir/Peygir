#!/usr/bin/env bash
# Shared pinned-host SSH connection. Source from a deployment script.
for name in VPS_HOST VPS_PORT VPS_USER VPS_SSH_KEY VPS_HOST_KEY; do
  [[ -n "${!name:-}" ]] || { echo "Missing GitHub secret: $name" >&2; exit 1; }
done
VPS_APP_DIR=${VPS_APP_DIR:-/root/peygir/Peygir-VPS}
[[ "$VPS_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.:-]*$ ]] || { echo 'VPS_HOST must be a hostname or IP, without scheme or username' >&2; exit 1; }
[[ "$VPS_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo 'Invalid VPS_USER' >&2; exit 1; }
[[ "$VPS_PORT" =~ ^[0-9]{1,5}$ ]] && ((10#$VPS_PORT >= 1 && 10#$VPS_PORT <= 65535)) || { echo 'Invalid VPS_PORT' >&2; exit 1; }
[[ "$VPS_APP_DIR" =~ ^/[a-zA-Z0-9_./-]+$ && "$VPS_APP_DIR" != *..* ]] || { echo 'VPS_APP_DIR must be an absolute installation path without spaces or ..' >&2; exit 1; }

ssh_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/peygir-ci-ssh.XXXXXX")
trap 'rm -f -- "$ssh_dir/key" "$ssh_dir/known_hosts"; rmdir -- "$ssh_dir"' EXIT
chmod 700 "$ssh_dir"
printf '%s\n' "$VPS_SSH_KEY" | tr -d '\r' > "$ssh_dir/key"
chmod 600 "$ssh_dir/key"
read -r key_type key_body _ <<< "$VPS_HOST_KEY"
[[ "$key_type" == ssh-ed25519 && "$key_body" =~ ^[a-zA-Z0-9+/=]+$ ]] || { echo 'VPS_HOST_KEY must contain the full ssh-ed25519 public host-key line' >&2; exit 1; }
printf 'peygir-vps %s %s\n' "$key_type" "$key_body" > "$ssh_dir/known_hosts"
ssh-keygen -y -P '' -f "$ssh_dir/key" >/dev/null
ssh-keygen -lf "$ssh_dir/known_hosts" >/dev/null
ssh=(ssh -T -i "$ssh_dir/key" -p "$VPS_PORT"
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
  -o HostKeyAlias=peygir-vps -o UpdateHostKeys=no
  -o "UserKnownHostsFile=$ssh_dir/known_hosts" -o GlobalKnownHostsFile=/dev/null
  -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
remote() {
  local quoted
  printf -v quoted '%q ' "$@"
  "${ssh[@]}" "$VPS_USER@$VPS_HOST" "bash -se -- $quoted"
}

