#!/usr/bin/env python3
"""Magisk / root emulation, native (n64) engines: the emulated root never loses the owner bits of a directory or file.

Magisk makes directories with mode 000 (and leaves magisk.db at 000); the guest's "root" is a plain host user, to whom that is
a locked door. vm_mode_fix() (native/guest64/vmount.h) adds the owner bits to the mode of mkdirat / fchmodat for the emulated
root of a VM that has Root on; this script calls it from every native/guest64/<api>/run64.c:

  * the shared case "mkdirat ... fchmodat ..." (every version): its final S5() passes vm_mode_fix(nr, dirfd, host, mode);
  * the separate fchmodat / fchmodat2 case of 37 (both chmod calls in it).

Run from the checkout root after tools/patch_guest64_vmount.py; idempotent. Rebuild afterwards (see docs/magisk.md).
"""
import glob, sys

MARK = "vm_mode_fix("

def sub1(s, old, new, what, path):
    if s.count(old) != 1:
        sys.exit(f"{path}: anchor not found exactly once ({what}: {s.count(old)})")
    return s.replace(old, new)

changed = 0
for path in sorted(glob.glob("native/guest64/[0-9]*/run64.c")):
    s = open(path).read()
    if MARK in s:
        continue
    if '#include "../vmount.h"' not in s:
        sys.exit(f"{path}: run tools/patch_guest64_vmount.py first")
    s = sub1(s, "        return S5(nr, a[0], h1, a[2], a[3], a[4]);\n",
             "        // Added for AEmulator Plus (2026-11-21): the emulated root keeps the owner bits (vmount.h, vm_mode_fix)\n"
             "        return S5(nr, a[0], h1, vm_mode_fix(nr, a[0], h1, a[2]), a[3], a[4]);\n", "mkdirat/fchmodat return", path)
    if \
        "case 452 /* fchmodat2 */" in s:
        s = sub1(s, "long r = S4(NR_fchmodat, AT_FDCWD, t, a[2], 0);",
                 "long r = S4(NR_fchmodat, AT_FDCWD, t, vm_mode_fix(NR_fchmodat, AT_FDCWD, t, a[2]), 0);", "fchmodat via /proc/self/fd", path)
        s = sub1(s, "        return S4(NR_fchmodat, a[0], h1, a[2], 0);\n",
                 "        return S4(NR_fchmodat, a[0], h1, vm_mode_fix(NR_fchmodat, a[0], h1, a[2]), 0);\n", "fchmodat", path)
    open(path, "w").write(s); changed += 1; print("patched", path)
print(f"{changed} file(s) changed")
