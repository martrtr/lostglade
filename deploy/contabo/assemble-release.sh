#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
release_dir="${repo_dir}/dist/lostglade-contabo-vps8"
archive_path="${repo_dir}/dist/lostglade-contabo-vps8.tar.zst"

if [[ "${LG2_SKIP_BUILD:-0}" != "1" ]]; then
  cd "${repo_dir}/mods/lg2-0.1.0"
  ./gradlew --no-daemon --console=plain build remapGameplayClientJar
  cd "${repo_dir}"
fi

rm -rf "${release_dir}"
mkdir -p "${release_dir}"

rsync -a \
  --exclude='.git/' \
  --exclude='.venv/' \
  --exclude='.codex_packet_src/' \
  --exclude='.codex_polymer_src/' \
  --exclude='.fabric/' \
  --exclude='.gradle/' \
  --exclude='.gradle-vm/' \
  --exclude='dist/' \
  --exclude='libraries/' \
  --exclude='versions/' \
  --exclude='logs/' \
  --exclude='crash-reports/' \
  --exclude='cache/' \
  --exclude='bluemap/' \
  --exclude='debug/' \
  --exclude='server-secrets/' \
  --exclude='usercache.json' \
  --exclude='banned-ips.json' \
  --exclude='mods/lg2-0.1.0/.gradle/' \
  --exclude='mods/lg2-0.1.0/build/' \
  --exclude='mods/lg2-0.1.0/home/' \
  --exclude='mods/lg2-0.1.0/logs/' \
  --exclude='run-renderer-bot/.fabric/' \
  --exclude='run-renderer-bot/logs/' \
  --exclude='run-renderer-bot/debug/' \
  --exclude='run-renderer-bot/crash-reports/' \
  --exclude='run-renderer-bot/downloads/' \
  --exclude='run-renderer-bot/options.txt' \
  --exclude='run-renderer-bot/servers.dat*' \
  --exclude='run-renderer-bot/polymer/resource_pack.zip' \
  --exclude='tmp*' \
  --exclude='.tmp*' \
  ./ "${release_dir}/"

mkdir -p "${release_dir}/mods/lg2-0.1.0/build/libs"
cp mods/lg2-0.1.0/build/libs/lg2-1.0.0.jar "${release_dir}/mods/lg2-0.1.0/build/libs/"
cp mods/lg2-0.1.0/build/libs/lg2-client-1.0.0.jar "${release_dir}/mods/lg2-0.1.0/build/libs/"

rm -f "${archive_path}"
tar --zstd -cpf "${archive_path}" -C "${repo_dir}/dist" "lostglade-contabo-vps8"
printf 'Created %s\n' "${archive_path}"
