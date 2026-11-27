#!/usr/bin/env python3
"""patch_elf.py IN.so OUT.so  -- translate iWMMXt/iWMMXt2 code to NEON (reference implementation)"""
import sys, os, struct, subprocess, tempfile, json, collections
sys.path.insert(0, os.path.dirname(__file__))
from iwt import *
from elftools.elf.elffile import ELFFile

def enc_branch(cc, src, dst, link=False):
    off = (dst - (src + 8)) >> 2
    assert -(1 << 23) <= off < (1 << 23), 'branch out of range'
    return (cc << 28) | 0x0a000000 | (0x01000000 if link else 0) | (off & 0xffffff)

def objdump_span(d, lo, hi):
    t = tempfile.mkdtemp()
    open(t + '/r.bin', 'wb').write(d[lo:hi])
    out = subprocess.run(['arm-linux-gnueabi-objdump', '-D', '-b', 'binary', '-m', 'iwmmxt2', f'--adjust-vma={lo:#x}', t + '/r.bin'],
                         capture_output=True, text=True).stdout
    open(t + '/r.dis', 'w').write(out)
    return parse_dis(t + '/r.dis')

def is_code_sym(e, s):
    try:
        sec = e.get_section(s['st_shndx'])
        return bool(sec['sh_flags'] & 4)
    except Exception:
        return True

def translate(path_in, path_out, verbose=True):
    d = bytearray(open(path_in, 'rb').read())
    orig_tail = bytes(d[-8:])
    e = ELFFile(open(path_in, 'rb'))
    ph = [s for s in e.iter_segments()]
    ds = e.get_section_by_name('.dynsym')
    execseg = [s for s in ph if s['p_type'] == 'PT_LOAD' and s['p_flags'] & 1][0]
    xlo, xhi = execseg['p_vaddr'], execseg['p_vaddr'] + execseg['p_filesz']
    txt = e.get_section_by_name('.text')
    if txt is not None: xlo, xhi = txt['sh_addr'], txt['sh_addr'] + txt['sh_size']
    seeds = sorted({s['st_value'] for s in ds.iter_symbols() if s['st_shndx'] != 'SHN_UNDEF' and s['st_value'] and s['st_info']['type'] in ('STT_FUNC', 'STT_NOTYPE') and is_code_sym(e, s)
                    and not s['st_value'] & 1 and xlo <= s['st_value'] < xhi})
    seen, warn = reach(bytes(d), seeds, xlo, xhi)
    # decode only the span covering code that decodes as iWMMXt (cheap prefilter on reachable words)
    cand = [a for a in sorted(seen) if (lambda w: ((w >> 8) & 0xf) in (0, 1) and (((w >> 24) & 0xe) == 0xe or (w & 0x0e000000) == 0x0c000000))(struct.unpack_from('<I', d, a)[0])]
    if not cand: print('no iWMMXt code found'); return None
    lo, hi = min(cand), max(cand) + 4
    bad = [a for a, why in warn if lo <= a < hi]
    if bad: raise SystemExit('computed jump inside iWMMXt code at %s' % [hex(a) for a in bad[:3]])
    recs = objdump_span(bytes(d), lo, hi)
    byaddr = {r.addr: r for r in recs}
    todo = [r for r in recs if r.addr in seen and is_iw(r)]
    plans = []
    uid = 0
    for r in todo:
        uid += 1
        prev = find_producer(recs, r.idx) if r.base.startswith(('tandc', 'torc')) else None
        try:
            xl = xlate(r, prev, uid)
        except Unsupported as ex:
            raise SystemExit(f'unsupported instruction at {r.addr:#x}: {r!r}: {ex}')
        lits = [t for t in xl if isinstance(t, tuple)]
        ts = [t for t in xl if not isinstance(t, tuple)]
        cond_sfx = CC[r.cc] if r.cc < 14 else ''
        inplace = len(ts) == 1 and not lits and (r.cc in (14, 15) or ts[0].vfp)
        if inplace:
            plans.append((r, 'inplace', [ts[0].text.replace('{c}', cond_sfx)]))
        else:
            lines = [t.text.replace('{c}', '') for t in ts] + lits
            plans.append((r, 'cave', lines))
    # assemble all snippets (dedupe)
    uniq = {}
    for r, kind, lines in plans:
        key = tuple((l if isinstance(l, str) else ('LIT', None, l[2])) for l in lines)
        # literal labels are unique per instruction; normalise to a per-snippet label
        norm = []
        for l in lines:
            if isinstance(l, tuple): norm.append(('LIT', '.LlitX', l[2]))
            else: norm.append(l.replace(f'.Llit{r.idx}', '.LlitX').replace(' .Llit', ' .LlitX') if False else l)
        uniq.setdefault((kind, tuple(norm)), None)
    # fix label naming: translator uses .Llit{uid}; rewrite to .LlitX consistently
    def canon(lines):
        out = []
        for l in lines:
            if isinstance(l, tuple): out.append(('LIT', '.LlitX', l[2]))
            else: out.append(re.sub(r'\.Llit\d+', '.LlitX', l))
        return tuple(out)
    snips = {}; keymap = {}
    for r, kind, lines in plans:
        k = (kind, canon(lines)); keymap[r.addr] = k; snips[k] = list(k[1])
    asm = assemble_snippets(snips)
    # layout caves
    caves = []; cave_off = {}
    blob = bytearray()
    max_end = max(s['p_vaddr'] + s['p_memsz'] for s in ph if s['p_type'] == 'PT_LOAD')
    base_v = (max_end + 0xfff) & ~0xfff
    for k, (b, moff) in asm.items():
        if k[0] == 'cave':
            while len(blob) % 8: blob += b'\0'
            cave_off[k] = (len(blob), moff, len(b)); blob += b
    stats = collections.Counter()
    for r, kind, lines in plans:
        k = keymap[r.addr]; b, moff = asm[k]
        if kind == 'inplace':
            assert len(b[:moff]) == 4, (r, b[:moff].hex())
            d[r.addr:r.addr + 4] = b[:4]; stats['inplace'] += 1
        else:
            off, mo, ln = cave_off[k]
            # each site shares the cave body only if the exit branch can be the same: it cannot (different return).
            stats['cave'] += 1
    # caves are per-site (exit branch differs): instantiate one copy per site
    blob = bytearray(); site_cave = {}
    for r, kind, lines in plans:
        if kind != 'cave': continue
        k = keymap[r.addr]; b, moff = asm[k]
        while len(blob) % 8: blob += b'\0'
        caddr = base_v + len(blob)
        cb = bytearray(b)
        cb[moff:moff + 4] = struct.pack('<I', enc_branch(14, caddr + moff, r.addr + 4))
        blob += cb
        cc = r.cc if r.cc < 14 else 14
        d[r.addr:r.addr + 4] = struct.pack('<I', enc_branch(cc, r.addr, caddr))
    if not blob:                                   # all in place: no cave, no new (empty) PT_LOAD
        open(path_out, 'wb').write(d)
        rep = dict(path_in=path_in, instrs=len(todo), inplace=stats['inplace'], caves=0, cave_bytes=0, segment_vaddr=None, converted_phdr=None)
        if verbose: print(json.dumps(rep))
        return rep
    # new segment: convert PT_GNU_STACK (or PT_NOTE) header into PT_LOAD
    e_phoff, = struct.unpack_from('<I', d, 28); e_phentsize, e_phnum = struct.unpack_from('<HH', d, 42)
    slot = None
    for i in range(e_phnum):
        pt, = struct.unpack_from('<I', d, e_phoff + i * e_phentsize)
        if pt in (0x6474e551, 4): slot = i; break           # GNU_STACK, NOTE
    if slot is None: raise SystemExit('no spare program header to convert')
    while len(d) % 0x1000: d += b'\0'
    foff = len(d); d += blob
    # prelinked libraries end with <load address> + b'PRE ' (read by the 2.x linker from the end of the file): keep it last
    if orig_tail[-4:] == b'PRE ': d += orig_tail
    struct.pack_into('<IIIIIIII', d, e_phoff + slot * e_phentsize, 1, foff, base_v, base_v, len(blob), len(blob), 7, 0x1000)   # R+W+X on purpose: the 2.x linker mprotects the span of all NON-writable PT_LOADs to R+X, which would cover .data/.bss
    open(path_out, 'wb').write(d)
    rep = dict(path_in=path_in, instrs=len(todo), inplace=stats['inplace'], caves=stats['cave'], cave_bytes=len(blob),
               segment_vaddr=base_v, converted_phdr=slot)
    if verbose: print(json.dumps(rep))
    return rep

if __name__ == '__main__':
    translate(sys.argv[1], sys.argv[2])
