#!/usr/bin/env bash
set -euo pipefail

# This file is installed on the VPS as /usr/local/sbin/lostglade-activate-release.
# It accepts only a Git commit SHA and only an archive in the application's own
# incoming directory; it never evaluates user-supplied paths.
release_id="${1:?Usage: lostglade-activate-release <full-git-sha>}"
if [[ ! "${release_id}" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Release id must be a full lowercase Git commit SHA.' >&2
  exit 2
fi

server_root='/srv/lostglade'
archive_path="${server_root}/incoming/${release_id}.tar.gz"
release_dir="${server_root}/releases/${release_id}"
temporary_dir="${server_root}/releases/.${release_id}.tmp"
data_dir="${server_root}/data"

# Do not switch releases while nightly maintenance owns the server lifecycle.
exec 9>/run/lock/lostglade-operations.lock
flock -n 9 || { echo 'Lostglade maintenance is in progress; retry deployment later.' >&2; exit 1; }

[[ -f "${archive_path}" ]] || { echo "Archive is missing: ${archive_path}" >&2; exit 1; }
if [[ -e "${release_dir}" ]]; then
  if [[ -L "${server_root}/current" && "$(readlink -f "${server_root}/current")" == "${release_dir}" ]]; then
    rm -f -- "${archive_path}"
    echo "Release ${release_id} is already active."
    exit 0
  fi
  echo "Release already exists but is not active: ${release_id}" >&2
  exit 1
fi

rm -rf -- "${temporary_dir}"
mkdir -p "${temporary_dir}"
cleanup() {
  rm -rf -- "${temporary_dir}"
}
trap cleanup EXIT

tar -xzf "${archive_path}" -C "${temporary_dir}"
(
  cd "${temporary_dir}"
  sha256sum -c SHA256SUMS
)

[[ "$(<"${temporary_dir}/RELEASE_COMMIT")" == "${release_id}" ]] || {
  echo 'Archive commit does not match requested release.' >&2
  exit 1
}
for required in \
  fabric-server-launch.jar \
  server.jar \
  server.properties.template \
  server-icon.png \
  whitelist.json.template \
  deploy/msk/run-server.sh \
  deploy/msk/backup-server.sh \
  deploy/msk/lostglade-maintenance.sh \
  deploy/msk/run-renderer-bot.sh \
  renderer-bot/launch.cfg \
  mods/lg2-1.0.0.jar \
  libraries/net/fabricmc/fabric-loader/0.18.4/fabric-loader-0.18.4.jar; do
  [[ -f "${temporary_dir}/${required}" ]] || { echo "Release lacks ${required}" >&2; exit 1; }
done
unzip -p "${temporary_dir}/mods/lg2-1.0.0.jar" fabric.mod.json 2>/dev/null \
  | grep -Eq '"id"[[:space:]]*:[[:space:]]*"lg2"' \
  || { echo 'Release has an invalid LG2 jar.' >&2; exit 1; }
mkdir -p \
  "${data_dir}/world" \
  "${data_dir}/logs" \
  "${data_dir}/crash-reports" \
  "${data_dir}/.fabric" \
  "${data_dir}/versions" \
  "${data_dir}/polymer" \
  "${data_dir}/config/tab" \
  "${data_dir}/config"
install -d -o root -g lostglade -m 0750 "${server_root}/server-secrets"

# The committed template is authoritative for each release. Vanilla owns the
# generated timestamp in the runtime copy, so it belongs in data, not release.
install -m 0644 "${temporary_dir}/server.properties.template" "${data_dir}/server.properties"
install -m 0644 "${temporary_dir}/whitelist.json.template" "${data_dir}/whitelist.json"
rm -f -- "${temporary_dir}/whitelist.json.template"

# Authentication is stateful and stays private on the VPS. Other LG2 configs
# are public release configuration and are replaced atomically with each release.
source_path="${temporary_dir}/config/lg2-auth.json"
persistent_path="${data_dir}/config/lg2-auth.json"
[[ -f "${source_path}" ]] || { echo 'Release lacks LG2 auth config' >&2; exit 1; }
if [[ ! -e "${persistent_path}" ]]; then
  install -m 0640 "${source_path}" "${persistent_path}"
fi
rm -f -- "${source_path}"
ln -s "${persistent_path}" "${temporary_dir}/config/lg2-auth.json"

# Vanilla, Fabric, Polymer and TAB create these at runtime. Keep them out of
# immutable releases alongside world/player state, so a release directory is
# exactly the verified archive plus symlinks to persistent state.
for item in \
  world \
  logs \
  crash-reports \
  .fabric \
  versions \
  polymer \
  server.properties \
  usercache.json \
  whitelist.json \
  banned-ips.json \
  banned-players.json \
  ops.json \
  config/tab/playerdata.yml \
  config/tab/skincache.yml \
  config/tab/users.yml; do
  ln -s "${data_dir}/${item}" "${temporary_dir}/${item}"
done
ln -s "${server_root}/server-secrets" "${temporary_dir}/server-secrets"

chown -R lostglade:lostglade "${temporary_dir}" "${data_dir}"
mv -- "${temporary_dir}" "${release_dir}"
trap - EXIT

previous_release=''
if [[ -L "${server_root}/current" ]]; then
  previous_release="$(readlink -f "${server_root}/current")"
fi
ln -s "${release_dir}" "${server_root}/current.next"
mv -Tf "${server_root}/current.next" "${server_root}/current"

started_at="$(date --iso-8601=seconds)"
systemctl restart lostglade.service
ready=0
for _ in $(seq 1 45); do
  if journalctl -u lostglade.service --since "${started_at}" --no-pager -o cat | grep -Fq 'Done ('; then
    ready=1
    break
  fi
  if ! systemctl is-active --quiet lostglade.service; then
    break
  fi
  sleep 2
done

if [[ "${ready}" -ne 1 ]]; then
  echo 'New release did not become ready; restoring the previous release.' >&2
  if [[ -n "${previous_release}" && -d "${previous_release}" ]]; then
    ln -s "${previous_release}" "${server_root}/current.rollback"
    mv -Tf "${server_root}/current.rollback" "${server_root}/current"
    systemctl restart lostglade.service || true
  else
    systemctl stop lostglade.service || true
  fi
  journalctl -u lostglade.service --since "${started_at}" --no-pager -n 120 >&2 || true
  exit 1
fi

install -m 0755 "${release_dir}/deploy/msk/backup-server.sh" /usr/local/sbin/lostglade-backup
install -m 0755 "${release_dir}/deploy/msk/lostglade-maintenance.sh" /usr/local/sbin/lostglade-maintenance
install -m 0644 "${release_dir}/deploy/msk/lostglade-renderer-bot.service" /etc/systemd/system/lostglade-renderer-bot.service
install -m 0644 "${release_dir}/deploy/msk/lostglade-maintenance.service" /etc/systemd/system/lostglade-maintenance.service
install -m 0644 "${release_dir}/deploy/msk/lostglade-maintenance.timer" /etc/systemd/system/lostglade-maintenance.timer
systemctl daemon-reload
systemctl enable lostglade-renderer-bot.service
systemctl enable --now lostglade-maintenance.timer
systemctl restart lostglade-renderer-bot.service

rm -f -- "${archive_path}"

# Keep the active release and two rollback releases. Removal is restricted to
# verified SHA-named directories belonging to this service.
mapfile -t old_releases < <(find "${server_root}/releases" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended -regex '.*/[0-9a-f]{40}' -printf '%T@ %p\n' | sort -nr | awk 'NR > 3 {print $2}')
for old_release in "${old_releases[@]}"; do
  [[ "${old_release}" == "${release_dir}" ]] || rm -rf -- "${old_release}"
done

echo "Release ${release_id} is active."
