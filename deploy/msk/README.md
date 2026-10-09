# Lostglade deployment: msk.sh

This is the production deployment path for `31.77.251.51` (Debian 13, 12 vCPU,
24 GiB RAM, 300 GiB disk). It deploys only an allow-listed server runtime.

## What a release contains

- Fabric launcher, Minecraft server JAR, their required Maven runtime
  libraries, EULA and a checked `server.properties` template;
- every allow-listed server mod JAR, including the release-built `lg2` JAR;
- an explicit list of settings, including LG2's first-run settings;
- a source-free renderer-client runtime plus the small systemd and activation
  scripts needed at runtime.

It never contains LG2 source code, the Gradle project, worlds, player data,
logs, secrets or locally generated resource packs. The renderer runtime contains
only the compiled client files required by Fabric's launcher; it cannot build the
mod. World, player state, Fabric's remap cache, downloaded vanilla runtime,
Polymer pack and TAB's player caches live in `/srv/lostglade/data`, outside
releases.
Secrets live in `/srv/lostglade/server-secrets` and are linked into a release
only at runtime; they are never included in a release archive or Git.

LG2 configuration is copied to persistent storage only for its first release;
later releases retain auth and season state. `seamless-itemframes` remains: its
file name has an `-lg2` suffix only because it is the compatibility port prepared
for this project; its Fabric mod id is `sframes`.

## Release process

Push a commit to the `release` branch. GitHub Actions builds an
archive named after that exact commit, uploads it over SSH, and runs the host
activation command. Activating a release verifies SHA-256 checksums, switches
the `current` symlink atomically, waits for Minecraft's `Done (...)` startup
line, and switches back if startup fails. The current and two prior releases
are kept for rollback.

Repository secrets required by the workflow:

- `MSK_SSH_PASSWORD` — password for the deployment SSH account;

The SSH host key is pinned in the workflow. Do not store passwords in Git,
workflow YAML, or a release archive.

## First provisioning

The first provisioning is performed once from an administrator's trusted
machine. The GitHub workflow handles all later releases.

```bash
./deploy/msk/assemble-release.sh "$(git rev-parse HEAD)"
scp "dist/lostglade-$(git rev-parse HEAD).tar.gz" root@31.77.251.51:/root/
ssh root@31.77.251.51
mkdir -p /srv/lostglade/bootstrap /srv/lostglade/incoming
tar -xzf /root/lostglade-<commit>.tar.gz -C /srv/lostglade/bootstrap
/srv/lostglade/bootstrap/deploy/msk/provision-vps.sh /srv/lostglade/bootstrap
mv /root/lostglade-<commit>.tar.gz /srv/lostglade/incoming/<commit>.tar.gz
lostglade-activate-release <commit>
```

After the initial deployment, use `journalctl -u lostglade -f` to watch
startup. Chunky pre-generation is intentionally a manual post-release task.

## Daily maintenance

`config/lg2.json` has `restartHourMsk` (default `3`, clamped to `0..23`). The
server saves and starts an online ZIP backup 30 minutes before that Moscow
hour, then stops at the scheduled hour. If the archive is still being written,
shutdown waits until it finishes. Warning titles are sent at 30, 10, 3 and 1
minutes, then 30, 10, 5, 4, 3, 2 and 1 seconds before shutdown.

The backup is written to `/srv/lostglade/data/backups` as
`lg2-YYYY-MM-DD-HH-msk.zip` and includes the world, release config, persistent
config (including LG2 authentication state) and server properties/allow lists.
The backup directory and archives are private to the server user on Linux.
Only completed LG2 ZIP archives older than seven days are pruned. Logs, caches,
release binaries and the separate `server-secrets/` directory are excluded.
This is an online save-and-copy backup, not an atomic filesystem
snapshot; an off-host copy is still recommended for disaster recovery.

Operators can schedule an earlier restart with `/restart <minutes>` (positive
integer, required). It starts a separate backup immediately, shows the initial
red title and then sends each warning threshold reached during the countdown.
An archive still in progress delays the stop until it finishes.

`lostglade.service` uses `Restart=always` to start Minecraft after a clean
scheduled stop. `systemctl stop lostglade.service` still stops it intentionally;
the in-game `/stop` command will instead be followed by a service restart.
The Gradle `runServer` development task has no supervisor and will simply exit.
Release activation removes the obsolete 04:00 `lostglade-maintenance.timer`
and its service, so it cannot trigger a second restart or backup.

For a separate offline recovery point, stop `lostglade.service` intentionally
and run `lostglade-backup` as root, then start the service again. This manual
tool creates and verifies a private tar.gz archive plus SHA-256 checksum in
`/srv/lostglade/backups`. Unlike the automatic ZIP, it includes the generated
Polymer pack and `server-secrets/`. It also prunes its own archives older than
seven days. Neither local backup location survives total VPS loss; off-host
copies require separate storage.
