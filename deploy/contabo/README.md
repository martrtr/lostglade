# Lostglade: Contabo VPS 8 deployment

Target: Contabo Cloud VPS 8, 8 vCPU, 24 GiB RAM, 300 GB SSD, Debian 13 or Ubuntu 24.04.

The package intentionally runs the server and the headless renderer bot from the
same checked-out LG2 project. The bot depends on Loom's development runtime:
the production server jar deliberately excludes the bot's client code.

The settings in the repository are the VPS profile:

- 16 chunk view distance and 8 chunk simulation distance;
- `max-players=16`: 15 player slots plus the `camera` client;
- server heap 8 GiB maximum, camera-client heap 4 GiB maximum;
- only FerriteCore (memory) and Chunky (manual pre-generation) are enabled;
- `cameraRenderThreads=2`, monitor rendering 3, quantization 2. These cap
  background work without changing game mechanics or visual quality.

`seamless-itemframes-1.1.0+1.21.11-lg2.jar` is a locally rebuilt compatibility
port of the supplied Seamless Frames source for 1.21.11. It preserves the
shears-to-invisible-frame, leather restore, glow and `/sframes` behavior. The
original 1.21.9 JAR is retained as `.disabled` because it crashes 1.21.11 as
soon as a player joins. The rebuilt JAR SHA-256 is
`47dd725ed3290fa1974698459b049ca22b79b4fe32933ae2afe2ec0bdc55b1e2`.

The supplied `fabsit-1.5.3+1.19.2.jar` is not loadable on 1.21.11, so it is
intentionally replaced by the already configured FSit 2.8.3 for 1.21.11. It
retains `/sit`, `/crawl`, right-click seating and player riding; the old FabSit
JAR is not copied into `mods/`.

## Build the transfer bundle

From the repository root:

```bash
nix-shell --run './deploy/contabo/assemble-release.sh'
```

The result is `dist/lostglade-contabo-vps8.tar.zst`. It includes the world,
runtime configuration, all server mods, source, Gradle wrapper and generated
LG2 artifacts. It deliberately excludes `server-secrets/`, caches, logs and
player IP caches. Copy `server-secrets/` through the SSH session separately;
never publish it in a release archive.

## On the new VPS

```bash
sudo install -d -m 0755 /srv/lostglade
sudo tar --zstd -xpf lostglade-contabo-vps8.tar.zst -C /srv/lostglade
sudo rsync -a server-secrets/ /srv/lostglade/server-secrets/
cd /srv/lostglade
sudo ./deploy/contabo/provision-vps.sh
```

The provisioning script installs Java 21, Mesa software OpenGL, a local dummy
Xorg display and ffmpeg,
creates an unprivileged `lostglade` user, applies a narrow UFW firewall and
starts `lostglade.service`. The only public ports are TCP 25565 (Minecraft) and
UDP 24454 (Simple Voice Chat). The renderer bot and its GL backend remain local
to the VPS.

## Player GPU renderers (optional)

The VPS has no usable GPU, so a player with a strong PC can temporarily donate
their GPU to camera work. Install the updated `lg2-client` mod from this bundle,
join normally, and press **F8** (`Отдать GPU для рендера камер`). The opt-in is
local, persists in that player's `config/lg2.json`, and is off by default.
The player remains visible and playable; camera frames use an isolated shadow
world which is restored immediately afterwards.

The server prefers one opted-in player renderer, then automatically falls back
to the permanent `RendererBot`. Keep that fallback enabled. Remote videos are
encoded on the volunteer's computer and uploaded to the VPS as ordered,
size-limited MP4 chunks; install `ffmpeg` on a volunteer computer that will
record video. Set `cameraRendererAllowPlayerVolunteers: false` in the server's
`config/lg2.json` to disable player volunteers globally.

## First start and pre-generation

Wait for `systemctl status lostglade` to be healthy, then check:

```bash
journalctl -u lostglade -f
```

The server starts the camera bot itself. Successful startup contains both
`Done (...)!` and `Renderer bot joined 127.0.0.1:25565` in the journal.

Before opening the server, pre-generate the start area with Chunky from the
server console:

```
chunky world world
chunky center 0 0
chunky radius 2200
chunky start
```

Run this while the server is closed to players: it deliberately consumes CPU
and its progress is stored, so it can be stopped and resumed. It prevents the
initial 2 km player cluster from generating terrain during the online peak.

## Deliberately excluded optimisations

`lithium`, `vmp` and `krypton` are not in this build. They modify tick/AI,
entity-tracking and networking paths; that is an unacceptable regression risk
for LG2's custom stalker, movement, camera, glitch and low-stability behaviour.
They can be trialled later only in a cloned world with gameplay regression
tests.
