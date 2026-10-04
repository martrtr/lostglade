#!/usr/bin/env bash
set -euo pipefail

runtime='/srv/lostglade/current/renderer-bot'
game_dir="${runtime}/game"
display=':99'

[[ -f "${runtime}/launch.cfg" && -f "${runtime}/args.txt" ]] || {
  echo 'Renderer bot runtime is incomplete.' >&2
  exit 1
}

export DISPLAY="${display}"
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
export XDG_RUNTIME_DIR='/tmp/lostglade-renderer-xdg'
install -d -m 0700 "${XDG_RUNTIME_DIR}"

# The renderer runs through llvmpipe on the VPS. Keep its normal game loop
# bounded and avoid decoding player webcams in this technical client.
if [[ -f "${game_dir}/options.txt" ]]; then
  sed -i 's/^maxFps:.*/maxFps:30/' "${game_dir}/options.txt"
fi
if [[ -f "${game_dir}/config/webcam/client.json" ]]; then
  python3 - "${game_dir}/config/webcam/client.json" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding='utf-8') as handle:
    data = json.load(handle)
data['show_webcams'] = False
data['webcam_enabled'] = False
with open(path, 'w', encoding='utf-8') as handle:
    json.dump(data, handle, indent=2)
    handle.write('\n')
PY
fi

Xvfb "${display}" -screen 0 1280x720x24 +extension GLX -nolisten tcp &
xvfb_pid=$!
cleanup() {
  kill "${xvfb_pid}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# The service is ordered after Minecraft, but wait for its local listener so
# the offline renderer account can complete its first connection immediately.
for _ in $(seq 1 60); do
  if (echo >/dev/tcp/127.0.0.1/25565) 2>/dev/null; then
    break
  fi
  sleep 1
done

cd "${game_dir}"
/usr/bin/java \
  -Dfabric.dli.config="${runtime}/launch.cfg" \
  -Dfabric.dli.env=client \
  -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient \
  -Dlg2.rendererBot=true \
  -Dlg2.rendererBotName="${LG2_RENDERER_BOT_NAME:-camera}" \
  -Dlg2.rendererBotServer='127.0.0.1:25565' \
  -Dlg2.cameraMediaCacheRoot='/srv/lostglade/data/cache/lg2-camera' \
  -Xms2G -Xmx4G \
  @"${runtime}/args.txt" \
  net.fabricmc.devlaunchinjector.Main \
  --offlineDeveloperMode \
  "--username=${LG2_RENDERER_BOT_NAME:-camera}" \
  --width=960 --height=540
