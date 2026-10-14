#!/usr/bin/env python3
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

IMPACT = {
    "gravity_surges": ["physics", "route+pickup", "moves players and item entities every tick while active"],
    "phantom_chunk": ["topology", "route", "replaces a live chunk or makes it intangible, then restores it"],
    "player_shuffle": ["teleport", "route", "can copy another eligible player's position"],
    "chest_desync": ["interaction", "storage", "opening one storage can open another touched storage"],
    "checkpoint_desync": ["respawn", "recovery", "can respawn at another eligible player's spawn target"],
    "phantom_mobs": ["perception", "survival", "server-created invulnerable/no-AI phantom entities can poison threat selection"],
    "time_of_day_jumps": ["world-time", "indirect", "changes downstream vanilla mob/light circumstances"],
    "bitcoin_overcook": ["resource", "bitcoin", "interferes with bitcoin furnace processing"],
    "blackout": ["presentation", "none", "visual for Mineflayer"],
    "inventory_texture_shuffle": ["presentation", "none", "visual inventory presentation"],
    "chat_interference": ["chat", "none", "incoming chat must never control Badyaga"],
    "phantom_sounds": ["audio", "none", "audio-only for current bot"],
    "upside_down_mobs": ["presentation/entity", "low", "primarily presentation unless collision semantics differ"],
}

SOURCE_FILES = [
    "src/main/java/com/lostglade/server/ServerGlitchSystem.java",
    "src/main/java/com/lostglade/server/ServerStabilitySystem.java",
    "src/main/java/com/lostglade/server/glitch/GravitySurgeGlitch.java",
    "src/main/java/com/lostglade/server/glitch/PhantomChunkGlitch.java",
    "src/main/java/com/lostglade/server/glitch/PlayerShuffleGlitch.java",
    "src/main/java/com/lostglade/server/glitch/ChestDesyncGlitch.java",
    "src/main/java/com/lostglade/server/glitch/CheckpointDesyncGlitch.java",
    "src/main/java/com/lostglade/server/glitch/PhantomMobGlitch.java",
    "src/main/java/com/lostglade/worldgen/BitcoinOreFeature.java",
    "src/main/java/com/lostglade/config/Lg2Config.java",
]

def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def clamp(value, low=0.0, high=1.0):
    return max(low, min(high, value))

def range_instability(stability, min_stability, max_stability):
    span = max_stability - min_stability
    if span <= 1e-9:
        return 1.0
    return clamp(1.0 - ((stability - min_stability) / span))

def effective_chance(entry, stability):
    base = clamp(float(entry.get("chancePerCheck", 0.0)))
    influence = clamp(float(entry.get("stabilityInfluence", 1.0)))
    instability = range_instability(
        stability,
        float(entry.get("minStabilityPercent", 0.0)),
        float(entry.get("maxStabilityPercent", 100.0)),
    )
    return base * ((1.0 - influence) + instability * influence)

def cooldown_ticks(entry, stability):
    minimum = max(0, int(entry.get("minCooldownTicks") or 0))
    maximum = max(minimum, int(entry.get("maxCooldownTicks") or minimum))
    start = max(0.0, float(entry.get("maxStabilityPercent", 100.0)))
    if start <= 1e-9:
        return minimum
    factor = clamp(stability / start)
    return round(minimum + ((maximum - minimum) * factor))

def expected_trigger_seconds(entry, stability, check_ticks):
    if stability < float(entry.get("minStabilityPercent", 0.0)):
        return None
    if stability > float(entry.get("maxStabilityPercent", 100.0)):
        return None
    probability = effective_chance(entry, stability)
    if probability <= 0.0:
        return None
    checks_to_hit = 1.0 / probability
    return (cooldown_ticks(entry, stability) + checks_to_hit * check_ticks) / 20.0

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-root", type=Path, required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    root = args.server_root
    mod = root / "mods" / "lg2-0.1.0"
    lg2 = json.loads((root / "config" / "lg2.json").read_text(encoding="utf-8-sig"))
    glitches = json.loads((root / "config" / "lg2-glitches.json").read_text(encoding="utf-8-sig"))
    check_ticks = int(glitches.get("checkIntervalTicks", 20))

    rows = []
    for glitch_id, entry in glitches.get("glitches", {}).items():
        rows.append({
            "id": glitch_id,
            "enabled": bool(entry.get("enabled", False)),
            "min_stability": entry.get("minStabilityPercent"),
            "max_stability": entry.get("maxStabilityPercent"),
            "base_chance": entry.get("chancePerCheck"),
            "impact": IMPACT.get(glitch_id, ["unknown", "unknown", "inspect source"]),
            "by_stability": {
                str(stability): {
                    "effective_chance": round(effective_chance(entry, stability), 6),
                    "cooldown_ticks": cooldown_ticks(entry, stability),
                    "expected_trigger_seconds": expected_trigger_seconds(entry, stability, check_ticks),
                }
                for stability in (100, 75, 50, 40, 30, 20, 10, 0)
                if float(entry.get("minStabilityPercent", 0.0)) <= stability <= float(entry.get("maxStabilityPercent", 100.0))
            },
        })

    report = {
        "source_hashes": {
            rel: sha(mod / rel)
            for rel in SOURCE_FILES
            if (mod / rel).exists()
        },
        "config_hashes": {
            path.name: sha(path)
            for path in (
                root / "config" / "lg2.json",
                root / "config" / "lg2-glitches.json",
                root / "server.properties",
            )
            if path.exists()
        },
        "bitcoin_worldgen": {
            key: lg2.get(key)
            for key in (
                "oresPerChunkMin", "oresPerChunkMax", "veinSizeMin", "veinSizeMax",
                "dropMin", "dropMax", "spawnMinY", "spawnMaxY",
                "silkTouchEnabled", "fortuneEnabled",
            )
        },
        "stability": {
            key: lg2.get(key)
            for key in (
                "stabilityMax", "stabilityDecayIntervalSeconds",
                "stabilityDecayIntervalSecondsPerPlayer", "bitcoinsPerStability",
            )
        },
        "glitch_check_interval_ticks": check_ticks,
        "glitch_max_activations_per_check": glitches.get("maxActivationsPerCheck"),
        "glitches": rows,
    }

    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(encoded, encoding="utf-8")
    else:
        print(encoded)

if __name__ == "__main__":
    main()
