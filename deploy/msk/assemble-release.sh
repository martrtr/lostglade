#!/usr/bin/env bash
set -euo pipefail

# Build a deliberately small, server-only release.  Do not turn this into an
# rsync of the repository: the allow-list below is the release contract.
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
release_id="${1:-$(git -C "${repo_dir}" rev-parse HEAD)}"

if [[ ! "${release_id}" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Release id must be a full lowercase Git commit SHA.' >&2
  exit 2
fi

output_dir="${repo_dir}/dist"
stage_dir="${output_dir}/lostglade-${release_id}"
archive_path="${output_dir}/lostglade-${release_id}.tar.gz"

rm -rf -- "${stage_dir}"
mkdir -p "${stage_dir}/mods" "${stage_dir}/config"

copy_file() {
  local relative_path="$1"
  install -D -m 0644 "${repo_dir}/${relative_path}" "${stage_dir}/${relative_path}"
}

# Minecraft/Fabric runtime. No sources, Gradle cache, world or local runtime
# state is copied.
for path in eula.txt server.jar fabric-server-launch.jar fabric-server-launcher.properties server.properties; do
  copy_file "${path}"
done

# Both Mojang's server JAR and Fabric's thin launcher reference these exact
# Maven artifacts via their manifests. They are runtime dependencies, not a
# cache; without them Java cannot even load FabricServerLauncher.
while IFS= read -r -d '' library; do
  relative_path="${library#"${repo_dir}/"}"
  install -D -m 0644 "${library}" "${stage_dir}/${relative_path}"
done < <(find "${repo_dir}/libraries" -type f -name '*.jar' -print0 | LC_ALL=C sort -z)

# Every top-level JAR is an intentional server mod. The locally built Seamless
# Frames compatibility JAR contains "lg2" in its *file name*, but is not the
# Lostglade mod and is intentionally included. Verify the actual Fabric id.
shopt -s nullglob
for mod_jar in "${repo_dir}"/mods/*.jar; do
  if unzip -p "${mod_jar}" fabric.mod.json 2>/dev/null | grep -Eq '"id"[[:space:]]*:[[:space:]]*"lg2"'; then
    echo "Refusing to package the LG2 mod: ${mod_jar}" >&2
    exit 1
  fi
  install -m 0644 "${mod_jar}" "${stage_dir}/mods/$(basename "${mod_jar}")"
done

# Config is also an explicit allow-list. In particular, LG2 state, player
# caches, skin cache, TAB users/playerdata, secrets and generated Polymer packs
# never cross this boundary.
config_files=(
  config/SeamlessFrames.conf
  config/SeamlessFrames.json
  config/cardinal-components-api.properties
  config/chunky/config.json
  config/ferritecore.mixin.properties
  config/fsit.yml
  config/polymer/auto-host.json
  config/polymer/common.json
  config/polymer/resource-pack.json
  config/polymer/server.json
  config/polymer/sound-patch.json
  config/skinrestorer/config.json
  config/tab/animations.yml
  config/tab/config.yml
  config/tab/groups.yml
  config/tab/messages.yml
  config/vcinteraction/vcinteraction.properties
  config/voicechat/translations.properties
  config/voicechat/voicechat-server.properties
  config/webcam/common.json
  config/webcam/server.json
)
for path in "${config_files[@]}"; do
  copy_file "${path}"
done

for path in \
  deploy/msk/activate-release.sh \
  deploy/msk/lostglade.service \
  deploy/msk/provision-vps.sh \
  deploy/msk/run-server.sh; do
  install -D -m 0755 "${repo_dir}/${path}" "${stage_dir}/${path}"
done

printf '%s\n' "${release_id}" > "${stage_dir}/RELEASE_COMMIT"
(
  cd "${stage_dir}"
  find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -czf "${archive_path}" .
)

tar -tzf "${archive_path}" >/dev/null
printf 'Created %s\n' "${archive_path}"
