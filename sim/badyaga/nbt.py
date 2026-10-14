from __future__ import annotations
import io
import struct
from dataclasses import dataclass
from typing import Any

class NBTError(Exception):
    pass

@dataclass
class NamedTag:
    name: str
    value: Any

class Reader:
    def __init__(self, data: bytes):
        self.f = io.BytesIO(data)

    def read(self, n: int) -> bytes:
        b = self.f.read(n)
        if len(b) != n:
            raise NBTError(f"unexpected EOF: wanted {n}, got {len(b)}")
        return b

    def u8(self): return self.read(1)[0]
    def i8(self): return struct.unpack(">b", self.read(1))[0]
    def i16(self): return struct.unpack(">h", self.read(2))[0]
    def u16(self): return struct.unpack(">H", self.read(2))[0]
    def i32(self): return struct.unpack(">i", self.read(4))[0]
    def i64(self): return struct.unpack(">q", self.read(8))[0]
    def f32(self): return struct.unpack(">f", self.read(4))[0]
    def f64(self): return struct.unpack(">d", self.read(8))[0]

    def string(self):
        n = self.u16()
        return self.read(n).decode("utf-8", errors="replace")

def _payload(r: Reader, t: int):
    if t == 0: return None
    if t == 1: return r.i8()
    if t == 2: return r.i16()
    if t == 3: return r.i32()
    if t == 4: return r.i64()
    if t == 5: return r.f32()
    if t == 6: return r.f64()
    if t == 7:
        n = r.i32()
        return r.read(n)
    if t == 8:
        return r.string()
    if t == 9:
        child = r.u8()
        n = r.i32()
        return [_payload(r, child) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            child = r.u8()
            if child == 0:
                break
            name = r.string()
            out[name] = _payload(r, child)
        return out
    if t == 11:
        n = r.i32()
        return [r.i32() for _ in range(n)]
    if t == 12:
        n = r.i32()
        return [r.i64() for _ in range(n)]
    raise NBTError(f"unknown tag type {t}")

def loads(data: bytes) -> NamedTag:
    r = Reader(data)
    t = r.u8()
    if t == 0:
        return NamedTag("", None)
    name = r.string()
    return NamedTag(name, _payload(r, t))
