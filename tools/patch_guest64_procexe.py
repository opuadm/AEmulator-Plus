#!/usr/bin/env python3
"""Magisk / root emulation, native (n64) engines: stat/open/readlink of "/proc/<pid>/exe" of ANOTHER guest process.

Magisk's daemon only serves a non-root client (an app asking for su) when stat("/proc/<client pid>/exe") is the same file as
its own stat("/proc/self/exe") (daemon.rs is_client). run64.c redirected only "/proc/self/exe" to the guest program, so the
client's answer was the shim itself and every app's su ended in "Accessed denied" (policies never got looked at).
vmount.h now has vm_exe_put() (a process publishes its guest program in <root>/dev/.aemu-exe/<pid>) and vm_proc_exe();
this script calls them from every native/guest64/<api>/run64.c:

  * after g_exe is known (just before the AEMU64_SOCK_ setup): vm_exe_put();
  * next to every "/proc/self/exe" redirect of openat / newfstatat / statx: the same for "/proc/<pid>/exe";
  * readlinkat of "/proc/<pid>/exe" answers the published path.

Run from the checkout root after tools/patch_guest64_vmount.py; idempotent. Rebuild afterwards (see docs/magisk.md).
"""
import glob, sys

MARK = "vm_proc_exe("

def sub1(s, old, new, what, path):
    if s.count(old) != 1:
        sys.exit(f"{path}: anchor not found exactly once ({what}: {s.count(old)})")
    return s.replace(old, new)

REDIRECT = '            if (!g_is32 && g_exe[0] && seq(P(1), "/proc/self/exe")) { const char *h = xl(g_exe, b1, &err); if (h) return S4(nr, AT_FDCWD, h, a[2], a[3]); }\n'
EXTRA = ('            // Added for AEmulator Plus (2026-11-21): the same for another process (Magisk: is_client), see vmount.h\n'
         '            if (g_vm && !g_is32) { char *ex = PB(1); if (vm_proc_exe(P(1), ex)) { const char *h = xl(ex, b1, &err); if (h) return S4(nr, AT_FDCWD, h, a[2], a[3]); } }\n')
RL_OLD = '            if (seq(P(1), "/proc/self/exe") && g_exe[0]) {\n'
RL_NEW = ('            if (g_vm) {      // Added for AEmulator Plus (2026-11-21): /proc/<pid>/exe of another guest process, see vmount.h\n'
          '                char *ex = PB(1);\n'
          '                if (vm_proc_exe(P(1), ex)) { u64 n = slen(ex); if (n > (u64)a[3]) n = (u64)a[3]; memcpy((void *)a[2], ex, n); return (long)n; }\n'
          '            }\n')
SOCK = "    // sockets init would have made for the service: AEMU64_SOCK_<name>=<host path>,<octal mode>"

changed = 0
for path in sorted(glob.glob("native/guest64/[0-9]*/run64.c")):
    s = open(path).read()
    if MARK in s:
        continue
    if '#include "../vmount.h"' not in s:
        sys.exit(f"{path}: run tools/patch_guest64_vmount.py first")
    n = s.count(REDIRECT)
    s = s.replace(REDIRECT, EXTRA + REDIRECT)      # (the oldest engines have no open/stat redirect: readlink only)
    s = sub1(s, RL_OLD, RL_NEW + RL_OLD, "readlinkat", path)
    s = sub1(s, SOCK, "    vm_exe_put();    // Added for AEmulator Plus (2026-11-21): /proc/<pid>/exe of this process for the others (vmount.h)\n" + SOCK, "g_exe known", path)   # (the line goes on after the anchor: only its start is matched)
    open(path, "w").write(s); changed += 1; print("patched", path, f"({n} redirect(s))")
print(f"{changed} file(s) changed")
