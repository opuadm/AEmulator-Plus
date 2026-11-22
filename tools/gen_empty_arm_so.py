#!/usr/bin/env python3
# AEmulator Plus 2026-11-20. GPL-3.0; see LICENSE.
"""Writes a minimal ARM32 (EM_ARM, ET_DYN) shared object with a given soname and one data symbol, no code, no imports, no DT_NEEDED.

    python3 gen_empty_arm_so.py libstagefrighthw.so ../app/src/main/assets/engines/common/libstagefrighthw_stub.so

core/TreeFixer.kt puts it where a parked vendor library used to be when another firmware library still lists that library in
DT_NEEDED (LG: libimsmmpf.so -> libstagefrighthw.so; without the file com.lge.ims dies in System.loadLibrary("ims")).
Written by hand because the stub needs no compiler: one RW PT_LOAD, PT_DYNAMIC, classic SysV hash (bionic 2.3 .. 5.x read it).
"""
import struct, sys


def build(soname: str, marker: str = "__aemu_stub") -> bytes:
    strtab = b"\0" + marker.encode() + b"\0" + soname.encode() + b"\0"
    name_off, so_off = 1, 1 + len(marker) + 1
    ehdr_sz, phdr_sz, nph = 52, 32, 2
    off = ehdr_sz + phdr_sz * nph
    hash_off = off                      # nbucket=1 nchain=2 bucket[0]=1 chain[0]=0 chain[1]=0
    hash_tab = struct.pack("<6I", 1, 2, 1, 0, 0, 0)[:20]
    sym_off = (hash_off + len(hash_tab) + 3) & ~3
    syms = struct.pack("<IIIBBH", 0, 0, 0, 0, 0, 0)
    dyn_off = (sym_off + 16 * 2 + 7) & ~7
    str_off = dyn_off + 8 * 8
    data_off = (str_off + len(strtab) + 3) & ~3
    total = data_off + 4
    syms += struct.pack("<IIIBBH", name_off, data_off, 4, (1 << 4) | 1, 0, 1)  # GLOBAL OBJECT, shndx != 0
    dyn = b"".join(struct.pack("<II", t, v) for t, v in [
        (4, hash_off), (5, str_off), (6, sym_off), (10, len(strtab)), (11, 16), (14, so_off), (0, 0), (0, 0)])
    ehdr = b"\x7fELF" + bytes([1, 1, 1, 0]) + b"\0" * 8
    ehdr += struct.pack("<HHIIIIIHHHHHH", 3, 40, 1, 0, ehdr_sz, 0, 0x05000000, ehdr_sz, phdr_sz, nph, 40, 0, 0)
    ph_load = struct.pack("<8I", 1, 0, 0, 0, total, total, 6, 0x1000)
    ph_dyn = struct.pack("<8I", 2, dyn_off, dyn_off, dyn_off, len(dyn), len(dyn), 6, 4)
    out = bytearray(total)
    out[0:52] = ehdr
    out[52:84] = ph_load
    out[84:116] = ph_dyn
    out[hash_off:hash_off + len(hash_tab)] = hash_tab
    out[sym_off:sym_off + len(syms)] = syms
    out[dyn_off:dyn_off + len(dyn)] = dyn
    out[str_off:str_off + len(strtab)] = strtab
    out[data_off:data_off + 4] = struct.pack("<I", 1)
    return bytes(out)


if __name__ == "__main__":
    soname, dst = sys.argv[1], sys.argv[2]
    data = build(soname)
    open(dst, "wb").write(data)
    print(f"{dst}: {len(data)} bytes, soname {soname}")
