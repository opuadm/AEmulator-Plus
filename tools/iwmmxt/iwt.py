#!/usr/bin/env python3
"""iWMMXt/iWMMXt2 -> ARMv7 NEON static translator (prototype).

Register model
  wR0..wR15  -> d16..d31   (caller-saved in AAPCS, so clobbering is legal)
  wCGR0..3   -> d4..d7     (shadow holds *negated* count, 16-bit lanes, so
                            right shifts by wCGR are a single VSHL)
  d0..d3     -> scratch
Instructions that become exactly one VFP-class instruction are patched in
place (those can carry a condition code); everything else becomes a code cave
reached with a (conditional) branch and left with a branch back.
"""
import re, struct, subprocess, os, tempfile, collections

CC = ['eq','ne','cs','cc','mi','pl','vs','vc','hi','ls','ge','lt','gt','le','','']
W = lambda n: f"d{16+int(n)}"
G = lambda k: f"d{4+int(k)}"
REGS = {'ip':12,'sp':13,'lr':14,'pc':15,'fp':11,'sl':10,'sb':9}

class Unsupported(Exception): pass

class Rec:
    __slots__ = ('addr','word','base','cc','ops','idx')
    def __repr__(self): return f"{self.addr:#x}:{self.base}{CC[self.cc] if self.cc<14 else ''} {self.ops}"

def regnum(s):
    s = s.strip()
    if s in REGS: return REGS[s]
    m = re.fullmatch(r'r(\d+)', s)
    if m: return int(m.group(1))
    raise Unsupported('reg '+s)

def parse_dis(path):
    out = []
    for l in open(path):
        m = re.match(r'\s+([0-9a-f]+):\s+([0-9a-f]{8})\s+(\S+)\s*(.*)', l)
        if not m: continue
        r = Rec(); r.addr = int(m.group(1), 16); r.word = int(m.group(2), 16)
        mn = m.group(3); r.ops = m.group(4).split('@')[0].strip(); r.cc = r.word >> 28
        r.base = mn
        if r.cc < 14 and mn.endswith(CC[r.cc]) and mn[:-2].startswith(('w','t','mar','mra')):
            r.base = mn[:-2]
        # XScale DSP names for wR0: acc0 *is* wR0 in iWMMXt  (mar == tmcrr wr0,..  mra == tmrrc ..,wr0)
        if r.base in ('mar', 'mra'):
            r.base = 'tmcrr' if r.base == 'mar' else 'tmrrc'
            r.ops = r.ops.replace('acc0', 'wr0')
        r.idx = len(out); out.append(r)
    return out

def is_iw(r):
    w = r.word
    cp = (w >> 8) & 0xf
    if cp not in (0, 1): return False
    if not r.base.startswith(('w', 't')): return False
    if r.base in ('teq','tst'): return False
    return ((w >> 24) & 0xe) == 0xe or (w & 0x0e000000) == 0x0c000000

# ---------------------------------------------------------------- helpers
class T:
    """one emitted line: text, vfp-class (cond capable)"""
    def __init__(s, text, vfp=False): s.text, s.vfp = text, vfp

def split_ops(ops): return [o.strip() for o in re.split(r',\s*(?![^\[]*\])', ops)]

def mem_parse(op_str):
    # wr0, [r1], #4 | [r1, #8]! | [r1]
    m = re.fullmatch(r'wr(\d+),\s*\[(\w+)(?:,\s*#(-?\d+))?\](!)?(?:,\s*#(-?\d+))?', op_str)
    if not m: raise Unsupported('mem '+op_str)
    wr, base, off, bang, post = m.groups()
    if post is not None: return int(wr), base, int(post), 'post'
    if bang: return int(wr), base, int(off), 'pre'
    return int(wr), base, int(off or 0), 'off'

SIZE = {'b': 8, 'h': 16, 'w': 32, 'd': 64}

def xlate_mem(r, uid):
    kind = r.base[:5]          # wldrd wstrd wldrw ...
    sz = r.base[4:5] if False else r.base[-1]
    ld = r.base.startswith('wldr')
    wr, base, off, mode = mem_parse(r.ops)
    D = W(wr); R = base
    if base == 'pc': raise Unsupported('pc-relative wldr/wstr')
    if sz == 'd':
        if mode == 'off' and off % 4 == 0 and -1020 <= off <= 1020:
            op = 'vldr' if ld else 'vstr'
            return [T(f"{op}{{c}} {D}, [{R}{', #%d' % off if off else ''}]", True)]
        if mode == 'post' and off == 8:
            return [T(f"{'vldmia' if ld else 'vstmia'}{{c}} {R}!, {{{D}}}", True)]
        if mode == 'pre' and off == -8 and not ld:
            return [T(f"vstmdb{{c}} {R}!, {{{D}}}", True)]
        if mode == 'post':
            op = 'vldr' if ld else 'vstr'
            adj = f"add {R}, {R}, #{off}" if off >= 0 else f"sub {R}, {R}, #{-off}"
            return [T(f"{op} {D}, [{R}]"), T(adj)]
        if mode == 'pre':
            op = 'vldr' if ld else 'vstr'
            adj = f"add {R}, {R}, #{off}" if off >= 0 else f"sub {R}, {R}, #{-off}"
            return [T(adj), T(f"{op} {D}, [{R}]")]
        raise Unsupported('wldrd form ' + r.ops)
    # 16/32-bit lane transfers
    n = {'w': 32, 'h': 16, 'b': 8}[sz]
    lane = f"{{{D}[0]}}"
    out = []
    if ld: out.append(T(f"veor {D}, {D}, {D}"))
    if mode == 'off' and off:
        out.append(T(f"add {R}, {R}, #{off}" if off > 0 else f"sub {R}, {R}, #{-off}"))
    if mode == 'pre':
        out.append(T(f"add {R}, {R}, #{off}" if off > 0 else f"sub {R}, {R}, #{-off}"))
    if mode == 'post' and off == n // 8:
        out.append(T(f"{'vld1' if ld else 'vst1'}.{n} {lane}, [{R}]!"))
    else:
        out.append(T(f"{'vld1' if ld else 'vst1'}.{n} {lane}, [{R}]"))
        if mode == 'post':
            out.append(T(f"add {R}, {R}, #{off}" if off > 0 else f"sub {R}, {R}, #{-off}"))
    if mode == 'off' and off:
        out.append(T(f"sub {R}, {R}, #{off}" if off > 0 else f"add {R}, {R}, #{-off}"))
    return out

# flag layouts of producers that precede TANDC/TORC
FLAG_LAYOUT = {
    'wunpckihw': 32, 'wunpckilw': 32, 'wxor': 64, 'wand': 64, 'wor': 64, 'wandn': 64,
    'wsubh': 16, 'waddh': 16, 'wsllh': 16, 'wsrlh': 16, 'wunpckelub': 16, 'wunpckehub': 16,
    'wsradg': 64,
}

def flag_lines(layout, X, comb, tmp):
    mm = 'vpmin' if comb == 'and' else 'vpmax'
    L = []
    if layout == 32:
        L += [f"vshr.u32 d0, {X}, #31", f"vceq.i32 d1, {X}, #0", "vshr.u32 d1, d1, #31",
              f"{mm}.u32 d0, d0, d0", f"{mm}.u32 d1, d1, d1",
              "vshl.i32 d0, d0, #31", "vshl.i32 d1, d1, #30", "vorr d0, d0, d1",
              f"vmov.32 {tmp}, d0[0]"]
    elif layout == 64:
        if comb == 'and': raise Unsupported('tandcw after 64-bit producer')
        L += [f"vshr.u64 d0, {X}, #63", "vshl.i32 d0, d0, #31",
              f"vceq.i32 d1, {X}, #0", "vpmin.u32 d1, d1, d1", "vshr.u32 d1, d1, #31",
              "vshl.i32 d1, d1, #30", "vorr d0, d0, d1", f"vmov.32 {tmp}, d0[0]"]
    elif layout == 16:
        L += [f"vshr.u16 d0, {X}, #15", f"vceq.i16 d1, {X}, #0", "vshr.u16 d1, d1, #15",
              f"{mm}.u16 d0, d0, d0", f"{mm}.u16 d0, d0, d0",
              f"{mm}.u16 d1, d1, d1", f"{mm}.u16 d1, d1, d1",
              "vshl.i16 d0, d0, #15", "vshl.i16 d1, d1, #14", "vorr d0, d0, d1",
              f"vmov.u16 {tmp}, d0[0]", f"lsl {tmp}, {tmp}, #16"]
    else:
        raise Unsupported('layout')
    return L + [f"msr APSR_nzcvq, {tmp}"]

def xlate(r, prev=None, uid=0):
    """returns list[T]"""
    b, ops = r.base, r.ops
    o = split_ops(ops) if ops else []
    wnum = lambda s: int(re.fullmatch(r'wr(\d+)', s).group(1))
    # ----- loads / stores
    if b.startswith(('wldr', 'wstr')): return xlate_mem(r, uid)
    # ----- logical
    m = re.fullmatch(r'w(and|or|xor|andn)', b)
    if m:
        op = {'and': 'vand', 'or': 'vorr', 'xor': 'veor', 'andn': 'vbic'}[m.group(1)]
        d, n, mm = map(wnum, o)
        return [T(f"{op} {W(d)}, {W(n)}, {W(mm)}")]
    # ----- add / sub
    m = re.fullmatch(r'w(add|sub)([bhw])(us|ss|)', b)
    if m:
        d, n, mm = map(wnum, o); s = SIZE[m.group(2)]
        if m.group(3) == '': op = f"v{m.group(1)}.i{s}"
        else: op = f"vq{m.group(1)}.{'u' if m.group(3)=='us' else 's'}{s}"
        return [T(f"{op} {W(d)}, {W(n)}, {W(mm)}")]
    # ----- multiply
    if b == 'wmulwl':
        d, n, mm = map(wnum, o); return [T(f"vmul.i32 {W(d)}, {W(n)}, {W(mm)}")]
    m = re.fullmatch(r'wmulw([us])m(r?)', b)        # high 32 bits of each 32x32 product (R: rounded)
    if m:
        d, n, mm = map(wnum, o)
        sh = 'vrshrn.i64' if m.group(2) else 'vshrn.i64'
        return [T(f"vmull.{m.group(1)}32 q0, {W(n)}, {W(mm)}"), T(f"{sh} {W(d)}, q0, #32")]
    m = re.fullmatch(r'wmul([us])([lm])(r?)', b)
    if m:
        d, n, mm = map(wnum, o)
        if m.group(2) == 'l': return [T(f"vmul.i16 {W(d)}, {W(n)}, {W(mm)}")]
        sg = m.group(1)
        sh = 'vrshrn.i32' if m.group(3) else 'vshrn.i32'
        return [T(f"vmull.{sg}16 q0, {W(n)}, {W(mm)}"), T(f"{sh} {W(d)}, q0, #16")]
    m = re.fullmatch(r'wmadd([us])x?', b)
    if m:
        d, n, mm = map(wnum, o)
        return [T(f"vmull.{m.group(1)}16 q0, {W(n)}, {W(mm)}"), T(f"vpadd.i32 {W(d)}, d0, d1")]
    m = re.fullmatch(r'wmac([us])(z?)', b)
    if m:
        d, n, mm = map(wnum, o); sg = m.group(1)
        L = [T(f"vmull.{sg}16 q0, {W(n)}, {W(mm)}"), T(f"vpaddl.{sg}32 q0, q0")]
        if m.group(2): L.append(T(f"vadd.i64 {W(d)}, d0, d1"))
        else: L += [T("vadd.i64 d0, d0, d1"), T(f"vadd.i64 {W(d)}, {W(d)}, d0")]
        return L
    # ----- shuffle
    if b == 'wshufh':
        d, n = wnum(o[0]), wnum(o[1]); imm = int(o[2].lstrip('#'))
        idx = []
        for i in range(4):
            s = (imm >> (2 * i)) & 3; idx += [2 * s, 2 * s + 1]
        lit = '0x' + ''.join('%02x' % x for x in reversed(idx))
        return [T(f"vldr d0, .Llit{uid}"), T(f"vtbl.8 {W(d)}, {{{W(n)}}}, d0"),
                ('LIT', f".Llit{uid}", lit)]
    # ----- unpack
    m = re.fullmatch(r'wunpck(e|i)(l|h)([us]?)([bhw])', b)   # wunpckel{u,s}{b,h,w}: zero- or sign-extending
    if m:
        ei, lh, u, sz = m.groups(); s = SIZE[sz]
        if ei == 'e':
            d, n = map(wnum, o)
            sg = 'u' if u == 'u' else 's'
            L = [T(f"vmovl.{sg}{s} q0, {W(n)}"), T(f"vmov {W(d)}, {'d0' if lh=='l' else 'd1'}")]
            return L
        d, n, mm = map(wnum, o)
        return [T(f"vmov d0, {W(n)}"), T(f"vmov d1, {W(mm)}"), T(f"vzip.{s} d0, d1"),
                T(f"vmov {W(d)}, {'d0' if lh=='l' else 'd1'}")]
    # ----- pack
    m = re.fullmatch(r'wpack([hwd])(us|ss)', b)
    if m:
        d, n, mm = map(wnum, o)
        src = {'h': 's16', 'w': 's32', 'd': 's64'}[m.group(1)]
        op = 'vqmovun' if m.group(2) == 'us' else 'vqmovn'
        return [T(f"vmov d0, {W(n)}"), T(f"vmov d1, {W(mm)}"), T(f"{op}.{src} {W(d)}, q0")]
    # ----- shifts: immediate (iWMMXt2) and wCGR forms
    m = re.fullmatch(r'w(srl|sll|sra|ror)([hwd])', b)
    if m and len(o) == 3 and o[2].startswith('#'):
        kind, sz = m.groups(); s = SIZE[sz]
        d, n = wnum(o[0]), wnum(o[1]); imm = int(o[2][1:])
        D_, N_ = W(d), W(n)
        if kind == 'ror':
            imm %= s
            if imm == 0: return [T(f"vmov {D_}, {N_}")]
            return [T(f"vshr.u{s} d0, {N_}, #{imm}"), T(f"vsli.{s} d0, {N_}, #{s-imm}"),
                    T(f"vmov {D_}, d0")]
        if imm == 0: return [T(f"vmov {D_}, {N_}")]
        if kind == 'srl':
            if imm >= s: return [T(f"vmov.i32 {D_}, #0")]
            return [T(f"vshr.u{s} {D_}, {N_}, #{imm}")]
        if kind == 'sra':
            return [T(f"vshr.s{s} {D_}, {N_}, #{min(imm, s)}")]
        if imm >= s: return [T(f"vmov.i32 {D_}, #0")]
        return [T(f"vshl.i{s} {D_}, {N_}, #{imm}")]
    m = re.fullmatch(r'w(srl|sll|sra)([hwd])', b)          # shift count in the low bits of a data register (wRm)
    if m and len(o) == 3 and o[2].startswith('wr'):
        kind, sz = m.groups(); s = SIZE[sz]
        d, n, mm = map(wnum, o)
        if kind == 'sll':
            cnt = [T(f"vdup.{s} d0, {W(mm)}[0]")] if s < 64 else [T(f"vmov d0, {W(mm)}")]
        elif s < 64:
            cnt = [T(f"vdup.{s} d0, {W(mm)}[0]"), T(f"vneg.s{s} d0, d0")]
        else:
            cnt = [T("vmov.i32 d1, #0"), T(f"vsub.i64 d0, d1, {W(mm)}")]
        return cnt + [T(f"vshl.{'s' if kind == 'sra' else 'u'}{s} {W(d)}, {W(n)}, d0")]
    m = re.fullmatch(r'w(srl|sll|sra)([hwd])g', b)
    if m:
        kind, sz = m.groups(); s = SIZE[sz]
        d, n = wnum(o[0]), wnum(o[1]); k = int(re.fullmatch(r'wcgr(\d)', o[2]).group(1))
        if kind == 'srl': return [T(f"vshl.u{s} {W(d)}, {W(n)}, {G(k)}")]
        if kind == 'sra': return [T(f"vshl.s{s} {W(d)}, {W(n)}, {G(k)}")]
        return [T(f"vneg.s16 d0, {G(k)}"), T(f"vshl.u{s} {W(d)}, {W(n)}, d0")]
    # ----- min / max / compare
    m = re.fullmatch(r'w(min|max)([us])([bhw])', b)
    if m:
        d, n, mm = map(wnum, o)
        return [T(f"v{m.group(1)}.{m.group(2)}{SIZE[m.group(3)]} {W(d)}, {W(n)}, {W(mm)}")]
    m = re.fullmatch(r'wcmpgt([us])([bhw])', b)
    if m:
        d, n, mm = map(wnum, o)
        return [T(f"vcgt.{m.group(1)}{SIZE[m.group(2)]} {W(d)}, {W(n)}, {W(mm)}")]
    m = re.fullmatch(r'wcmpeq([bhw])', b)
    if m:
        d, n, mm = map(wnum, o)
        return [T(f"vceq.i{SIZE[m.group(1)]} {W(d)}, {W(n)}, {W(mm)}")]
    # ----- align / merge
    m = re.fullmatch(r'walignr([0-3])', b)
    if m:
        k = int(m.group(1)); d, n, mm = map(wnum, o)
        return [T(f"vneg.s16 d0, {G(k)}"), T("vshl.i16 d0, d0, #3"), T("vmov.i16 d1, #0x38"),
                T("vand d0, d0, d1"),                        # d0 = 8*(count&7)
                T("vneg.s16 d1, d0"), T(f"vshl.u64 d2, {W(n)}, d1"),
                T("vmov.i16 d3, #64"), T("vsub.i16 d3, d3, d0"),
                T(f"vshl.u64 d3, {W(mm)}, d3"), T(f"vorr {W(d)}, d2, d3")]
    if b == 'waligni':
        d, n, mm = wnum(o[0]), wnum(o[1]), wnum(o[2]); imm = int(o[3][1:])
        if imm == 0: return [T(f"vmov {W(d)}, {W(n)}")]
        return [T(f"vext.8 {W(d)}, {W(n)}, {W(mm)}, #{imm}")]
    if b == 'wmerge':
        d, n, mm = wnum(o[0]), wnum(o[1]), wnum(o[2]); imm = int(o[3][1:]); sh = 8 * imm
        if imm == 0: return [T(f"vmov {W(d)}, {W(mm)}")]
        if d == n: return [T(f"vsli.64 {W(d)}, {W(mm)}, #{sh}")]
        return [T(f"vmov d0, {W(n)}"), T(f"vsli.64 d0, {W(mm)}, #{sh}"), T(f"vmov {W(d)}, d0")]
    # ----- transfers
    m = re.fullmatch(r'tbcst([bhw])', b)
    if m: return [T(f"vdup{{c}}.{SIZE[m.group(1)]} {W(wnum(o[0]))}, {o[1]}", True)]
    m = re.fullmatch(r'textrm([us])([bhw])', b)
    if m:
        s = SIZE[m.group(2)]; i = int(o[2][1:])
        ty = '32' if s == 32 else f"{m.group(1)}{s}"
        return [T(f"vmov{{c}}.{ty} {o[0]}, {W(wnum(o[1]))}[{i}]", True)]
    m = re.fullmatch(r'tinsr([bhw])', b)
    if m:
        i = int(o[2][1:]); return [T(f"vmov{{c}}.{SIZE[m.group(1)]} {W(wnum(o[0]))}[{i}], {o[1]}", True)]
    if b == 'tmcrr': return [T(f"vmov{{c}} {W(wnum(o[0]))}, {o[1]}, {o[2]}", True)]
    if b == 'tmrrc': return [T(f"vmov{{c}} {o[0]}, {o[1]}, {W(wnum(o[2]))}", True)]
    if b == 'tmcr':
        k = int(re.fullmatch(r'wcgr(\d)', o[0]).group(1))
        return [T(f"vdup.16 {G(k)}, {o[1]}"), T(f"vneg.s16 {G(k)}, {G(k)}")]
    if b == 'tmrc':
        k = int(re.fullmatch(r'wcgr(\d)', o[1]).group(1))
        return [T(f"vneg.s16 d0, {G(k)}"), T(f"vmov.u16 {o[0]}, d0[0]")]
    # ----- SIMD flag consumers (fused with the producer just before them)
    m = re.fullmatch(r't(and|or)c([hw])', b)
    if m:
        if o != ['pc']: raise Unsupported('tandc/torc operand')
        if prev is None or prev.base not in FLAG_LAYOUT: raise Unsupported('flag producer')
        layout = FLAG_LAYOUT[prev.base]
        pd = split_ops(prev.ops)[0]
        X = W(wnum(pd))
        tmp = 'r12'
        comb = 'and' if m.group(1) == 'and' else 'or'
        body = flag_lines(layout, X, comb, tmp)
        return [T(f"str {tmp}, [sp, #-4]!")] + [T(x) for x in body] + [T(f"ldr {tmp}, [sp], #4")]
    raise Unsupported('mnemonic ' + b)

# ---------------------------------------------------------------- assemble
def assemble_snippets(snips):
    """snips: dict key -> list of asm text lines (or ('LIT', label, hex)).
    returns key -> bytes (body, with 4-byte placeholder marker `b .` at the
    position given by marker offset, then literal)."""
    keys = list(snips)
    src = ['.syntax unified', '.arch armv7-a', '.fpu neon', '.arm', '.text']
    for i, k in enumerate(keys):
        src.append('.balign 4'); src.append(f'S{i}:')
        lits = []
        for ln in snips[k]:
            if isinstance(ln, tuple): lits.append(ln)
            else: src.append('  ' + ln.replace('.LlitX', f'.LlitX{i}'))
        src.append(f'M{i}:'); src.append('  .word 0xEAFFFFFE')   # patched to "b back"
        for _, lab, val in lits:
            src.append('.balign 4'); src.append(f'{lab.replace(".LlitX", f".LlitX{i}")}:'); src.append(f'  .quad {val}')
        src.append(f'E{i}:')
    d = tempfile.mkdtemp()
    s = os.path.join(d, 'a.s'); o = os.path.join(d, 'a.o'); bn = os.path.join(d, 'a.bin')
    open(s, 'w').write('\n'.join(src) + '\n')
    p = subprocess.run(['arm-linux-gnueabi-as', '-march=armv7-a', '-mfpu=neon', '-o', o, s],
                       capture_output=True, text=True)
    if p.returncode: raise RuntimeError(p.stderr[:2000])
    subprocess.check_call(['arm-linux-gnueabi-objcopy', '-O', 'binary', '-j', '.text', o, bn])
    nm = subprocess.run(['arm-linux-gnueabi-nm', o], capture_output=True, text=True).stdout
    sym = {}
    for ln in nm.split('\n'):
        p_ = ln.split()
        if len(p_) == 3: sym[p_[2]] = int(p_[0], 16)
    blob = open(bn, 'rb').read()
    res = {}
    for i, k in enumerate(keys):
        a, m_, e = sym[f'S{i}'], sym[f'M{i}'], sym[f'E{i}']
        res[k] = (blob[a:e], m_ - a)
    return res

# ---------------------------------------------------------------- analysis helpers
DATA_NONPROD = ('wldr', 'wstr', 'tmcr', 'tmrc', 'tmrrc', 'tmcrr', 'tbcst', 'textrm', 'tinsr', 'tandc', 'torc')

def find_producer(recs, i, maxback=3):
    """nearest previous iWMMXt data-op (flag producer) within a few instructions"""
    for k in range(i - 1, max(-1, i - 1 - maxback), -1):
        r = recs[k]
        if r.addr != recs[i].addr - 4 * (i - k): break      # not contiguous
        if is_iw(r) and not r.base.startswith(DATA_NONPROD): return r
    return None

def arm_flow(w, addr):
    """returns (targets, falls_through, unsupported_reason)"""
    cc = w >> 28
    if cc != 0xf and (w & 0x0e000000) == 0x0a000000:
        off = w & 0xffffff
        if off & 0x800000: off -= 1 << 24
        t = addr + 8 + (off << 2)
        link = bool(w & 0x01000000)
        if link: return [t], True, None
        return [t], cc != 0xe, None
    if cc == 0xf and (w & 0xfe000000) == 0xfa000000:      # blx imm
        off = w & 0xffffff
        if off & 0x800000: off -= 1 << 24
        return [], True, None          # blx imm switches to Thumb: not ARM code
    if (w & 0x0ffffff0) in (0x012fff10, 0x012fff30): return [], (cc != 0xe) or (w & 0x20) != 0, None   # bx / blx reg
    rd = (w >> 12) & 0xf
    # writes to pc via data processing / ldr
    if cc != 0xf and (w & 0x0c000000) == 0x00000000 and rd == 15 and (w & 0x01900000) != 0x01000000:
        if (w & 0x0fffffff) == 0x01a0f00e or (w & 0x0ffff000) == 0x01a0f000 and (w & 0xff0) == 0: return [], cc != 0xe, None   # mov pc, rX
        return [], cc != 0xe, 'computed jump (dataproc pc)'
    if cc != 0xf and (w & 0x0c100000) == 0x04100000 and rd == 15: return [], cc != 0xe, 'ldr pc'
    if cc != 0xf and (w & 0x0e108000) == 0x08108000: return [], cc != 0xe, None     # ldm {..pc}
    return [], True, None

def reach(d, seeds, lo, hi):
    seen = set(); work = list(seeds); warn = []
    while work:
        a = work.pop()
        while lo <= a < hi and a not in seen:
            seen.add(a)
            w = struct.unpack_from('<I', d, a)[0]
            tg, ft, why = arm_flow(w, a)
            if a >= 4 and struct.unpack_from('<I', d, a - 4)[0] == 0xe1a0e00f and (w >> 12) & 0xf == 15 and not tg: ft, why = True, None   # mov lr,pc ; ldr/mov pc  == indirect call
            if why: warn.append((a, why))
            for t in tg:
                if lo <= t < hi and t not in seen: work.append(t)
            if not ft: break
            a += 4
    return seen, warn
