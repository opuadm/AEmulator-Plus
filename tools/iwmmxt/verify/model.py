import difftest as d, struct, random
def s(v,b): v&=(1<<b)-1; return v-(1<<b) if v>>(b-1) else v
def lanes(x,b): return [(x>>(i*b))&((1<<b)-1) for i in range(64//b)]
def pack(ls,b): 
    r=0
    for i,v in enumerate(ls): r|=(v&((1<<b)-1))<<(i*b)
    return r
def sat(v,lo,hi): return max(lo,min(hi,v))
def run_neon(lines, wr, core):
    tl = d.translate(d.assemble_words(lines))
    body=[]; 
    for t in tl: body += t.split(';')
    def tr(block):
        ls=[l.strip() for l in block.split('\n') if l.strip()]; iw=[l for l in ls if d.is_iw(l)]
        tw=d.translate(d.assemble_words(iw)) if iw else []; r=[]; k=0
        for l in ls:
            if d.is_iw(l): r+=tw[k].split(';'); k+=1
            else: r.append(l)
        return ''.join(f'  {x}\n' for x in r)
    B = d.HEAD + tr(d.PRO_W)+tr(d.PRO_C)+tr(d.PRO_G)+''.join(f'  {l}\n' for l in body)+tr(d.EPI_W)+tr(d.EPI_C)+tr(d.EPI_G)+d.EPI_F+d.TAIL
    data = b''.join(struct.pack('<Q',x) for x in wr)+b''.join(struct.pack('<I',x) for x in core)+b'\0'*20
    out,e = d.run_prog(B,'cortex-a8',data)
    if out is None: raise RuntimeError(e)
    return struct.unpack('<16Q',out[:128]), struct.unpack('<5I',out[128:148])
rng=random.Random(7)
bad=0
def chk(name, ok):
    global bad
    print(f'{name:12s}', 'ok' if ok else 'FAIL'); bad+= (not ok)
for kind in range(3):
    pass
def rw():
    return [rng.getrandbits(64) if rng.random()<.6 else pack([rng.choice([0,1,0x7f,0x80,0xff,0x7fff,0x8000,0xffff,3,-5]) for _ in range(4)],16) for _ in range(16)]
tests={}
ok={k:True for k in ['wpackhss','wpackwss','wpackdss','wpackhus','wpackwus','tmovmskb','tmovmskh','tmovmskw','wmacs','wmacu','wmacsz','wmacuz']}
for _ in range(12):
    wr=rw(); core=[rng.getrandbits(32) for _ in range(5)]
    def get(line):
        w,c=run_neon([line],wr,core); return w,c
    a,b=wr[2],wr[3]
    # pack: wRd = [pack(wRn) | pack(wRm) << half]
    for nm,sz,lo,hi in [('wpackhss',16,-128,127),('wpackwss',32,-32768,32767),('wpackdss',64,-2**31,2**31-1),('wpackhus',16,0,255),('wpackwus',32,0,65535)]:
        w,_=get(f'{nm} wr1,wr2,wr3'); o=sz//2
        exp=pack([sat(s(v,sz),lo,hi) for v in lanes(a,sz)+lanes(b,sz)],o)
        ok[nm]&= (w[1]==exp)
    for nm,sz in [('tmovmskb',8),('tmovmskh',16),('tmovmskw',32)]:
        _,c=get(f'{nm} r1,wr2'); exp=sum(((v>>(sz-1))&1)<<i for i,v in enumerate(lanes(a,sz)))
        ok[nm]&= (c[0]==exp)
    for nm,sg,z in [('wmacs',1,0),('wmacu',0,0),('wmacsz',1,1),('wmacuz',0,1)]:
        w,_=get(f'{nm} wr1,wr2,wr3')
        ls=[(s(x,16) if sg else x)*(s(y,16) if sg else y) for x,y in zip(lanes(a,16),lanes(b,16))]
        exp=(sum(ls)+(0 if z else wr[1]))&(2**64-1)
        ok[nm]&= (w[1]==exp)
for k,v in ok.items(): chk(k,v)
