#!/usr/bin/env python3
"""Create a relocatable, source-free Fabric client runtime for the camera bot.

Loom's development client launch uses compiled classes, mapped Minecraft jars,
the exact Fabric dependency jars, and Minecraft assets.  This exporter copies
only the paths referenced by Loom's generated launch metadata and rewrites
those paths to the immutable release location.  Gradle files, source files and
caches unrelated to the client launch are deliberately not exported.
"""

from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path


if len(sys.argv) != 4:
    raise SystemExit("usage: export-renderer-runtime.py <lg2-project> <output-dir> <runtime-prefix>")

project = Path(sys.argv[1]).resolve()
repository = project.parents[1]
output = Path(sys.argv[2]).resolve()
runtime_prefix = sys.argv[3].rstrip("/")
home_gradle_cache = Path.home() / ".gradle" / "caches"
arg_file = project / "build" / "loom-cache" / "argFiles" / "runRendererBotClient"
launch_cfg = project / ".gradle" / "loom-cache" / "launch.cfg"
remap_classpath = project / ".gradle" / "loom-cache" / "remapClasspath.txt"

for required in (arg_file, launch_cfg, remap_classpath):
    if not required.is_file():
        raise SystemExit(f"required Loom launch file is missing: {required}")

if output.exists():
    shutil.rmtree(output)
output.mkdir(parents=True)


def destination(source: Path) -> Path | None:
    try:
        return output / "project" / source.relative_to(project)
    except ValueError:
        pass
    try:
        return output / "repository" / source.relative_to(repository)
    except ValueError:
        pass
    try:
        return output / "gradle" / "caches" / source.relative_to(home_gradle_cache)
    except ValueError:
        return None


def copy_path(source: Path) -> None:
    target = destination(source)
    if target is None or not source.exists():
        return
    if source.is_dir():
        shutil.copytree(source, target, dirs_exist_ok=True, copy_function=shutil.copy2)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


raw_texts = {path: path.read_text(encoding="utf-8") for path in (arg_file, launch_cfg, remap_classpath)}
absolute_path_pattern = re.compile(r"/(?:[^:\s]+)")
candidate_paths: set[Path] = set()
for text in raw_texts.values():
    for match in absolute_path_pattern.finditer(text):
        candidate = Path(match.group(0))
        if destination(candidate) is not None and candidate.exists():
            candidate_paths.add(candidate)

for candidate in sorted(candidate_paths, key=lambda path: (len(path.parts), str(path))):
    copy_path(candidate)

# The renderer has an isolated game directory.  These are configuration and a
# single client-side compatibility mod, not source or Gradle state.
game_source = project.parents[1] / "run-renderer-bot"
if not game_source.is_dir():
    raise SystemExit(f"renderer bot game template is missing: {game_source}")
game_target = output / "game"
shutil.copytree(game_source / "config", game_target / "config", dirs_exist_ok=True, copy_function=shutil.copy2)
game_target.joinpath("mods").mkdir(parents=True, exist_ok=True)
for mod in (game_source / "mods").glob("*.jar"):
    if mod.name.endswith(".unpatched.bak"):
        continue
    shutil.copy2(mod, game_target / "mods" / mod.name)


def rewrite(text: str) -> str:
    project_target = f"{runtime_prefix}/project"
    repository_target = f"{runtime_prefix}/repository"
    cache_target = f"{runtime_prefix}/gradle/caches"
    return (text.replace(str(project), project_target)
            .replace(str(repository), repository_target)
            .replace(str(home_gradle_cache), cache_target))


# Preserve Loom's exact launch format; only the absolute locations change.
(output / "args.txt").write_text(rewrite(raw_texts[arg_file]), encoding="utf-8")
(output / "launch.cfg").write_text(rewrite(raw_texts[launch_cfg]), encoding="utf-8")
remap_target = destination(remap_classpath)
assert remap_target is not None
remap_target.parent.mkdir(parents=True, exist_ok=True)
remap_target.write_text(rewrite(raw_texts[remap_classpath]), encoding="utf-8")

print(f"Exported renderer runtime to {output}")
