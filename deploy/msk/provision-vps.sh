#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo 'Run as root.' >&2
  exit 1
fi

server_root='/srv/lostglade'
bootstrap_dir="${1:?Usage: provision-vps.sh <extracted-release-directory>}"

[[ -x "${bootstrap_dir}/deploy/msk/activate-release.sh" ]] || {
  echo 'Expected an extracted Lostglade release directory.' >&2
  exit 1
}

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends openjdk-21-jre-headless ufw unzip

id -u lostglade >/dev/null 2>&1 || useradd --system --home-dir "${server_root}" --create-home --shell /usr/sbin/nologin lostglade
install -d -o root -g root -m 0755 "${server_root}" "${server_root}/incoming" "${server_root}/releases"
install -d -o lostglade -g lostglade -m 0750 "${server_root}/data"
install -m 0755 "${bootstrap_dir}/deploy/msk/activate-release.sh" /usr/local/sbin/lostglade-activate-release
install -m 0644 "${bootstrap_dir}/deploy/msk/lostglade.service" /etc/systemd/system/lostglade.service
systemctl daemon-reload
systemctl enable lostglade.service

# The server has no web service. Only SSH, Minecraft and Simple Voice Chat are
# exposed; the later UFW enable is intentionally after the SSH allow rule.
ufw allow OpenSSH
ufw allow 25565/tcp comment 'Lostglade Minecraft'
ufw allow 24454/udp comment 'Lostglade voice chat'
ufw --force enable

echo 'VPS provisioning complete. Upload a release to /srv/lostglade/incoming and activate it.'
