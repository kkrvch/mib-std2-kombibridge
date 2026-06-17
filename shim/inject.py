#!/usr/bin/env python3
# inject.py — injects navshim.so into libext.google.gal.receiver.so (no QNX tooling).
#  - new RWX PT_LOAD with the blob (a 7th phdr is written into free space in the table)
#  - .rel.dyn is moved into the new segment: [first RELATIVE][+ ours][rest]
#  - DT_REL/DT_RELSZ/DT_RELCOUNT are patched in place
#  - the `BL onChannelOpened` in the tail of GalReceiver::init -> `BL init_trampoline`
import sys, struct, io, argparse
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

R_RELATIVE=23
R_JUMP_SLOT=22
TRAMPOLINE_SYM='init_trampoline'

# --- per-firmware addresses are AUTO-RESOLVED from the target libgal (no hardcoding) ---
# The blob's .galrefs slots (see navshim_fs.cpp g_ref[]) are filled here, in this exact order.
#   0 R_REGISTER     GalReceiver::registerService          (.dynsym)
#   1 R_NAV_VTABLE   _ZTV24NavigationStatusEndpoint        (.dynsym; navshim adds +8 at runtime)
#   2 R_GOT_MEMSET   memset GOT slot                       (R_ARM_JUMP_SLOT reloc)
#   3 R_ONCHOPEN     onChannelOpened PLT stub              (derived via the BL auto-locator)
#   4 R_MEDIA_VTABLE _ZTV27MediaPlaybackStatusEndpoint     (.dynsym; navshim adds +8 at runtime)
SYM_REGISTER='_ZN11GalReceiver15registerServiceEP20ProtocolEndpointBase'
SYM_NAV_VTABLE='_ZTV24NavigationStatusEndpoint'
SYM_ONCHOPEN='_ZN20ProtocolEndpointBase15onChannelOpenedEh'  # the call displaced from init's tail
SYM_MEDIA_VTABLE='_ZTV27MediaPlaybackStatusEndpoint'
SYM_INIT='_ZN11GalReceiver4initERK10shared_ptrI20IControllerCallbacksE'

def g16(b,o): return struct.unpack_from('<H',b,o)[0]
def g32(b,o): return struct.unpack_from('<I',b,o)[0]
def s16(b,o,v): struct.pack_into('<H',b,o,v)
def s32(b,o,v): struct.pack_into('<I',b,o,v)
def align(x,a): return (x+a-1)&~(a-1)

# ----- auto-resolution of the per-firmware addresses from the target libgal -----
def vaddr_to_foff(e):
    """Map a virtual address to a file offset via the target's PT_LOAD segments."""
    loads=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    def v2f(va):
        for s in loads:
            if s['p_vaddr']<=va<s['p_vaddr']+s['p_filesz']:
                return s['p_offset']+(va-s['p_vaddr'])
        raise ValueError("vaddr 0x%x not in any PT_LOAD"%va)
    return v2f

def dynsym_map(e):
    ds=e.get_section_by_name('.dynsym')
    return {sy.name:sy['st_value'] for sy in ds.iter_symbols() if sy.name}

def jump_slot_map(e):
    """symbol name -> GOT slot vaddr (r_offset) for every R_ARM_JUMP_SLOT relocation."""
    out={}
    for s in e.iter_sections():
        if isinstance(s,RelocationSection):
            symtab=e.get_section(s['sh_link'])
            for r in s.iter_relocations():
                if r['r_info_type']==R_JUMP_SLOT:
                    out[symtab.get_symbol(r['r_info_sym']).name]=r['r_offset']
    return out

def _arm_rotimm(w):
    rot=((w>>8)&0xf)*2; v=w&0xff
    return ((v>>rot)|((v<<(32-rot))&0xffffffff)) if rot else v

def plt_stub_for_got(e,raw,v2f,got_slot):
    """Find the .plt stub (add ip,pc / add ip,ip / ldr pc,[ip,#imm]!) that resolves got_slot."""
    plt=e.get_section_by_name('.plt')
    if plt is None: return None
    va=plt['sh_addr']; end=va+plt['sh_size']
    while va+12<=end:
        w0=g32(raw,v2f(va)); w1=g32(raw,v2f(va+4)); w2=g32(raw,v2f(va+8))
        if (w0&0xfffff000)==0xe28fc000 and (w1&0xfffff000)==0xe28cc000 and (w2&0xfffff000)==0xe5bcf000:
            ip=(va+8+_arm_rotimm(w0))&0xffffffff
            ip=(ip+_arm_rotimm(w1))&0xffffffff
            ip=(ip+(w2&0xfff))&0xffffffff
            if ip==got_slot: return va
        va+=4
    return None

def find_init_bl_to(e,raw,v2f,syms,target):
    """Scan GalReceiver::init for the BL whose target == `target` (the onChannelOpened PLT stub).
    Returns (file_offset, vaddr) of that BL, or (None,None)."""
    init=syms[SYM_INIT]
    higher=[v for v in syms.values() if v>init]
    end=min(higher) if higher else init+0x400
    hit=(None,None)
    for va in range(init,end,4):
        w=g32(raw,v2f(va))
        if (w&0x0F000000)==0x0B000000 and (w>>28)&0xf!=0xf:   # BL, cond != 0xF
            imm=w&0xffffff
            if imm&0x800000: imm-=0x1000000
            if va+8+(imm<<2)==target:
                hit=(v2f(va),va)   # keep the LAST match (the call in init's tail)
    return hit

def resolve_galrefs(e,raw,n_slots):
    """Resolve the n_slots .galrefs values (in g_ref enum order) and the BL patch site.
    Returns (resolved_list, bl_file_offset, bl_vaddr)."""
    v2f=vaddr_to_foff(e)
    syms=dynsym_map(e); js=jump_slot_map(e)
    def sym(name):
        if name not in syms: sys.exit("ERROR: symbol not found in libgal: %s"%name)
        return syms[name]
    if 'memset' not in js: sys.exit("ERROR: no memset JUMP_SLOT relocation in libgal")
    if SYM_ONCHOPEN not in js: sys.exit("ERROR: no onChannelOpened JUMP_SLOT relocation in libgal")
    onch_stub=plt_stub_for_got(e,raw,v2f,js[SYM_ONCHOPEN])
    if onch_stub is None: sys.exit("ERROR: could not locate onChannelOpened PLT stub")
    resolved=[sym(SYM_REGISTER), sym(SYM_NAV_VTABLE), js['memset'], onch_stub, sym(SYM_MEDIA_VTABLE)]
    if len(resolved)!=n_slots:
        sys.exit("ERROR: g_ref slot count mismatch: navshim has %d, inject.py resolves %d"
                 %(n_slots,len(resolved)))
    bl_foff,bl_va=find_init_bl_to(e,raw,v2f,syms,onch_stub)
    return resolved,bl_foff,bl_va

def blob_image_and_relocs(blob_path):
    e=ELFFile(open(blob_path,'rb'))
    loads=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    hi=max(s['p_vaddr']+s['p_memsz'] for s in loads)
    img=bytearray(hi)
    for s in loads:
        img[s['p_vaddr']:s['p_vaddr']+s['p_filesz']]=s.data()[:s['p_filesz']]
    relocs=[]
    for s in e.iter_sections():
        if isinstance(s,RelocationSection):
            for r in s.iter_relocations():
                assert r['r_info_type']==R_RELATIVE, "blob must be RELATIVE-only"
                relocs.append(r['r_offset'])
    gr=e.get_section_by_name('.galrefs')
    galrefs=[gr['sh_addr']+i*4 for i in range(gr['sh_size']//4)]
    ds=e.get_section_by_name('.dynsym')
    ent={sy.name:sy['st_value'] for sy in ds.iter_symbols() if sy.name in (TRAMPOLINE_SYM,'gal_nav_inject')}
    return img,hi,relocs,galrefs,ent

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('gal'); ap.add_argument('blob'); ap.add_argument('-o',default=None)
    ap.add_argument('--write',action='store_true')
    ap.add_argument('--bl-offset',type=lambda x:int(x,0),default=None,
                    help='override the auto-located BL patch file offset (e.g. 0x7226c)')
    a=ap.parse_args()
    raw=bytearray(open(a.gal,'rb').read())
    e=ELFFile(io.BytesIO(raw))
    # --- ELF header ---
    e_phoff=g32(raw,0x1c); e_phentsize=g16(raw,0x2a); e_phnum=g16(raw,0x2c)
    loads=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    top=max(s['p_vaddr']+s['p_memsz'] for s in loads)
    dynseg=next(s for s in e.iter_segments() if s['p_type']=='PT_DYNAMIC')
    dyn_foff=dynseg['p_offset']
    # find DT_REL/RELSZ/RELCOUNT entries (offset within .dynamic)
    DT={17:'REL',18:'RELSZ',19:'RELENT',0x6ffffffa:'RELCOUNT'}
    dt={}
    o=dyn_foff
    while True:
        tag=g32(raw,o); val=g32(raw,o+4)
        if tag in DT: dt[DT[tag]]=(o,val)
        if tag==0: break
        o+=8
    rel_foff=dt['REL'][1]; rel_sz=dt['RELSZ'][1]; relcount=dt['RELCOUNT'][1]
    n_old=rel_sz//8
    print("libgal: DT_REL@0x%x size=0x%x (%d entries) RELCOUNT=%d phnum=%d"%(rel_foff,rel_sz,n_old,relcount,e_phnum))

    img,hi,brelocs,galrefs,ent=blob_image_and_relocs(a.blob)
    print("blob: image=0x%x bytes, %d blob-relocs, %d galrefs, %s"%(hi,len(brelocs),len(galrefs),ent))

    # --- AUTO-RESOLVE the per-firmware addresses from THIS libgal and bake them into .galrefs ---
    resolved,bl_auto,bl_va=resolve_galrefs(e,raw,len(galrefs))
    names=['R_REGISTER','R_NAV_VTABLE','R_GOT_MEMSET','R_ONCHOPEN','R_MEDIA_VTABLE']
    print("resolved galrefs from libgal:")
    for nm,val in zip(names,resolved): print("    %-12s = 0x%x"%(nm,val))
    for off,val in zip(galrefs,resolved):     # write resolved VAs into the blob's .galrefs slots
        s32(img,off,val)                       # (relativized below: at load g_ref[i]=base+val)
    BL_PATCH_FOFF = a.bl_offset if a.bl_offset is not None else bl_auto
    if BL_PATCH_FOFF is None:
        sys.exit("ERROR: could not auto-locate the init BL; pass --bl-offset 0xNNNN")
    bl_pc = bl_va if a.bl_offset is None else BL_PATCH_FOFF   # vaddr for the branch arithmetic
    print("BL patch site: file offset 0x%x (vaddr 0x%x)%s"%(
        BL_PATCH_FOFF, bl_pc, " [overridden]" if a.bl_offset is not None else " [auto]"))

    # --- placement: new RWX segment at end of file ---
    new_vaddr=align(top,0x1000)
    file_end=len(raw)
    # align the file offset so (off % page) == (vaddr % page)
    new_foff=align(file_end,0x1000)+ (new_vaddr & 0xfff)
    blob_foff=new_foff
    # relocate blob image to new_vaddr (so that after R_RELATIVE: base+new_vaddr+orig)
    for ro in brelocs:
        s32(img,ro, g32(img,ro)+new_vaddr)
    # new rel.dyn: [first relcount RELATIVE][our RELATIVE][rest]
    old=raw[rel_foff:rel_foff+rel_sz]
    head=old[:relcount*8]; tail=old[relcount*8:]
    ours=bytearray()
    for ro in brelocs:  ours+=struct.pack('<II', new_vaddr+ro, R_RELATIVE)
    for ro in galrefs:  ours+=struct.pack('<II', new_vaddr+ro, R_RELATIVE)
    n_new=len(brelocs)+len(galrefs)
    newrel=head+ours+tail
    # new rel table goes right after the blob in the new segment (8-byte aligned)
    rel_vaddr=align(new_vaddr+hi,8)
    rel_in_seg=rel_vaddr-new_vaddr
    seg_filesz=rel_in_seg+len(newrel)
    seg_memsz=seg_filesz
    rel_new_foff=blob_foff+rel_in_seg

    # --- BL patch ---
    tramp_vaddr=new_vaddr+ent[TRAMPOLINE_SYM]
    # ARM BL @ bl_pc(vaddr): imm24=((target-(pc+8))>>2)&0xffffff, cond=E
    off=(tramp_vaddr-(bl_pc+8))>>2
    bl=0xEB000000 | (off & 0xffffff)
    old_bl=g32(raw,BL_PATCH_FOFF)
    print("BL patch @0x%x: 0x%08x -> 0x%08x (target trampoline @0x%x)"%(BL_PATCH_FOFF,old_bl,bl,tramp_vaddr))
    print("new segment: vaddr=0x%x foff=0x%x filesz=0x%x ; rel.dyn@vaddr0x%x (%d entries)"%(
        new_vaddr,new_foff,seg_filesz,rel_vaddr,n_old+n_new))

    if not a.write:
        print("\n[dry-run] pass --write to actually patch the file.")
        return

    # ====== WRITE ======
    # 1) BL
    s32(raw,BL_PATCH_FOFF,bl)
    # 2) add a 7th phdr (room exists: phoff+phnum*32 .. up to DT_HASH at 0x118)
    ph_new_off=e_phoff+e_phnum*e_phentsize
    assert ph_new_off+e_phentsize<=0x118, "no room for the phdr"
    PT_LOAD=1; PF_R=4;PF_W=2;PF_X=1
    struct.pack_into('<8I',raw,ph_new_off, PT_LOAD,new_foff,new_vaddr,new_vaddr,
                     seg_filesz,seg_memsz, PF_R|PF_W|PF_X, 0x1000)
    s16(raw,0x2c,e_phnum+1)  # e_phnum++
    # 3) DT_REL -> new table, DT_RELSZ, DT_RELCOUNT
    s32(raw,dt['REL'][0]+4, rel_vaddr)
    s32(raw,dt['RELSZ'][0]+4, len(newrel))
    s32(raw,dt['RELCOUNT'][0]+4, relcount+n_new)
    # 4) append to the file: [pad][blob image][pad][newrel]
    while len(raw)<blob_foff: raw+=b'\x00'
    raw[blob_foff:blob_foff]=b''  # ensure
    raw+=b'\x00'*(blob_foff-len(raw)) if len(raw)<blob_foff else b''
    # align and write the blob
    if len(raw)<blob_foff: raw+=b'\x00'*(blob_foff-len(raw))
    raw[blob_foff:blob_foff+len(img)]=img
    # rel table
    if len(raw)<rel_new_foff: raw+=b'\x00'*(rel_new_foff-len(raw))
    raw[rel_new_foff:rel_new_foff+len(newrel)]=newrel
    out=a.o or (a.gal+'.patched')
    open(out,'wb').write(raw)
    print("WROTE",out,"size=0x%x"%len(raw))

if __name__=='__main__': main()
