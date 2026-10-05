#!/usr/bin/env bash
set -euo pipefail

# Run only while Minecraft is stopped; refusing a live backup avoids archives
# with mismatched region files.
if [[ ${EUID} -ne 0 ]]; then
  echo 'Run as root.' >&2
  exit 1
fi
if systemctl is-active --quiet lostglade.service; then
  echo 'Refusing to back up while lostglade.service is running.' >&2
  exit 1
fi

server_root='/srv/lostglade'
backup_dir="${server_root}/backups"
tz='Europe/Moscow'
install -d -o root -g root -m 0700 "${backup_dir}"

backup_date="$(TZ="${tz}" date +%F-%H-%M-%S)"
archive_name="cold-${backup_date}-$$.tar.gz"
archive_path="${backup_dir}/${archive_name}"
temporary_path="${backup_dir}/.${archive_name}.$$.partial"
cleanup() { rm -f -- "${temporary_path}"; }
trap cleanup EXIT

paths=(
  data/world data/config data/polymer data/server.properties data/whitelist.json
  data/usercache.json data/banned-ips.json data/banned-players.json data/ops.json
  server-secrets
)
existing_paths=()
for path in "${paths[@]}"; do
  [[ -e "${server_root}/${path}" ]] && existing_paths+=("${path}")
done
(( ${#existing_paths[@]} > 0 )) || { echo 'Nothing to back up.' >&2; exit 1; }

# A partial archive is never visible as a restore point. gzip is available on
# the VPS without an additional runtime package.
nice -n 10 tar -C "${server_root}" -czf "${temporary_path}" "${existing_paths[@]}"
tar -tzf "${temporary_path}" >/dev/null
mv -f -- "${temporary_path}" "${archive_path}"
sha256sum "${archive_path}" > "${archive_path}.sha256"
chmod 0600 "${archive_path}" "${archive_path}.sha256"

# Manual cold archives, including archives from the retired timer, expire after
# one week. Remove each checksum with its archive.
while IFS= read -r -d '' old_archive; do
  rm -f -- "${old_archive}" "${old_archive}.sha256"
done < <(find "${backup_dir}" -maxdepth 1 -type f \
  \( -name 'cold-????-??-??-??-??-??-*.tar.gz' -o -name 'daily-????-??-??.tar.gz' \) \
  -mmin +10080 -print0)

printf 'Created backup %s\n' "${archive_path}"
