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
apt-get install -y --no-install-recommends openjdk-21-jre-headless ufw unzip yt-dlp ffmpeg xvfb libgl1-mesa-dri libglx-mesa0 libegl-mesa0

id -u lostglade >/dev/null 2>&1 || useradd --system --home-dir "${server_root}" --create-home --shell /usr/sbin/nologin lostglade
install -d -o root -g root -m 0755 "${server_root}" "${server_root}/incoming" "${server_root}/releases"
install -d -o lostglade -g lostglade -m 0750 "${server_root}/data"
install -d -o root -g lostglade -m 0750 "${server_root}/server-secrets" "${server_root}/server-secrets/yt-dlp"
install -d -o lostglade -g lostglade -m 0750 "${server_root}/.config/yt-dlp"
printf '%s\n' '--cookies /srv/lostglade/server-secrets/yt-dlp/youtube.cookies.txt' > "${server_root}/.config/yt-dlp/config"
chown lostglade:lostglade "${server_root}/.config/yt-dlp/config"
chmod 0640 "${server_root}/.config/yt-dlp/config"
install -m 0755 "${bootstrap_dir}/deploy/msk/activate-release.sh" /usr/local/sbin/lostglade-activate-release
install -m 0644 "${bootstrap_dir}/deploy/msk/lostglade.service" /etc/systemd/system/lostglade.service
systemctl daemon-reload
systemctl enable lostglade.service

# The renderer is local-only. Only SSH, Minecraft, voice and webcam are
# exposed; the later UFW enable is intentionally after the SSH allow rule.
ufw allow OpenSSH
ufw allow 25565/tcp comment 'Lostglade Minecraft'
ufw allow 24454/udp comment 'Lostglade voice chat'
ufw allow 25454/udp comment 'Lostglade Webcam'
ufw --force enable

echo 'VPS provisioning complete. Upload a release to /srv/lostglade/incoming and activate it.'
