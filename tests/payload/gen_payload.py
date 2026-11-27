import os,struct,hashlib,random,bz2,brotli,bsdiff4
random.seed(7)
BS=4096
def varint(v):
    v&=(1<<64)-1;o=bytearray()
    while True:
        b=v&127;v>>=7
        if v:o.append(b|128)
        else:o.append(b);return bytes(o)
def tag(f,w):return varint(f<<3|w)
def fv(f,v):return tag(f,0)+varint(v)
def fb(f,b):return tag(f,2)+varint(len(b))+b
def ext(s,n):return fv(1,s)+fv(2,n)
def offtout(x):
    neg=x<0;y=abs(x);b=bytearray(y.to_bytes(8,'little'))
    if neg:b[7]|=0x80
    return bytes(b)
def to_bsdf2(p40,kind=2):
    cl,dl,ns=[struct.unpack('<q',p40[8+8*i:16+8*i])[0] for i in range(3)]
    # offtin of positive numbers == plain LE
    ctrl=bz2.decompress(p40[32:32+cl]);diff=bz2.decompress(p40[32+cl:32+cl+dl]);extra=bz2.decompress(p40[32+cl+dl:])
    comp=(lambda b:b) if kind==0 else brotli.compress
    c,d,e=comp(ctrl),comp(diff),comp(extra)
    return b'BSDF2'+bytes([kind]*3)+offtout(len(c))+offtout(len(d))+offtout(ns)+c+d+e
def rnd(n):return bytes(random.getrandbits(8) for _ in range(n))
def texty(n):
    words=[b'alpha ',b'beta ',b'libfoo.so ',b'/system/bin/ ',b'\x00\x00\x00\x00',b'\x7fELF']
    return b''.join(random.choice(words) for _ in range(n//6+1))[:n]
OLDB=64
old=bytearray(texty(OLDB*BS))
for i in range(OLDB):  # make blocks distinct
    old[i*BS:i*BS+8]=struct.pack('<Q',i*0x9e3779b97f4a7c15&(2**64-1))
old=bytes(old)
blk=lambda img,i,n=1:img[i*BS:(i+n)*BS]
NEWB=56
new=bytearray(NEWB*BS)
blob=bytearray()  # data region
ops=[]
def sha(b):return hashlib.sha256(b).digest()
def add_blob(b):
    off=len(blob);blob.extend(b);return off,len(b)
def op(type_,dst,src=None,data=None,srchash=None,dstlen=None):
    o=fv(1,type_)
    if data is not None:
        off,ln=add_blob(data);o+=fv(2,off)+fv(3,ln)
    for s,n in (src or []):o+=fb(4,ext(s,n))
    for s,n in dst:o+=fb(6,ext(s,n))
    if dstlen:o+=fv(7,dstlen)
    if data is not None:o+=fb(8,sha(data))
    if srchash:o+=fb(9,srchash)
    ops.append(o)
# 1. SOURCE_COPY old 20..29 -> new 0..9
d=blk(old,20,10);new[0:10*BS]=d
op(4,[(0,10)],[(20,10)],srchash=sha(d))
# 2. BROTLI_BSDIFF old 30..39 (modified) -> new 10..19
tgt=bytearray(blk(old,30,10))
for k in range(0,len(tgt),997):tgt[k]^=0x5a
tgt[5000:5000]=b'';tgt=bytes(tgt)
new[10*BS:20*BS]=tgt
p=bsdiff4.diff(blk(old,30,10),tgt)
op(10,[(10,10)],[(30,10)],data=to_bsdf2(p),srchash=sha(blk(old,30,10)))
# 3. REPLACE raw -> new 20..29
r=rnd(10*BS);new[20*BS:30*BS]=r
op(0,[(20,10)],data=r)
# 4. ZERO 30..33
op(6,[(30,4)])
# 5. SOURCE_COPY with a hole and split extents: old[0], hole, old[5] -> new 34,35,36 (dst split)
d=blk(old,0)+b'\0'*BS+blk(old,5);new[34*BS:37*BS]=d
HOLE=2**64-1
op(4,[(34,2),(36,1)],[(0,1),(HOLE,1),(5,1)],srchash=sha(d))
# 6. BSDIFF split source extents, uncompressed streams (kind 0), shorter than the dst extents
src_data=blk(old,40,3)+blk(old,10,2)
tg=bytearray(src_data[:5*BS-100]);tg[100]^=1;tg=bytes(tg)
tg+=b'';
p=bsdiff4.diff(src_data,tg)
new[40*BS:40*BS+len(tg)]=tg
op(10,[(40,5)],[(40,3),(10,2)],data=to_bsdf2(p,0),srchash=sha(src_data),dstlen=len(tg))
# 7. bsdiff with a patch that needs data from far away negative seeks: reversed blocks
src_data=blk(old,50,6);tg=b''.join(blk(old,50+i,1) for i in (5,4,3,2,1,0))
p=bsdiff4.diff(src_data,tg);new[45*BS:51*BS]=tg
op(10,[(45,6)],[(50,6)],data=to_bsdf2(p),srchash=sha(src_data))
new=bytes(new)
part=fb(1,b'system')+fb(6,fv(1,len(old))+fb(2,sha(old)))+fb(7,fv(1,len(new))+fb(2,sha(new)))
for o in ops:part+=fb(8,o)
man=fv(3,BS)+fv(12,7)+fb(13,part)+fv(14,1795020573)+fb(18,b'2026-11-01')
hdr=b'CrAU'+struct.pack('>QQI',2,len(man),0)
payload=hdr+man+bytes(blob)
open('data/old.img','wb').write(old);open('data/new.img','wb').write(new)
open('data/payload.bin','wb').write(b'\xAA'*100+payload)   # window offset 100
props=f"FILE_HASH={__import__('base64').b64encode(sha(payload)).decode()}\nFILE_SIZE={len(payload)}\n"
open('data/payload_properties.txt','w').write(props)
print('ok',len(old),len(new),len(payload),len(ops),'ops')
