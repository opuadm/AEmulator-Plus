#!/usr/bin/env python3
"""Differential test of the iWMMXt->NEON translator: original on qemu pxa270, translation on qemu cortex-a8."""
import subprocess, random, struct, sys, os, re, tempfile
HARN = ['java', '-jar', os.environ.get('IWT_JAR', 'iwt.jar'), '--words']
AS = 'arm-linux-gnueabi-as'; LD = 'arm-linux-gnueabi-ld'
W = tempfile.mkdtemp(prefix='dt')

def sh(*a, **k): return subprocess.run(a, capture_output=True, text=True, **k)

def assemble_words(lines):
    """assemble iWMMXt lines individually-in-one-file -> list of words"""
    src = ['.cpu iwmmxt2', '.arm', '.text'] + lines
    open(f'{W}/w.s', 'w').write('\n'.join(src) + '\n')
    p = sh(AS, f'{W}/w.s', '-o', f'{W}/w.o')
    if p.returncode: raise RuntimeError(p.stderr[:400])
    sh('arm-linux-gnueabi-objcopy', '-O', 'binary', '-j', '.text', f'{W}/w.o', f'{W}/w.bin')
    b = open(f'{W}/w.bin', 'rb').read()
    return list(struct.unpack('<%dI' % (len(b) // 4), b))

def translate(words, prevs=None):
    inp = '\n'.join(f'{w:08x}' + (f' {prevs[i]:08x}' if prevs and prevs[i] else '') for i, w in enumerate(words)) + '\n'
    p = subprocess.run(HARN, input=inp, capture_output=True, text=True)
    out = [l for l in p.stdout.split('\n') if l != '' and not l.startswith('Picked')]
    return out

def is_iw(l):
    m = re.match(r'\s*([a-z0-9]+)', l)
    return m and (m.group(1).startswith(('w', 't')) and m.group(1) not in ('teq', 'tst'))

PRO_W = ''.join(f'  wldrd wr{i},[r11,#{8*i}]\n' for i in range(16))
PRO_C = ''.join(f'  ldr r{i},[r11,#{128+4*(i-1)}]\n' for i in range(1, 6))
PRO_G = ''.join(f'  ldr r6,[r11,#{148+4*k}]\n  tmcr wcgr{k},r6\n' for k in range(4))
EPI_W = ''.join(f'  wstrd wr{i},[r10,#{8*i}]\n' for i in range(16))
EPI_C = ''.join(f'  str r{i},[r10,#{128+4*(i-1)}]\n' for i in range(1, 6))
EPI_G = ''.join(f'  tmrc r6,wcgr{k}\n  str r6,[r10,#{148+4*k}]\n' for k in range(4))
EPI_F = '  mrs r6,apsr\n  str r6,[r10,#164]\n'
TAIL = '''  mov r7,#4
  mov r0,#1
  mov r1,r10
  mov r2,#168
  svc 0
  mov r7,#1
  mov r0,#0
  svc 0
.data
.balign 8
init: .space 168
out: .space 168
'''
HEAD = '.cpu iwmmxt2\n.arm\n.global _start\n_start:\n  ldr r11,=init\n  ldr r10,=out\n'

def build(prefix_lines, test_lines):
    return HEAD + prefix_lines + ''.join(f'  {l}\n' for l in test_lines) + EPI_F[:0] + ''

def run_prog(src, cpu, data):
    open(f'{W}/p.s', 'w').write(src)
    p = sh(AS, '-march=armv7-a', '-mfpu=neon', f'{W}/p.s', '-o', f'{W}/p.o') if cpu != 'pxa270' else sh(AS, f'{W}/p.s', '-o', f'{W}/p.o')
    if p.returncode: return None, 'asm: ' + p.stderr[:300]
    p = sh(LD, '-static', f'{W}/p.o', '-o', f'{W}/p')
    if p.returncode: return None, 'ld ' + p.stderr[:200]
    # patch init data
    elf = open(f'{W}/p', 'rb').read()
    idx = elf.find(b'\0' * 168 + b'\0' * 168)
    nm = sh('arm-linux-gnueabi-nm', f'{W}/p').stdout
    sym = {l.split()[2]: int(l.split()[0], 16) for l in nm.splitlines() if len(l.split()) == 3}
    # file offset of .data: use objdump -h
    hdr = sh('arm-linux-gnueabi-objdump', '-h', f'{W}/p').stdout
    m = re.search(r'\.data\s+\w+\s+(\w+)\s+\w+\s+(\w+)', hdr)
    vma, off = int(m.group(1), 16), int(m.group(2), 16)
    o = off + (sym['init'] - vma)
    elf = elf[:o] + data + elf[o + len(data):]
    open(f'{W}/p', 'wb').write(elf); os.chmod(f'{W}/p', 0o755)
    q = subprocess.run(['qemu-arm', '-cpu', cpu, f'{W}/p'], capture_output=True)
    if q.returncode != 0 or len(q.stdout) != 168: return None, f'rc={q.returncode} {q.stderr[:100]}'
    return q.stdout, None

def rnd_data(rng, kind):
    w = []
    for i in range(16):
        if kind == 0: v = rng.getrandbits(64)
        elif kind == 1: v = int.from_bytes(bytes(rng.choice([0, 1, 0x7f, 0x80, 0xff, 0xfe, 0x55, 0xaa]) for _ in range(8)), 'little')
        else: v = int.from_bytes(struct.pack('<4h', *[rng.choice([0, 1, -1, 32767, -32768, 100, -100, rng.randint(-32768, 32767)]) for _ in range(4)]), 'little')
        w.append(v)
    c = [rng.getrandbits(32) if kind != 1 else rng.choice([0, 1, 0xffffffff, 0x80000000, 0x7fffffff, 5]) for _ in range(5)]
    g = [rng.randint(0, 40) for _ in range(4)]
    return b''.join(struct.pack('<Q', x) for x in w) + b''.join(struct.pack('<I', x) for x in c) + b''.join(struct.pack('<I', x) for x in g) + b'\0' * 4

def test(insn_lines, trials=24, seed=1, ref=None, flagmask=0xffffffff):
    """insn_lines: iWMMXt asm lines (maybe a producer + consumer)"""
    rng = random.Random(seed)
    body = insn_lines
    iw_idx = [i for i, l in enumerate(body) if is_iw(l)]
    words = assemble_words([body[i] for i in iw_idx])
    prevs = []
    for k, i in enumerate(iw_idx):
        prevs.append(words[k - 1] if k > 0 and iw_idx[k - 1] == i - 1 else 0)
    tl = translate(words, prevs)
    if len(tl) != len(words): return 'harness mismatch ' + str(tl)
    bad = [t for t in tl if t.startswith(('UNDEC', 'ERR'))]
    if bad: return bad[0]
    b_lines = []; k = 0
    for i, ln in enumerate(body):
        if i in iw_idx: b_lines += tl[k].split(';'); k += 1
        else: b_lines.append(ln)
    pro_b_w = translate(assemble_words([l.strip() for l in (PRO_W + PRO_G).split('\n') if l.strip()]))
    epi_b = translate(assemble_words([l.strip() for l in (EPI_W + EPI_G).split('\n') if l.strip()]))
    def neon(pro, ls, epi_c):
        out = HEAD
        out += '  vldr d16,[r11]\n' if False else ''
        for l in pro.split('\n'):
            pass
        return out
    # build A
    A = HEAD + PRO_W + PRO_C + PRO_G + ''.join(f'  {l}\n' for l in (ref if ref is not None else body)) + EPI_W + EPI_C + EPI_G + EPI_F + TAIL
    # build B: translate prologue/epilogue iwmmxt lines too
    def tr(block):
        ls = [l.strip() for l in block.split('\n') if l.strip()]
        iw = [l for l in ls if is_iw(l)]
        tw = translate(assemble_words(iw)) if iw else []
        res = []; k = 0
        for l in ls:
            if is_iw(l): res += tw[k].split(';'); k += 1
            else: res.append(l)
        return ''.join(f'  {x}\n' for x in res)
    B = HEAD + tr(PRO_W) + tr(PRO_C) + tr(PRO_G) + ''.join(f'  {l}\n' for l in b_lines) + tr(EPI_W) + tr(EPI_C) + tr(EPI_G) + EPI_F + TAIL
    for t in range(trials):
        data = rnd_data(rng, t % 3)
        a, ea = run_prog(A, 'pxa270', data)
        if a is None: return 'ILL-orig ' + (ea or '')
        b, eb = run_prog(B, 'cortex-a8', data)
        if b is None: return 'ERR-neon ' + (eb or '')
        ra = list(struct.unpack('<42I', a)); rb = list(struct.unpack('<42I', b)); ra[41] &= flagmask; rb[41] &= flagmask
        if ra != rb:
            diff = [(i, hex(x), hex(y)) for i, (x, y) in enumerate(zip(ra, rb)) if x != y]
            return f'MISMATCH trial {t}: words(idx,orig,neon) {diff[:6]}'
    return 'ok'

if __name__ == '__main__':
    for l in sys.argv[1:]:
        print(l, '->', test([x.strip() for x in l.split(';')]))
