#!/usr/bin/env python3
"""Magisk support, native (n64) engines: make setns(2) succeed as a no-op, like unshare/mount already do.

Magisk's su daemon enters the requester's mount namespace (setns on /proc/<pid>/ns/mnt) before it execs the root
shell. A guest has no mount namespaces of its own (mount/umount2/unshare are accepted and ignored), and the phone's
app seccomp policy would kill or refuse the real call. Run from the checkout root; idempotent.
"""
import glob, re, sys

changed = 0
for path in sorted(glob.glob("native/guest64/[0-9]*/run64.c")):
    s = open(path).read()
    o = s
    if "268 /* setns */" not in s:
        s, n1 = re.subn(r"(case NR_setgroups: case NR_unshare:) return 0;",
                        r"\1 case 268 /* setns */: return 0;", s, count=1)
        s, n2 = re.subn(r"(NR_setsockopt, NR_unshare,) (NR_timerfd_create,)",
                        r"\1 268 /* setns */, \2", s, count=1)
        if n1 != 1 or n2 != 1:
            sys.exit(f"{path}: anchors not found (dispatch={n1}, trap list={n2})")
    if s != o:
        open(path, "w").write(s); changed += 1
        print("patched", path)
print(f"{changed} file(s) changed")
