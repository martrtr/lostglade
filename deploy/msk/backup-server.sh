#!/usr/bin/env bash
set -euo pipefail

# The maintenance service stops Minecraft first; refusing a live backup avoids
# archives with mismatched region files.
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

backup_date="$(TZ="${tz}" date +%F)"
archive_name="daily-${backup_date}.tar.gz"
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

# Retain 14 daily points, then one from each earlier ISO week (8) and month
# (12). Archive and checksum are always removed together.
mapfile -t archives < <(find "${backup_dir}" -maxdepth 1 -type f -name 'daily-????-??-??.tar.gz' -printf '%f\n' | LC_ALL=C sort -r)
declare -A keep=() seen_weeks=() seen_months=()
daily_count=0
weekly_count=0
monthly_count=0
for name in "${archives[@]}"; do
  date_part="${name#daily-}"
  date_part="${date_part%.tar.gz}"
  if (( daily_count < 14 )); then
    keep["${name}"]=1
    ((daily_count += 1))
  fi
  week="$(TZ="${tz}" date -d "${date_part}" +%G-W%V)"
  if [[ -z "${seen_weeks[${week}]:-}" && ${weekly_count} -lt 8 ]]; then
    keep["${name}"]=1
    seen_weeks["${week}"]=1
    ((weekly_count += 1))
  fi
  month="${date_part:0:7}"
  if [[ -z "${seen_months[${month}]:-}" && ${monthly_count} -lt 12 ]]; then
    keep["${name}"]=1
    seen_months["${month}"]=1
    ((monthly_count += 1))
  fi
done
for name in "${archives[@]}"; do
  [[ -n "${keep[${name}]:-}" ]] && continue
  rm -f -- "${backup_dir}/${name}" "${backup_dir}/${name}.sha256"
done

printf 'Created backup %s\n' "${archive_path}"
