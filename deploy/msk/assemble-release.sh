#!/usr/bin/env bash
set -euo pipefail

# Build a deliberately small production release.  Do not turn this into an
# rsync of the repository: the allow-list below is the release contract.
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
release_id="${1:-$(git -C "${repo_dir}" rev-parse HEAD)}"

if [[ ! "${release_id}" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Release id must be a full lowercase Git commit SHA.' >&2
  exit 2
fi

# Produce release-mode server/client jars and Loom's exact client launch
# metadata.  The renderer export below copies only the compiled runtime files
# referenced by that metadata, never the Gradle project or its sources.
(
  cd "${repo_dir}/mods/lg2-0.1.0"
  ./gradlew --no-daemon --console=plain \
    prepareDevResourcePack build remapGameplayClientJar \
    prepareRendererBotClientRunDir configureClientLaunch

  # Loom creates an argfile only when a named run configuration is executed.
  # Metadata is written before the client opens a window; a display/server is
  # not needed for this bounded metadata-generation invocation.
  renderer_argfile='build/loom-cache/argFiles/runRendererBotClient'
  if [[ ! -f "${renderer_argfile}" ]]; then
    set +e
    timeout 45s ./gradlew --no-daemon --console=plain runRendererBotClient \
      -Dlg2.rendererBot=true \
      -Dlg2.rendererBotName=renderer-export \
      -Dlg2.rendererBotServer=127.0.0.1:9 \
      >/dev/null 2>&1
    set -e
    [[ -f "${renderer_argfile}" ]] || {
      echo 'Loom did not produce renderer client launch metadata.' >&2
      exit 1
    }
  fi
)

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
for path in eula.txt server.jar fabric-server-launch.jar fabric-server-launcher.properties; do
  copy_file "${path}"
done
# Vanilla rewrites the timestamp comment in server.properties on every start.
# Package it as a checked template; activation copies it to persistent runtime
# state and links the expected server.properties name back into the release.
install -D -m 0644 "${repo_dir}/server.properties" "${stage_dir}/server.properties.template"
install -D -m 0644 "${repo_dir}/server-icon.png" "${stage_dir}/server-icon.png"
install -D -m 0644 "${repo_dir}/whitelist.json" "${stage_dir}/whitelist.json.template"

# Both Mojang's server JAR and Fabric's thin launcher reference these exact
# Maven artifacts via their manifests. They are runtime dependencies, not a
# cache; without them Java cannot even load FabricServerLauncher.
while IFS= read -r -d '' library; do
  relative_path="${library#"${repo_dir}/"}"
  install -D -m 0644 "${library}" "${stage_dir}/${relative_path}"
done < <(find "${repo_dir}/libraries" -type f -name '*.jar' -print0 | LC_ALL=C sort -z)

# Every top-level JAR is an intentional server mod. The locally built Seamless
# Frames compatibility JAR contains "lg2" in its *file name*, but is not the
# Lostglade mod and is intentionally included. Voice Chat is pinned separately
# below so releases cannot silently regress to the stale repository copy.
shopt -s nullglob
for mod_jar in "${repo_dir}"/mods/*.jar; do
  case "$(basename "${mod_jar}")" in
    voicechat-fabric-1.21.11-*.jar) continue ;;
  esac
  install -m 0644 "${mod_jar}" "${stage_dir}/mods/$(basename "${mod_jar}")"
done

voicechat_file='voicechat-fabric-1.21.11-2.6.22.jar'
voicechat_url='https://cdn.modrinth.com/data/9eGKb6K1/versions/CN53keBo/voicechat-fabric-1.21.11-2.6.22.jar'
voicechat_sha512='dab29df8577b220e1c2ad97b784c5acb837501c8ba42d83205c085f44b4a6df75bce2477b8c2c7905285d248fd2d195b3dc77f169cb11a226dc2965e23f5859a'
curl -fsSL --retry 3 --retry-delay 2 "${voicechat_url}" -o "${stage_dir}/mods/${voicechat_file}"
printf '%s  %s\n' "${voicechat_sha512}" "${stage_dir}/mods/${voicechat_file}" | sha512sum -c -

lg2_server_jar="${repo_dir}/mods/lg2-0.1.0/build/libs/lg2-1.0.0.jar"
[[ -f "${lg2_server_jar}" ]] || { echo "LG2 server jar is missing: ${lg2_server_jar}" >&2; exit 1; }
install -m 0644 "${lg2_server_jar}" "${stage_dir}/mods/lg2-1.0.0.jar"

python3 "${repo_dir}/deploy/msk/export-renderer-runtime.py" \
  "${repo_dir}/mods/lg2-0.1.0" \
  "${stage_dir}/renderer-bot" \
  '/srv/lostglade/current/renderer-bot'

# Config is also an explicit allow-list. Runtime state, player caches, skin
# cache, TAB users/playerdata, secrets and generated Polymer packs never cross
# this boundary. LG2 configs are seeded once and then preserved outside releases.
config_files=(
  config/SeamlessFrames.conf
  config/SeamlessFrames.json
  config/cardinal-components-api.properties
  config/chunky/config.json
  config/ferritecore.mixin.properties
  config/fsit.yml
  config/lg2-auth.template.json
  config/lg2-glitches.json
  config/lg2-races.json
  config/lg2-renderer-mod-allowlist.json
  config/lg2-season-start.json
  config/lg2-support.json
  config/lg2-upgrades.json
  config/lg2.json
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
mv "${stage_dir}/config/lg2-auth.template.json" "${stage_dir}/config/lg2-auth.json"

while IFS= read -r -d '' file; do
  relative_path="${file#"${repo_dir}/"}"
  install -D -m 0644 "${file}" "${stage_dir}/${relative_path}"
done < <(find "${repo_dir}/config/lg2-season-start" -type f -print0 | LC_ALL=C sort -z)

for path in \
  deploy/msk/activate-release.sh \
  deploy/msk/lostglade.service \
  deploy/msk/provision-vps.sh \
  deploy/msk/run-server.sh \
  deploy/msk/run-renderer-bot.sh; do
  install -D -m 0755 "${repo_dir}/${path}" "${stage_dir}/${path}"
done
install -D -m 0644 \
  "${repo_dir}/deploy/msk/lostglade-renderer-bot.service" \
  "${stage_dir}/deploy/msk/lostglade-renderer-bot.service"

printf '%s\n' "${release_id}" > "${stage_dir}/RELEASE_COMMIT"
(
  cd "${stage_dir}"
  find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -czf "${archive_path}" .
)

tar -tzf "${archive_path}" >/dev/null
printf 'Created %s\n' "${archive_path}"
