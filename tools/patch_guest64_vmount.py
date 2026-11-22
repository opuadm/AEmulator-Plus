#!/usr/bin/env python3
"""Magisk / root emulation, native (n64) engines: real mounts (bind, tmpfs, move, umount) and device nodes (whiteouts).

Wires native/guest64/vmount.h into every native/guest64/<api>/run64.c:

  * xl() becomes xl_x(..., rw) with a wrapper, and consults the mount table (vm_rewrite) for every absolute path;
  * trap2() is renamed trap2_base(); vmount.h defines the new trap2() in front of it (mount, umount2, mknodat of a
    device, getdents64, and the stat/readlink/open/access fix-ups for device nodes);
  * vm_init() runs at start-up (it looks for <root>/dev/.aemu-vmount; without it nothing changes);
  * the seccomp filter also traps getdents64, but only for a VM that has the feature on.

Run from the checkout root; idempotent. Rebuild afterwards (see docs/magisk.md).
"""
import glob, re, sys

INCLUDE = '#include "../vmount.h"'

def sub1(s, old, new, what, path):
    if s.count(old) != 1:
        sys.exit(f"{path}: anchor not found exactly once ({what}: {s.count(old)})")
    return s.replace(old, new)

SETNS = "    f[n++] = JEQ(268 /* setns */, L_TRAP, 0);\n"

def fix_setns(s, path):
    # trapped[] is a u8 table: its "268 /* setns */" entry (tools/patch_guest64_setns.py) was stored as 12, so setns(2) was
    # never trapped and the phone's kernel got it. It is trapped here as a filter entry of its own.
    if SETNS in s:
        return s
    return sub1(s, "    f[n++] = JEQ(179 /* sysinfo */, L_TRAP, 0);\n", "    f[n++] = JEQ(179 /* sysinfo */, L_TRAP, 0);\n" + SETNS, "setns", path)

changed = 0
for path in sorted(glob.glob("native/guest64/[0-9]*/run64.c")):
    s = open(path).read()
    if INCLUDE in s:
        s2 = fix_setns(s, path)
        if s2 != s:
            open(path, "w").write(s2); changed += 1; print("patched (setns)", path)
        continue

    # 1. xl -> xl_x(rw) + wrapper, forward declarations of what vmount.h provides
    s = sub1(s, "static const char *xl(const char *p, char *buf, int *err) {\n",
        "// Added for AEmulator Plus (2026-11-21): mounts and device nodes, see vmount.h. rw = 0 translates without the mount table.\n"
        "static int g_vm;                                            // <root>/dev/.aemu-vmount exists: this VM has real mounts\n"
        "static void vm_rewrite(char *n, u64 cap);\n"
        "static const char *xl_x(const char *p, char *buf, int *err, int rw);\n"
        "static const char *xl(const char *p, char *buf, int *err) { return xl_x(p, buf, err, 1); }\n"
        "static const char *xl_x(const char *p, char *buf, int *err, int rw) {\n", "xl", path)

    # 2. the mount table is consulted on the normalised path
    proc = '    if ((pre(n, "/proc") && (n[5] == \'/\' || !n[5])) || (pre(n, "/sys") && (n[4] == \'/\' || !n[4]))) {\n'
    s = sub1(s, proc, "    if (rw && g_vm) vm_rewrite(n, PATH_MAX - g_rootlen);       // what is mounted at this path (vmount.h)\n" + proc, "xl proc line", path)

    # 3. trap2 -> trap2_base, vmount.h in front of it (after PB(), P(), pb_get, fake_*, xl, unxl)
    t2 = "static long trap2(long nr, long *a, void *uc, char **pbp) {\n"
    i_pb, i_t2 = s.find("#define PB(i)"), s.find(t2)
    if i_pb < 0 or i_t2 < 0 or i_pb > i_t2:
        sys.exit(f"{path}: PB() must be defined before trap2")
    s = sub1(s, t2, INCLUDE + "\n\n" + "static long trap2_base(long nr, long *a, void *uc, char **pbp) {\n", "trap2", path)

    # 4. start-up
    s = sub1(s, "memcpy(g_root, r, slen(r) + 1); g_rootlen = slen(g_root);\n",
             "memcpy(g_root, r, slen(r) + 1); g_rootlen = slen(g_root);\n    vm_init();                                                   // (vmount.h)\n", "aemu_main", path)

    # 5. filter: getdents64 only when the feature is on; and the filter must still fit
    s = sub1(s, "    f[n++] = JEQ(179 /* sysinfo */, L_TRAP, 0);\n",
             "    f[n++] = JEQ(179 /* sysinfo */, L_TRAP, 0);\n"
             "    // Added for AEmulator Plus (2026-11-21): directory reads, to show a device node (a whiteout) as one (vmount.h)\n"
             "    if (g_vm) f[n++] = JEQ(61 /* getdents64 */, L_TRAP, 0);\n", "filter", path)
    s = sub1(s, "#undef AT\n",
             "#undef AT\n    if (n >= (int)(sizeof f / sizeof f[0]) || n > 250) die(\"seccomp filter too long\", \"\");     // (jump offsets are bytes)\n", "filter end", path)

    s = fix_setns(s, path)
    open(path, "w").write(s)
    changed += 1
    print("patched", path)
print(f"{changed} file(s) changed")
