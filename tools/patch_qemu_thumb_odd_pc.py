"""Patches libqemu_kk.so / libqemu_kk_xperiaplay.so (QEMU 8.x arm linux-user) so that a Thumb translation starting at an odd PC no
longer kills the guest process.

Symptom (run/zygote.log of a diag export, Android 5.0.1 / API 21 image, engine KK):
    Assertion failed: (dc->base.pc_next & 1) == 0 (../target/arm/tcg/translate.c: thumb_tr_translate_insn: 9428)
    *** SIGABRT ... thread RenderThread   (libhwui.so -> libGLES_bridge.so)
The translator's assert hook turns the failed assertion into a guest abort(); when it is system_server's RenderThread the whole
system_server goes, zygote follows (exit 137), glserverd loses its client and SurfaceFlinger aborts (exit 134).

Why odd: a Thumb TB is keyed by env->regs[15], and a guest branch that reaches Thumb code with the interworking bit still set (a
function-pointer call or return that real hardware masks) leaves bit 0 set. Real ARM ignores bit 0 of a Thumb PC (BranchWritePC), so the
only correct thing to do with the address is to clear it.

What: thumb_tr_translate_insn (0x82ad4) starts with
    82af0  ldr  w20, [x0, #0xc]          ; w20 = dc->base.pc_next
    82afc  mov  x0, #0                    ; clears the stack-protector copy, dead: x0 is reloaded at 0x82b04
    82b00  tbnz w20, #0, <assert block>   ; the assertion
and later code re-reads dc->base.pc_next from memory (0x82c10, 0x82d1c, ...), so clearing the register is not enough. The two
instructions become
    82afc  and  w20, w20, #0xfffffffe
    82b00  str  w20, [x19, #0xc]          ; dc is in x19; no flags are involved, no branch targets either of the two
The assert block (0x842dc) is left in place, unreachable. For an even PC the store writes back the value just loaded.

Not fixed: *why* the guest gets there. This only makes the translator do what the hardware does.
Run from the checkout root; the site is checked against the expected original bytes first. Idempotent.
Usage: python3 tools/patch_qemu_thumb_odd_pc.py [lib ...]   (default: libqemu_kk.so libqemu_kk_xperiaplay.so)
"""
import struct, sys
D = "app/src/main/jniLibs/arm64-v8a/"
SITE = 0x82afc                      # mov x0,#0 ; tbnz w20,#0
MOV_X0_0, TBNZ_W20_0 = 0xd2800000, 0x3700bef4
AND_W20, STR_W20 = 0x121f7a94, 0xb9000e74   # and w20,w20,#0xfffffffe ; str w20,[x19,#0xc]
LDR_W20 = 0xb9400c14                # ldr w20,[x0,#0xc] at SITE-12
ASSERT_TEXT = b"(dc->base.pc_next & 1) == 0\0"

def vaddr_to_off(d, va):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, phoff + i * phsz)
        if t == 1 and vad <= va < vad + filesz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

def patch(path):
    d = bytearray(open(path, "rb").read())
    o = vaddr_to_off(d, SITE)
    cur = struct.unpack_from("<II", d, o)
    if cur == (AND_W20, STR_W20):
        print(path + ": already patched"); return
    assert cur == (MOV_X0_0, TBNZ_W20_0), "%s: unexpected instructions %#x %#x at %#x (another build of the translator?)" % (path, cur[0], cur[1], SITE)
    assert struct.unpack_from("<I", d, o - 12)[0] == LDR_W20, path + ": ldr w20,[x0,#0xc] not found before the site"
    assert ASSERT_TEXT in d, path + ": assertion text not found"
    struct.pack_into("<II", d, o, AND_W20, STR_W20)
    open(path, "wb").write(d)
    print("%s: patched at %#x (Thumb pc_next bit 0 is cleared instead of asserted)" % (path, SITE))

for p in (sys.argv[1:] or [D + "libqemu_kk.so", D + "libqemu_kk_xperiaplay.so"]):
    patch(p)
