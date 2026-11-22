"""Runs the two patched instructions of tools/patch_qemu_thumb_odd_pc.py in unicorn (pip install unicorn capstone).

Usage: python3 tools/test_patch_qemu_thumb_odd_pc.py [patched libqemu_kk.so]
The code is read from the patched library itself: for odd and even pc_next, w20 and dc->base.pc_next (x19+0xc) both end up even and
equal to pc_next & ~1, and nothing else (x0, sp, other dc fields) is touched.
"""
import os, struct, sys
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import UC_ARM64_REG_X19, UC_ARM64_REG_X20, UC_ARM64_REG_X0

SITE = 0x82afc
here = os.path.dirname(os.path.abspath(__file__))
lib = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "app/src/main/jniLibs/arm64-v8a/libqemu_kk.so")
code = open(lib, "rb").read()[SITE:SITE + 8]
CODE, DC = 0x10000, 0x20000
bad = 0
for pc_next in (0x40001001, 0x40001000, 0x408545f1, 1, 0xfffffffe, 0xffffffff):
    mu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    mu.mem_map(CODE, 0x1000); mu.mem_map(DC, 0x1000)
    mu.mem_write(CODE, code)
    mu.mem_write(DC, bytes(range(0xc)) + struct.pack("<I", pc_next) + b"\xaa" * 16)
    mu.reg_write(UC_ARM64_REG_X19, DC); mu.reg_write(UC_ARM64_REG_X20, pc_next); mu.reg_write(UC_ARM64_REG_X0, 0x1234)
    mu.emu_start(CODE, CODE + 8)
    want = pc_next & ~1
    got_reg = mu.reg_read(UC_ARM64_REG_X20)
    got_mem = struct.unpack("<I", bytes(mu.mem_read(DC + 0xc, 4)))[0]
    rest = bytes(mu.mem_read(DC, 0xc)) + bytes(mu.mem_read(DC + 0x10, 16))
    ok = got_reg == want and got_mem == want and mu.reg_read(UC_ARM64_REG_X0) == 0x1234 and rest == bytes(range(0xc)) + b"\xaa" * 16
    print("%s pc_next=%#x -> w20=%#x dc.pc_next=%#x" % ("ok  " if ok else "FAIL", pc_next, got_reg, got_mem))
    bad += not ok
sys.exit(1 if bad else 0)
