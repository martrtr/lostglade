#!/usr/bin/env bash
set -euo pipefail

cd /srv/lostglade/current

# 24 GiB VPS: leave room for the OS filesystem cache and native memory while
# giving Minecraft enough heap for 16-chunk view distance and 15 players.
exec /usr/bin/java \
  -Xms8G \
  -Xmx12G \
  -XX:+UseG1GC \
  -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 \
  -XX:InitiatingHeapOccupancyPercent=15 \
  -Dlog4j2.formatMsgNoLookups=true \
  -jar fabric-server-launch.jar nogui
