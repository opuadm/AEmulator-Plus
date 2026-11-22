#!/usr/bin/env python3
"""RAR reader tests: python3 run_rar_tests.py BUILD_DIR   (BUILD_DIR has libaemuarc.so and rar_dump, see run.sh)

Each fixture is read completely with the reader (CRC32 checked inside), in several read patterns: whole chunks, 1-byte chunks,
entries in reverse order and every second entry skipped (the solid-chain logic). Expected outcome per fixture:
  ok      every entry reads and matches its CRC / size
  refuse  arcrar_open says "unsupported" so the importer uses junrar (RAR 1.x/2.x, encryption)
  fail    damaged on purpose: an error, never a crash (checked under AddressSanitizer in run.sh)
Then a seeded fuzz round: corrupted copies of the "ok" fixtures must not crash or hang.
"""
import os, random, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
FX = os.path.join(HERE, "rar")
build = sys.argv[1]
exe = os.path.join(build, "rar_dump")
env = dict(os.environ, ASAN_OPTIONS="detect_leaks=1", UBSAN_OPTIONS="print_stacktrace=1")

EXPECT = {}
for n in os.listdir(FX):
    if not n.endswith(".rar"): continue
    EXPECT[n] = "ok"
for n in ("rarfile-rar3-old.rar", "rarfile-rar15-comment.rar", "libarchive-rar4_encrypted.rar",
          "libarchive-rar5_encrypted.rar"):
    EXPECT[n] = "refuse"
for n in ("libarchive-rar5_bad_tables.rar", "libarchive-rar5_loop_bug.rar", "libarchive-rar5_truncated_huff.rar",
          "libarchive-rar5_different_window_size.rar", "libarchive-rar_invalid1.rar", "libarchive-rar5_distance_overflow.rar",
          "libarchive-rar5_readtables_overflow.rar"):
    EXPECT[n] = "fail"

def run(path, extra=None, timeout=60):
    e = dict(env); e.update(extra or {})
    return subprocess.run([exe, path], capture_output=True, text=True, timeout=timeout, env=e)

def crashed(r):
    return r.returncode not in (0, 1) or "AddressSanitizer" in r.stderr or "runtime error" in r.stderr

fails = 0
for name, want in sorted(EXPECT.items()):
    path = os.path.join(FX, name)
    for mode in ({}, {"CHUNK": "1"}, {"REVERSE": "1"}, {"SKIPODD": "1"}):
        if mode.get("CHUNK") and os.path.getsize(path) > 100000: continue
        r = run(path, mode)
        if crashed(r): print("CRASH", name, mode, r.stderr[:300]); fails += 1; continue
        out = r.stdout
        if want == "ok": good = r.returncode == 0 and "all ok" in out
        elif want == "refuse": good = "open failed: unsupported" in out
        else: good = r.returncode == 1 or "open failed" in out
        if not good: print("FAIL", name, mode, "expected", want, "\n", out[-400:]); fails += 1
print("fixtures: %d, problems so far: %d" % (len(EXPECT), fails))

rnd = random.Random(20261122)
srcs = [os.path.join(FX, n) for n, w in sorted(EXPECT.items()) if w == "ok" and os.path.getsize(os.path.join(FX, n)) < 200000]
tmp = os.path.join(build, "fuzz.rar")
rounds = int(os.environ.get("RAR_FUZZ", "300"))
for i in range(rounds):
    d = bytearray(open(rnd.choice(srcs), "rb").read())
    for _ in range(rnd.choice([1, 1, 2, 5, 20])):
        p = rnd.randrange(len(d)); m = rnd.random()
        if m < .5: d[p] ^= 1 << rnd.randrange(8)
        elif m < .8: d[p] = rnd.randrange(256)
        else: d = d[:max(8, p)]
    open(tmp, "wb").write(d)
    try: r = run(tmp, timeout=30)
    except subprocess.TimeoutExpired: print("HANG on fuzz round", i); fails += 1; open(tmp + ".hang", "wb").write(d); continue
    if crashed(r): print("CRASH on fuzz round", i, r.stderr[:300]); fails += 1; open(tmp + ".crash", "wb").write(d)
print("fuzz rounds: %d" % rounds)
print("RAR TESTS FAILED" if fails else "RAR TESTS OK")
sys.exit(1 if fails else 0)
