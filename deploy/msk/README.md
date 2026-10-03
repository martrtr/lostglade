# Lostglade deployment: msk.sh

This is the production deployment path for `31.77.251.51` (Debian 13, 12 vCPU,
24 GiB RAM, 300 GiB disk). It deploys only an allow-listed server runtime.

## What a release contains

- Fabric launcher, Minecraft server JAR, their required Maven runtime
  libraries, EULA and a checked `server.properties` template;
- every top-level server mod JAR except a JAR whose Fabric id is `lg2`;
- an explicit list of settings for those mods;
- the small systemd and activation scripts needed at runtime.

It never contains the LG2 source/build, LG2 configuration, worlds, player data,
logs, caches, secrets, Gradle files or locally generated resource packs. World,
player state, Fabric's remap cache, downloaded vanilla runtime, Polymer pack and
TAB's player caches live in `/srv/lostglade/data`, outside releases.
Secrets live in `/srv/lostglade/server-secrets` and are linked into a release
only at runtime; they are never included in an archive or Git.

The initial release therefore contains **no LG2 mod**. `seamless-itemframes`
remains: its file name has an `-lg2` suffix only because it is the compatibility
port prepared for this project; its Fabric mod id is `sframes`.

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
