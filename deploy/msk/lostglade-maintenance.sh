#!/usr/bin/env bash
set -euo pipefail

# Serialize the nightly window with release activation.
exec 9>/run/lock/lostglade-operations.lock
flock -n 9 || { echo 'Another Lostglade operation is in progress.' >&2; exit 1; }

server_was_running=0
if systemctl is-active --quiet lostglade.service; then
  server_was_running=1
fi

wait_ready() {
  local started_at="$1"
  for _ in $(seq 1 60); do
    if journalctl -u lostglade.service --since "${started_at}" --no-pager -o cat | grep -Fq 'Done ('; then
      return 0
    fi
    if ! systemctl is-active --quiet lostglade.service; then
      return 1
    fi
    sleep 2
  done
  return 1
}

restart_server() {
  [[ "${server_was_running}" -eq 1 ]] || return 0
  local started_at
  started_at="$(date --iso-8601=seconds)"
  systemctl start lostglade.service
  wait_ready "${started_at}"
  systemctl restart lostglade-renderer-bot.service
}

if [[ "${server_was_running}" -eq 1 ]]; then
  systemctl stop lostglade-renderer-bot.service || true
  systemctl stop lostglade.service
fi

# A backup failure must not leave a healthy server down. The original status is
# preserved for systemd and the journal.
restarted=0
restore_on_exit() {
  local status=$?
  if [[ "${server_was_running}" -eq 1 && "${restarted}" -eq 0 ]]; then
    restart_server || true
  fi
  exit "${status}"
}
trap restore_on_exit EXIT

/usr/local/sbin/lostglade-backup
restart_server
restarted=1
trap - EXIT
