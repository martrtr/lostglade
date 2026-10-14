#!/usr/bin/env python3
from __future__ import annotations
import argparse
import collections
import json
from pathlib import Path
from anvil import World, iter_chunk_blocks, block_entities, iter_entities

CONTAINERS = {"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"}

def classify(name: str):
    n = name.lower()
    if "bitcoin" in n or n.endswith(":raw_gold_block"):
        return "bitcoin"
    if "copper_ore" in n:
        return "copper"
    if n in CONTAINERS:
        return "container"
    if n == "minecraft:water":
        return "water"
    if n in {
        "minecraft:lava", "minecraft:fire", "minecraft:soul_fire",
        "minecraft:magma_block", "minecraft:powder_snow",
        "minecraft:campfire", "minecraft:soul_campfire",
    }:
        return "hazard"
    if n.endswith("_log") or n.endswith("_stem"):
        return "wood"
    if n in {"minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots"}:
        return "crop"
    return None

def scan(root: Path, x: int, z: int, radius: int):
    world_dir = root / "world"
    world = World(world_dir)
    box = (x - radius, z - radius, x + radius, z + radius)
    found = collections.defaultdict(list)
    counts = collections.Counter()
    heights = {}
    chunks = 0
    block_entity_rows = []

    for chunk in world.iter_chunks_in_block_box(*box):
        chunks += 1
        for bx, by, bz, name in iter_chunk_blocks(chunk):
            if bx < box[0] or bx > box[2] or bz < box[1] or bz > box[3]:
                continue
            counts[name] += 1
            kind = classify(name)
            if kind:
                found[kind].append([bx, by, bz, name])
            if name not in {
                "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
                "minecraft:water", "minecraft:lava",
            }:
                key = (bx, bz)
                heights[key] = max(heights.get(key, -10000), by)

        for entity in block_entities(chunk):
            if not isinstance(entity, dict):
                continue
            bx = entity.get("x")
            bz = entity.get("z")
            if isinstance(bx, int) and isinstance(bz, int) and box[0] <= bx <= box[2] and box[1] <= bz <= box[3]:
                block_entity_rows.append({
                    key: entity.get(key)
                    for key in ("id", "x", "y", "z", "Items", "items")
                    if key in entity
                })

    values = list(heights.values())
    edges_ge_2 = 0
    edges_ge_4 = 0
    for (bx, bz), height in heights.items():
        for neighbor in ((bx + 1, bz), (bx, bz + 1)):
            if neighbor in heights:
                delta = abs(height - heights[neighbor])
                edges_ge_2 += delta >= 2
                edges_ge_4 += delta >= 4

    entities = list(iter_entities(world_dir, *box) or [])
    return {
        "server_root": str(root),
        "center": [x, z],
        "radius": radius,
        "chunks_loaded": chunks,
        "found": dict(found),
        "block_entities": block_entity_rows,
        "entities": entities,
        "terrain": {
            "surface_columns": len(heights),
            "min_y": min(values) if values else None,
            "max_y": max(values) if values else None,
            "mean_y": sum(values) / len(values) if values else None,
            "edges_ge_2": edges_ge_2,
            "edges_ge_4": edges_ge_4,
        },
        "top_blocks": counts.most_common(40),
    }

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-root", type=Path, required=True)
    parser.add_argument("--x", type=int, required=True)
    parser.add_argument("--z", type=int, required=True)
    parser.add_argument("--radius", type=int, default=160)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    result = scan(args.server_root, args.x, args.z, args.radius)
    encoded = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
    if args.out:
        args.out.write_text(encoded, encoding="utf-8")
    else:
        print(encoded)
