import subprocess, re, random, struct
lines=[l.strip() for l in open('extra.txt') if l.strip() and '#@' not in l]
# add register/imm variants
rng=random.Random(3); var=[]
for l in lines:
    var.append(l)
    for _ in range(6):
        def rr(m):
            t=m.group(0)
            if t[0]=='d': return 'd'+str(rng.randint(16,31) if rng.random()<.7 else rng.randint(0,7))
            if t[0]=='q': return 'q'+str(rng.randint(0,3))
            return 'r'+str(rng.choice([0,1,2,3,4,5,6,7,8,9,10,11,12]))
        v=re.sub(r'\b[dqr]\d+\b',rr,l)
        v=re.sub(r'#(\d+)',lambda m:'#'+str(rng.choice([0,1,2,3,4,7,8,15,16,31,32,63,64,100,255]) ) if int(m.group(1))<256 else m.group(0),v)
        var.append(v)
var=sorted(set(var))
open('/tmp/enc_in.txt','w').write('\n'.join(var)+'\n')
out=subprocess.run(['java','-jar',__import__('os').environ.get('IWT_JAR', 'iwt.jar'),'--enc'],stdin=open('/tmp/enc_in.txt'),capture_output=True,text=True).stdout.split('\n')
out=[o for o in out if o and not o.startswith('Picked')]
# assemble each line with gas individually (skip those gas rejects)
bad=0; checked=0
src=['.arm','.cpu cortex-a8','.fpu neon']
import tempfile,os
W=tempfile.mkdtemp()
for i,(l,o) in enumerate(zip(var,out)):
    if o=='ERR': continue
    open(f'{W}/a.s','w').write('.arm\n.cpu cortex-a8\n.fpu neon\n'+l+'\n')
    p=subprocess.run(['arm-linux-gnueabi-as',f'{W}/a.s','-o',f'{W}/a.o'],capture_output=True,text=True)
    if p.returncode: continue
    subprocess.run(['arm-linux-gnueabi-objcopy','-O','binary','-j','.text',f'{W}/a.o',f'{W}/a.bin'])
    w=struct.unpack('<I',open(f'{W}/a.bin','rb').read()[:4])[0]
    checked+=1
    if w!=int(o,16):
        bad+=1
        if bad<=15: print('DIFF',l,hex(w),o)
print('checked',checked,'of',len(var),'bad',bad, 'kotlin-ERR', sum(1 for o in out if o=='ERR'))
