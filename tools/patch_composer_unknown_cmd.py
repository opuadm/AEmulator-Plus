#!/usr/bin/env python3
"""Added for AEmulator Plus (2026-11-20). Makes the engine's own composer@2.1-impl.so (engines/n64-*-own) skip a command it does not know.

ColorOS SurfaceFlinger sends a vendor command (opcode 0x0801, 2 payload words) that AOSP's ComposerCommandEngine::executeCommand() rejects.
execute() then calls endCommand(), which does LOG_ALWAYS_FATAL("too little data read") because the payload was not consumed: the
composer@2.1-service aborts, SurfaceFlinger gets DEAD_OBJECT and dies. The patch rewrites the dead "too little data read" abort block into
"mDataRead = mCommandEnd; carry on with the next command" (ignoring the command, like the one that logs "failed to parse command" would).
usage: python3 patch_composer_unknown_cmd.py <impl.so> [...]   (needs: pip install capstone)"""
import struct, sys
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
dis = lambda d, o, n: list(md.disasm(d[o:o + n], o))
for fn in sys.argv[1:]:
    d = bytearray(open(fn, 'rb').read()); hits = 0
    for o in range(0, len(d) - 20, 4):
        ins = dis(d, o, 20)
        if not (len(ins) == 5 and [i.mnemonic for i in ins] == ['cmp', 'b.hi', 'b.lo', 'stp', 'tbnz']
                and ins[3].op_str == 'w9, wzr, [x22, #0x28]' and ins[0].op_str == 'w8, w9'): continue
        blo = int(ins[2].op_str.lstrip('#'), 0); top = int(ins[4].op_str.split('#')[-1], 0)
        if [i.mnemonic for i in dis(d, blo, 24)] != ['adrp', 'adrp', 'add', 'add', 'mov', 'bl']: continue
        new = [struct.pack('<I', 0xB9000000 | (9 << 10) | (22 << 5) | 9),            # str w9, [x22, #0x24]   mDataRead = mCommandEnd
               bytes(d[o + 12:o + 16]),                                                # stp w9, wzr, [x22, #0x28] (normal end of command)
               struct.pack('<I', 0x2A0903E8),                                          # mov w8, w9 (loop top expects w8 = mDataRead)
               struct.pack('<I', 0x14000000 | (((top - (blo + 12)) >> 2) & 0x3ffffff)),  # b <loop top>
               struct.pack('<I', 0xD503201F) * 2]
        d[blo:blo + 24] = b''.join(new); hits += 1
    if hits == 1: open(fn, 'wb').write(d); print(fn, 'patched')
    else: print(fn, 'pattern not found' if not hits else 'ambiguous', file=sys.stderr)
