#!/usr/bin/env python3
"""Generate IwmmxtTables.kt: iWMMXt decode rows and ARM/VFP/NEON encode rows, all probed from GNU as.
The Kotlin translator contains no hand-written bit layouts: every field position below is measured."""
import sys, os, re, subprocess, tempfile, itertools, struct, collections
sys.path.insert(0, os.path.dirname(__file__))
import iwt
from iwt import *

TMP = tempfile.mkdtemp(prefix='gen')
EXTRA = os.environ.get('IWMMXT_EXTRA_SHAPES')
def sub(t, vals): return re.sub(r'<(\w+)>', lambda m: str(vals[m.group(1)]), t)

def asm_batch(lines, cpu_iw=False):
    """assemble each line separately; returns list[int|None] (first word)"""
    lines = list(lines); bad = set()
    for _ in range(40):
        src = ['.syntax unified'] + (['.cpu iwmmxt2'] if cpu_iw else ['.arch armv7-a', '.fpu neon']) + ['.arm', '.text']
        for i, l in enumerate(lines):
            src.append(f'.balign 4'); src.append(f'L{i}:'); src.append('  ' + (l if i not in bad else 'nop'))
        open(f'{TMP}/b.s', 'w').write('\n'.join(src) + '\n')
        p = subprocess.run(['arm-linux-gnueabi-as', '-o', f'{TMP}/b.o', f'{TMP}/b.s'], capture_output=True, text=True)
        if p.returncode == 0: break
        new = set()
        for m in re.finditer(r'b\.s:(\d+): Error', p.stderr):
            ln = int(m.group(1)); idx = (ln - 6) // 3      # 5 header lines; each insn takes 3 lines
            new.add(idx)
        if not new - bad: raise RuntimeError(p.stderr[:500])
        bad |= new
    subprocess.check_call(['arm-linux-gnueabi-objcopy', '-O', 'binary', '-j', '.text', f'{TMP}/b.o', f'{TMP}/b.bin'])
    blob = open(f'{TMP}/b.bin', 'rb').read()
    # each snippet is one word unless it is 'nop'-replaced; all single instruction => index*4
    out = []
    for i, l in enumerate(lines):
        out.append(None if i in bad else struct.unpack_from('<I', blob, i * 4)[0])
    return out

# ----------------------------------------------------------------------------- probing
def probe(tmpl, params, cpu_iw, base_vals=None):
    """tmpl uses {name}; params: name -> (nbits, kind)  kind in 'lin' (bitwise linear) or 'tab' (enumerate 0..2**nbits-1)
    returns (base_word, {name: ('lin', [mask per bit]) | ('tab', {val: delta})})"""
    base_vals = base_vals or {}
    names = list(params)
    bv = {n: base_vals.get(n, 0) for n in names}
    queries = [('base', None, None, sub(tmpl, bv))]
    for n in names:
        nb, kind = params[n]
        if kind == 'lin':
            for b in range(nb):
                v = dict(bv); v[n] = bv[n] ^ (1 << b); queries.append((n, b, v[n], sub(tmpl, v)))
        else:
            for val in range(1 << nb):
                v = dict(bv); v[n] = val; queries.append((n, None, val, sub(tmpl, v)))
    words = asm_batch([q[3] for q in queries], cpu_iw)
    base = words[0]
    if base is None: return None
    res = {}
    for (n, b, val, _), w in zip(queries[1:], words[1:]):
        if w is None: continue
        if params[n][1] == 'lin': res.setdefault(n, ('lin', [0] * params[n][0]))[1][b] = w ^ base
        else: res.setdefault(n, ('tab', {}))[1][val] = w ^ base
    for n in names: res.setdefault(n, ('lin', [0] * params[n][0]) if params[n][1] == 'lin' else ('tab', {}))
    return base, res

# ----------------------------------------------------------------------------- decoder specs
DEC = []   # (mnemonic, operand template, params)
def spec(mn, tmpl, **p): DEC.append((mn, tmpl, p))
W3 = 'wr{d}, wr{n}, wr{m}'
for op in ('and', 'or', 'xor', 'andn'): spec('w' + op, W3, d=4, n=4, m=4)
for op in ('add', 'sub'):
    for sz in 'bhw':
        for sat in ('', 'us', 'ss'): spec(f'w{op}{sz}{sat}', W3, d=4, n=4, m=4)
for u in 'us':
    for lm in 'lm':
        for r in ('', 'r'): spec(f'wmul{u}{lm}{r}', W3, d=4, n=4, m=4)
spec('wmulwl', W3, d=4, n=4, m=4)
for u in 'us':
    for r in ('', 'r'): spec(f'wmulw{u}m{r}', W3, d=4, n=4, m=4)
for u in 'us':
    spec(f'wmadd{u}', W3, d=4, n=4, m=4)
    for z in ('', 'z'): spec(f'wmac{u}{z}', W3, d=4, n=4, m=4)
spec('wshufh', 'wr{d}, wr{n}, #{i}', d=4, n=4, i=8)
for lh in 'lh':
    for us in 'us':
        for sz in 'bhw': spec(f'wunpcke{lh}{us}{sz}', 'wr{d}, wr{n}', d=4, n=4)
    for sz in 'bhw': spec(f'wunpcki{lh}{sz}', W3, d=4, n=4, m=4)
for sz in 'hwd':
    for sat in ('us', 'ss'): spec(f'wpack{sz}{sat}', W3, d=4, n=4, m=4)
for op in ('srl', 'sll', 'sra'):
    for sz in 'hwd': spec(f'w{op}{sz}g', 'wr{d}, wr{n}, wcgr{k}', d=4, n=4, k=2)
for op in ('srl', 'sll', 'sra', 'ror'):
    for sz in 'hwd': spec(f'w{op}{sz}', W3, d=4, n=4, m=4)
for op in ('srl', 'sll', 'sra', 'ror'):
    for sz, nb in (('h', 4), ('w', 5), ('d', 6)): spec(f'w{op}{sz}', 'wr{d}, wr{n}, #{i}', d=4, n=4, i=nb)
for op in ('min', 'max'):
    for u in 'us':
        for sz in 'bhw': spec(f'w{op}{u}{sz}', W3, d=4, n=4, m=4)
for u in 'us':
    for sz in 'bhw': spec(f'wcmpgt{u}{sz}', W3, d=4, n=4, m=4)
for sz in 'bhw': spec(f'wcmpeq{sz}', W3, d=4, n=4, m=4)
for k in range(4): spec(f'walignr{k}', W3, d=4, n=4, m=4)
spec('waligni', 'wr{d}, wr{n}, wr{m}, #{i}', d=4, n=4, m=4, i=3)
spec('wmerge', 'wr{d}, wr{n}, wr{m}, #{i}', d=4, n=4, m=4, i=3)
for sz, nb in (('b', 3), ('h', 2), ('w', 1)):
    spec(f'tbcst{sz}', 'wr{d}, r{a}', d=4, a=4)
    for u in 'us': spec(f'textrm{u}{sz}', 'r{a}, wr{n}, #{i}', a=4, n=4, i=nb)
    spec(f'tinsr{sz}', 'wr{d}, r{a}, #{i}', d=4, a=4, i=nb)
spec('tmcrr', 'wr{d}, r{a}, r{b}', d=4, a=4, b=4)
spec('tmrrc', 'r{a}, r{b}, wr{d}', a=4, b=4, d=4)
spec('tmcr', 'wcgr{k}, r{a}', k=2, a=4)
spec('tmrc', 'r{a}, wcgr{k}', a=4, k=2)
for sz in 'bhw':
    spec(f'tandc{sz}', 'pc'); spec(f'torc{sz}', 'pc')
# Added 2026-11-26: the rest of what the Marvell PXA firmware (GT-I8250) uses
for sz in 'bhw': spec(f'tmovmsk{sz}', 'r{a}, wr{n}', a=4, n=4)
for sz in 'bh':
    for r in ('', 'r'): spec(f'wavg2{sz}{r}', W3, d=4, n=4, m=4)
for r in ('', 'r'): spec(f'wavg4{r}', W3, d=4, n=4, m=4)
for sz in 'bh':
    for z in ('', 'z'): spec(f'wsad{sz}{z}', W3, d=4, n=4, m=4)
for x in ('', 'ph', 'bb', 'bt', 'tb', 'tt'): spec(f'tmia{x}', 'wr{d}, r{a}, r{b}', d=4, a=4, b=4)
for sz in 'bhw':
    spec(f'wabs{sz}', 'wr{d}, wr{n}', d=4, n=4); spec(f'wabsdiff{sz}', W3, d=4, n=4, m=4); spec(f'wacc{sz}', 'wr{d}, wr{n}', d=4, n=4)
for sz, nb in (('b', 3), ('h', 2), ('w', 1)): spec(f'textrc{sz}', 'pc, #{i}', i=nb)

def gen_decoder():
    rows = []
    for mn, tmpl, p in DEC:
        params = {n: (nb, 'lin') for n, nb in p.items()}
        full = re.sub(r'\{(\w)\}', r'<\1>', f'{mn} {tmpl}')
        # baseline: regs 0; imm 0; some mnemonics reject imm 0 -> retry with 1
        one = {n: 1 for n in p if n == 'i'}
        shiftfam = re.fullmatch(r'w(srl|sll|sra|ror)[hwd]', mn) is not None
        for bv in (([one, {}] if shiftfam else [{}, one])):
            r = probe(full, params, True, bv)
            if r: break
        if r is None: print('skip (gas rejects)', mn, file=sys.stderr); continue
        base, res = r
        fieldmask = 0
        cond_f = (base >> 28) == 0xf
        mask = (~fieldmask) & 0xffffffff
        if not cond_f: mask &= 0x0fffffff
        fields = {}
        for n in p:
            bits = res[n][1]
            if all(bin(b).count('1') == 1 for b in bits):
                fields[n] = ('L', [b.bit_length() - 1 for b in bits])
                for b in bits: fieldmask |= b
            else:
                # non-linear immediate: enumerate every legal value
                pm = 0
                for b in bits: pm |= b
                tabp = {n: (p[n], 'tab')}
                r2 = probe(full, {**{x: (p[x], 'lin') for x in p if x != n}, **tabp}, True, {})
                b2, res2 = r2
                ws = {v: b2 ^ dl for v, dl in res2[n][1].items()}
                # keep only values that stay inside this family (same condition class / major opcode bits as the baseline word)
                valid = {v: w for v, w in ws.items() if (w >> 28) == (base >> 28) and (w & 0x0e000000) == (base & 0x0e000000)}
                w0 = valid[min(valid)]
                allm = 0
                for w in valid.values(): allm |= (w ^ w0)
                tab = {v: w & allm for v, w in valid.items()}
                fields[n] = ('T', tab, allm)
                fieldmask |= allm
        mask = (~fieldmask) & 0xffffffff
        if not cond_f: mask &= 0x0fffffff
        rows.append((mn, tmpl, mask, base & mask, cond_f, fields))
    return rows

# ----------------------------------------------------------------------------- encoder shapes
TOKEN = re.compile(r'#-?(?:0x[0-9a-fA-F]+|\d+)|\b[dqr]\d+\b|\[\d+\]')
def normalize(line):
    """-> (key, [slot values]) ; slot values are ints in left-to-right order"""
    slots = []
    def f(m):
        t = m.group(0)
        if t.startswith('#'):
            neg = t[1] == '-'; v = int(t[2:] if neg else t[1:], 0); slots.append(v); return '#-?' if neg else '#?'
        if t.startswith('['): slots.append(int(t[1:-1])); return '[?]'
        slots.append(int(t[1:])); return t[0] + '?'
    key = TOKEN.sub(f, line.strip())
    key = re.sub(r'\s+', ' ', key)
    return key, slots

def slot_kinds(line):
    kinds = []
    for m in TOKEN.finditer(line):
        t = m.group(0)
        kinds.append('imm' if t.startswith('#') else 'lane' if t.startswith('[') else t[0])
    return kinds

def collect_shapes():
    """run the Python translator over every supported mnemonic variant and collect emitted lines"""
    lines = set()
    def feed(base, ops, cc=14):
        r = Rec(); r.addr = 0; r.word = 0; r.cc = cc; r.base = base; r.ops = ops; r.idx = 0
        try: xl = xlate(r, None, 1)
        except Unsupported: return
        for t in xl:
            if not isinstance(t, tuple):
                lines.add(t.text.replace('{c}', ''))
    # data-processing via decode specs (several register aliasing patterns)
    for mn, tmpl, p in DEC:
        if mn.startswith(('tandc', 'torc')): continue
        for pat in ({'d': 1, 'n': 2, 'm': 3, 'k': 1, 'a': 4, 'b': 5, 'i': 1},
                    {'d': 1, 'n': 1, 'm': 3, 'k': 2, 'a': 4, 'b': 5, 'i': 0},
                    {'d': 2, 'n': 1, 'm': 2, 'k': 3, 'a': 12, 'b': 5, 'i': 3}):
            for iv in {0, 1, pat['i'], (1 << p.get('i', 1)) - 1, (1 << p.get('i', 1)) // 2 + 1, 15, 16, 31, 63}:
                if 'i' in p and iv >= (1 << p['i']): continue
                feed(mn, tmpl.format(**dict(pat, i=iv)))
    # flag consumers need a producer
    for prod in FLAG_LAYOUT:
        for cons in ('tandcw', 'torcw', 'tandch', 'torch'):
            rp = Rec(); rp.addr = 0; rp.word = 0; rp.cc = 14; rp.base = prod; rp.idx = 0
            rp.ops = 'wr3, wr3, #1' if prod in ('wsllh', 'wsrlh') else ('wr3, wr3, wcgr0' if prod == 'wsradg' else ('wr3, wr3' if prod.startswith('wunpcke') else 'wr3, wr4, wr5'))
            rc = Rec(); rc.addr = 4; rc.word = 0; rc.cc = 14; rc.base = cons; rc.ops = 'pc'; rc.idx = 1
            try:
                for t in xlate(rc, rp, 1):
                    if not isinstance(t, tuple): lines.add(t.text.replace('{c}', ''))
            except (Unsupported, KeyError): pass
    # memory forms
    for sz in 'dwhb':
        for off in (0, 1, 2, 4, 8, 16, 48, 64):
            for ld in ('wldr', 'wstr'):
                for form in (f'wr5, [r3, #{off}]', f'wr5, [r3], #{off}', f'wr5, [r3, #{off}]!', f'wr5, [r3, #-{off}]', f'wr5, [r3, #-{off}]!', 'wr5, [r3]', 'wr5, [r3], #-8'):
                    feed(ld + sz, form)
    # shapes of the Kotlin-only translations (Iwmmxt.allLines(), dumped by tools/iwmmxt/dump_shapes.sh)
    if EXTRA and os.path.exists(EXTRA): lines |= {l.strip() for l in open(EXTRA) if l.strip() and '#@' not in l}
    lines.discard('vldr d0, .Llit1'); lines |= {f'vldr d0, [pc, #{o}]' for o in (0, 4, 8)}
    lines = {l for l in lines if not re.search(r'\.Llit', l)}
    return sorted(lines)

def gen_encoder(lines):
    shapes = collections.OrderedDict()
    for l in lines:
        key, slots = normalize(l)
        kinds = slot_kinds(l)
        shapes.setdefault(key, (l, kinds))
    rows = []
    for key, (example, kinds) in shapes.items():
        # build template with {s0}.. placeholders
        idx = [0]
        def rep(m):
            t = m.group(0); i = idx[0]; idx[0] += 1
            if t.startswith('#'): return ('#-' if t[1] == '-' else '#') + '<s%d>' % i
            if t.startswith('['): return '[<s%d>]' % i
            return t[0] + '<s%d>' % i
        tmpl = TOKEN.sub(rep, example)
        params = {}
        for i, k in enumerate(kinds):
            nb = {'d': 5, 'q': 4, 'r': 4, 'lane': 3, 'imm': 10}[k]
            params[f's{i}'] = (nb, 'tab' if k == 'imm' else 'lin')
        # core registers: never r15; D regs: 0..31
        # imm slots can be large -> use linear probing first (offsets / add imm) and detect non-linearity afterwards
        lin_params = {n: ((nb, 'lin') if kinds[int(n[1:])] == 'imm' else v) for n, v in params.items()}
        force1 = key.startswith(('vshr.', 'vshl.', 'vsli.', 'vsri.', 'vsra.'))
        base_vals = {n: 1 for n in lin_params if kinds[int(n[1:])] == 'imm'} if force1 else {}
        # a base register of the memory forms must not be pc/sp specially: keep literal 'sp'/'pc' keys as they are
        r = probe(tmpl, lin_params, False, base_vals)
        if r is None:
            # some immediates are illegal at 0 (shifts): find a legal baseline for imm slots
            for b0 in range(1, 65):
                bv = {n: b0 for n in lin_params if kinds[int(n[1:])] == 'imm'}
                r = probe(tmpl, lin_params, False, bv)
                if r: break
        if r is None: print('shape rejected by gas', key, example, file=sys.stderr); continue
        base, res = r
        # imm slots: enumerate against the canonical base word (all other slots at baseline)
        bvec = {n: 0 for n in lin_params}
        b0 = None
        for n, v in res.items(): pass
        # baseline imm value actually used by probe(): 0, or the first b0 that gas accepted
        used_b0 = 1 if force1 else 0
        if not force1 and probe(tmpl, lin_params, False, {}) is None:
            for bb in range(1, 65):
                if probe(tmpl, lin_params, False, {n: bb for n in lin_params if kinds[int(n[1:])] == 'imm'}): used_b0 = bb; break
        for n in lin_params:
            if kinds[int(n[1:])] == 'imm': bvec[n] = used_b0
        out_slots = []
        for i, k in enumerate(kinds):
            n = f's{i}'; kind, bits = res[n]
            if k != 'imm':
                out_slots.append(('lin', bits, -1)); continue
            vals = list(range(0, 1021 if key.startswith(('vldr', 'vstr')) else 256 if key.startswith(('add ', 'sub ', 'vmov.i')) else 130))
            ws = asm_batch([sub(tmpl, {**bvec, n: v}) for v in vals])
            legal = {v: w for v, w in zip(vals, ws) if w is not None}
            if not legal: print('no legal imm', key, file=sys.stderr); continue
            import functools
            def predicted(vv): return base ^ functools.reduce(lambda x, y: x ^ y, [bits[bb] for bb in range(len(bits)) if vv >> bb & 1], 0)
            lin_ok = used_b0 == 0 and any(bits) and all(predicted(v) == w for v, w in legal.items())
            lim = -1
            if key.startswith(('add ', 'sub ')): lim = 255
            elif key.startswith(('vldr', 'vstr')): lim = 1020
            elif key.startswith('vmov.i'): lim = 255
            if lin_ok: out_slots.append(('lin', bits, lim))
            else: out_slots.append(('tab', {v: w ^ base for v, w in legal.items()}, 0))
        rows.append((key, base, out_slots, kinds))
    return rows

# ----------------------------------------------------------------------------- Kotlin output
def emit_kotlin(dec_rows, enc_rows, path):
    o = []
    o.append('/* Modified for AEmulator Plus, 2026-11-25: iWMMXt -> NEON static translator (generated tables).')
    o.append(' * GENERATED by tools/iwmmxt/gen_tables.py from GNU as output - do not edit by hand.')
    o.append(' * GPL-3.0; see LICENSE and NOTICE.md. */')
    o.append('package app.aemu.core\n')
    o.append('/** Decode rows: "mnemonic|operand template|mask|value|condF|param:bit,bit,...;..." (bit lists are LSB first) */')
    o.append('internal object IwmmxtTables {')
    o.append('    val DECODE: Array<String> = arrayOf(')
    for mn, tmpl, mask, val, cf, fields in dec_rows:
        parts = []
        for n, fd in fields.items():
            if fd[0] == 'L': parts.append(f"{n}:" + ','.join(map(str, fd[1])))
            else: parts.append(f"{n}=" + ','.join(f'{v}:{b:#x}' for v, b in sorted(fd[1].items())))
        o.append(f'        "{mn}|{tmpl}|{mask:#x}|{val:#x}|{int(cf)}|' + ';'.join(parts) + '"')
    o.append('    )\n')
    o.append('    /** Encode rows: "key|baseWord|slot;slot;..." slot = "L:mask,mask,.." (xor mask per value bit) or "T:v0:val=mask,val=mask.." */')
    o.append('    val ENCODE: Array<String> = arrayOf(')
    for key, base, slots, kinds in enc_rows:
        ss = []
        for s in slots:
            if s[0] == 'lin': ss.append(f'L:{s[2]}:' + ','.join(f'{b:#x}' for b in s[1]))
            else: ss.append(f"T:{s[2]}:" + ','.join(f'{v}={d:#x}' for v, d in sorted(s[1].items())))
        o.append(f'        "{key}|{base:#x}|' + ';'.join(ss) + '"')
    o.append('    )')
    o.append('}')
    out = []
    for i, l in enumerate(o):
        nxt = o[i + 1] if i + 1 < len(o) else ''
        out.append(l + (',' if l.startswith('        "') and nxt.startswith('        "') else ''))
    open(path, 'w').write('\n'.join(out) + '\n')

if __name__ == '__main__':
    out = sys.argv[1]
    dec = gen_decoder(); print('decode rows', len(dec), file=sys.stderr)
    lines = collect_shapes(); print('distinct emitted lines', len(lines), file=sys.stderr)
    enc = gen_encoder(lines); print('encode rows', len(enc), file=sys.stderr)
    import pickle; pickle.dump((dec, enc), open(TMP + '/rows.pkl', 'wb'))
    emit_kotlin(dec, enc, out)
    print('wrote', out, os.path.getsize(out), 'bytes', file=sys.stderr)
