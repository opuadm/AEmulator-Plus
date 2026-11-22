import struct, sys
from elftools.elf.elffile import ELFFile
from capstone import *
# __aarch64_ldadd8_{relax,acq_rel}: x0 = addend, x1 = ptr, returns the old value in x0.
# The shim built by zig called itself (bl to its own address) and overflowed the stack on first use.
# Only x16/x17 may be clobbered (outline-atomics helper ABI). Both entry points share one acquire/release loop
# (stronger than relaxed, so valid for both) in their 40 contiguous bytes: acq_rel at A, relax at A+20.
def patch(path):
    data=bytearray(open(path,'rb').read())
    e=ELFFile(open(path,'rb')); st=e.get_section_by_name('.symtab')
    s={x.name:x for x in st.iter_symbols()}
    rel,acq=s['__aarch64_ldadd8_relax'],s['__aarch64_ldadd8_acq_rel']
    A=acq['st_value']; assert rel['st_value']==A+20, "unexpected layout"
    sec=e.get_section(acq['st_shndx']); o=A-sec['sh_addr']+sec['sh_offset']
    # verify what we overwrite is the self-calling shim: 2 x (stp, mov, bl self, ldp, ret)
    old=struct.unpack('<10I',data[o:o+40])
    assert old[2]==0x94000000|(((-8)//4)&0x3ffffff) or True
    md=Cs(CS_ARCH_ARM64,CS_MODE_ARM)
    ins=list(md.disasm(bytes(data[o:o+40]),A))
    assert ins[2].mnemonic=='bl' and ins[2].op_str=='#%#x'%A and ins[7].mnemonic=='bl' and ins[7].op_str=='#%#x'%(A+20), "not the self-calling shim"
    new=[0xC85FFC30,  # A+0  ldaxr x16,[x1]
         0x8B000210,  # A+4  add   x16,x16,x0       (new value)
         0xC811FC30,  # A+8  stlxr w17,x16,[x1]
         0x35FFFFB1,  # A+12 cbnz  w17,A+0          (retry; x0 untouched)
         0x14000002,  # A+16 b     A+24
         0x17FFFFFB,  # A+20 b     A+0              <- __aarch64_ldadd8_relax
         0xCB000200,  # A+24 sub   x0,x16,x0        (old = new - addend)
         0xD65F03C0,  # A+28 ret
         0xD503201F, 0xD503201F]
    data[o:o+40]=struct.pack('<10I',*new)
    open(path,'wb').write(data)
    print(path.split('/')[-1])
    for i in md.disasm(bytes(data[o:o+40]),A): print("   %x %s %s"%(i.address,i.mnemonic,i.op_str))
for p in sys.argv[1:]: patch(p)
