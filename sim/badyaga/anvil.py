from __future__ import annotations
import gzip
import math
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Optional
from nbt import loads

SECTOR = 4096

@dataclass
class Chunk:
    x: int
    z: int
    root: dict

class RegionFile:
    def __init__(self, path: Path):
        self.path = Path(path)
        parts = self.path.stem.split(".")
        self.rx = int(parts[1])
        self.rz = int(parts[2])

    def _entry(self, lx: int, lz: int):
        with self.path.open("rb") as f:
            f.seek(4 * (lx + lz * 32))
            b = f.read(4)
        if len(b) != 4:
            return None
        offset = (b[0] << 16) | (b[1] << 8) | b[2]
        count = b[3]
        if offset == 0 or count == 0:
            return None
        return offset, count

    def read_chunk(self, cx: int, cz: int) -> Optional[Chunk]:
        entry = self._entry(cx & 31, cz & 31)
        if not entry:
            return None
        offset, _ = entry
        with self.path.open("rb") as f:
            f.seek(offset * SECTOR)
            header = f.read(5)
            if len(header) < 5:
                return None
            length = struct.unpack(">I", header[:4])[0]
            compression = header[4]
            payload = f.read(max(0, length - 1))

        external = bool(compression & 0x80)
        compression &= 0x7F
        if external:
            payload = (self.path.parent / f"c.{cx}.{cz}.mcc").read_bytes()

        if compression == 1:
            raw = gzip.decompress(payload)
        elif compression == 2:
            raw = zlib.decompress(payload)
        elif compression == 3:
            raw = payload
        elif compression == 4:
            try:
                import lz4.block
            except Exception as exc:
                raise RuntimeError("LZ4 chunk but python lz4 is unavailable") from exc
            raw = lz4.block.decompress(payload)
        else:
            raise RuntimeError(f"unknown region compression {compression}")

        return Chunk(cx, cz, loads(raw).value)

class World:
    def __init__(self, world_dir: Path):
        self.world_dir = Path(world_dir)
        self.region_dir = self.world_dir / "region"
        self._regions = {}

    def region(self, rx: int, rz: int) -> Optional[RegionFile]:
        key = (rx, rz)
        if key not in self._regions:
            path = self.region_dir / f"r.{rx}.{rz}.mca"
            self._regions[key] = RegionFile(path) if path.exists() else None
        return self._regions[key]

    def chunk(self, cx: int, cz: int) -> Optional[Chunk]:
        region = self.region(cx >> 5, cz >> 5)
        return region.read_chunk(cx, cz) if region else None

    def iter_chunks_in_block_box(self, minx, minz, maxx, maxz) -> Iterator[Chunk]:
        for cz in range(math.floor(minz / 16), math.floor(maxz / 16) + 1):
            for cx in range(math.floor(minx / 16), math.floor(maxx / 16) + 1):
                chunk = self.chunk(cx, cz)
                if chunk:
                    yield chunk

def _palette_name(entry):
    if isinstance(entry, dict):
        return entry.get("Name") or entry.get("name") or "minecraft:air"
    return str(entry)

def _decode_indices(data: list[int], palette_len: int, count: int = 4096):
    if palette_len <= 1:
        return [0] * count
    bits = max(4, (palette_len - 1).bit_length())
    mask = (1 << bits) - 1
    unsigned = [value & ((1 << 64) - 1) for value in data]
    out = []
    for i in range(count):
        bit = i * bits
        long_index = bit >> 6
        shift = bit & 63
        if long_index >= len(unsigned):
            out.append(0)
            continue
        value = unsigned[long_index] >> shift
        spill = shift + bits - 64
        if spill > 0 and long_index + 1 < len(unsigned):
            value |= unsigned[long_index + 1] << (bits - spill)
        out.append(value & mask)
    return out

def iter_chunk_blocks(chunk: Chunk):
    sections = chunk.root.get("sections") or chunk.root.get("Sections") or []
    for section in sections:
        sy = section.get("Y")
        block_states = section.get("block_states") or section.get("BlockStates")
        if sy is None or not isinstance(block_states, dict):
            continue
        palette = block_states.get("palette") or block_states.get("Palette") or []
        if not palette:
            continue
        names = [_palette_name(entry) for entry in palette]
        data = block_states.get("data") or block_states.get("Data") or []
        indices = _decode_indices(data, len(names))
        for i, palette_index in enumerate(indices):
            if palette_index >= len(names):
                palette_index = 0
            x = i & 15
            z = (i >> 4) & 15
            y = (i >> 8) & 15
            yield chunk.x * 16 + x, int(sy) * 16 + y, chunk.z * 16 + z, names[palette_index]

def block_entities(chunk: Chunk):
    return chunk.root.get("block_entities") or chunk.root.get("TileEntities") or []

def iter_entities(world_dir: Path, minx, minz, maxx, maxz):
    region_dir = Path(world_dir) / "entities"
    if not region_dir.exists():
        return
    mincx = math.floor(minx / 16)
    maxcx = math.floor(maxx / 16)
    mincz = math.floor(minz / 16)
    maxcz = math.floor(maxz / 16)
    cache = {}
    for cz in range(mincz, maxcz + 1):
        for cx in range(mincx, maxcx + 1):
            rx, rz = cx >> 5, cz >> 5
            key = (rx, rz)
            if key not in cache:
                path = region_dir / f"r.{rx}.{rz}.mca"
                cache[key] = RegionFile(path) if path.exists() else None
            region = cache[key]
            if not region:
                continue
            chunk = region.read_chunk(cx, cz)
            if not chunk:
                continue
            entities = chunk.root.get("Entities") or chunk.root.get("entities") or []
            for entity in entities:
                if not isinstance(entity, dict):
                    continue
                pos = entity.get("Pos") or entity.get("pos")
                if not isinstance(pos, list) or len(pos) < 3:
                    continue
                x, y, z = map(float, pos[:3])
                if minx <= x <= maxx and minz <= z <= maxz:
                    yield {
                        "id": entity.get("id"),
                        "uuid": entity.get("UUID") or entity.get("UUIDMost"),
                        "pos": [x, y, z],
                        "health": entity.get("Health"),
                    }
