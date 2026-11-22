#!/usr/bin/env python3
"""Zygisk, native (n64) engines: answer getsockopt(SOL_SOCKET, SO_PEERSEC) with a guest SELinux context (vmount.h, vm_peersec).

Magisk's daemon serves ZYGISK requests only from a peer whose context is "u:r:zygote:s0". Run from the checkout root after
tools/patch_guest64_vmount.py; idempotent. Rebuild afterwards (see docs/magisk.md)."""
import glob, sys
OLD = "    case NR_getsockopt: {\n        if (is_nl_fd((int)a[0])) return 0;\n"
NEW = OLD + "        if (g_vm && a[1] == 1 && a[2] == 31 /* SO_PEERSEC */) return vm_peersec(a[0], (char *)a[3], (u32 *)a[4]);   // Added for AEmulator Plus (2026-11-21): Zygisk, see vmount.h\n"
n = 0
for path in sorted(glob.glob("native/guest64/[0-9]*/run64.c")):
    s = open(path).read()
    if "vm_peersec(" in s: continue
    if s.count(OLD) != 1: sys.exit(f"{path}: anchor count {s.count(OLD)}")
    open(path, "w").write(s.replace(OLD, NEW)); n += 1; print("patched", path)
print(n, "file(s) changed")
