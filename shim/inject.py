#!/usr/bin/env python3
# inject.py — injects navshim.so into libext.google.gal.receiver.so (no QNX tooling).
#  - new RWX PT_LOAD with the blob (a 7th phdr is written into free space in the table)
#  - .rel.dyn is moved into the new segment: [first RELATIVE][+ ours][rest]
#  - DT_REL/DT_RELSZ/DT_RELCOUNT are patched in place
#  - the `BL onChannelOpened` in the tail of GalReceiver::init  -> `BL init_trampoline`     (session up)
#  - the 1st `BL MessageRouter::shutdown` in GalReceiver::shutdown -> `BL shutdown_trampoline` (session down)
import sys, struct, io, argparse, hashlib
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection

R_RELATIVE=23
R_JUMP_SLOT=22
TRAMPOLINE_SYM='init_trampoline'
TRAMPOLINE_SYM2='shutdown_trampoline'
TRAMPOLINE_VIDEO_DATA='video_data_trampoline'
TRAMPOLINE_VIDEO_CONFIG='video_config_trampoline'

# libgal builds this shim has actually run on (sha256 of the STOCK file -> where it comes from).
# Function addresses are resolved from any build, but the object layouts the shim relies on
# (MessageRouter inside GalReceiver, its service table, the endpoint fields, the listener
# callbacks' arguments, IoBuffer) are known only from these: another build gets a warning.
KNOWN_LIBGAL={
    '7af20a37e9fc111058e54f6a94d432f0e4be8c8731bafdbd1d653e17ee180499': 'development unit (MST2_EU_SK_ZR_P0480T)',
}

# --- per-firmware addresses are AUTO-RESOLVED from the target libgal (no hardcoding) ---
# The blob's .galrefs slots (see navshim_fs.cpp g_ref[]) are filled here, in this exact order.
#   0 R_REGISTER     GalReceiver::registerService          (.dynsym)
#   1 R_NAV_VTABLE   _ZTV24NavigationStatusEndpoint        (.dynsym; navshim adds +8 at runtime)
#   2 R_GOT_MEMSET   memset GOT slot                       (R_ARM_JUMP_SLOT reloc)
#   3 R_ONCHOPEN     onChannelOpened PLT stub              (derived via the BL auto-locator)
#   4 R_MEDIA_VTABLE _ZTV27MediaPlaybackStatusEndpoint     (.dynsym; navshim adds +8 at runtime)
#   5 R_MR_SHUTDOWN  MessageRouter::shutdown               (.dynsym; call displaced from the 1st BL
#                                                           of GalReceiver::shutdown)
#   6 R_VIDEO_DATA   VideoSink::handleDataAvailable        (.dynsym; 0 if absent, video probe only)
#   7 R_VIDEO_CONFIG VideoSink::handleCodecConfig          (.dynsym; 0 if absent, video probe only)
#   8 R_QUEUE_OUT    MessageRouter::queueOutgoing          (.dynsym; 0 if absent, cluster sink sends)
#   9 R_ROUTE_GOT    MessageRouter::routeMessage GOT slot  (R_ARM_JUMP_SLOT; navshim repoints it)
#  10 R_ROUTE_FN     MessageRouter::routeMessage           (.dynsym; the wrapper calls it directly)
#  11 R_QOUT_GOT     MessageRouter::queueOutgoing GOT slot (R_ARM_JUMP_SLOT; navshim repoints it)
#  12 R_QOUT_FN      MessageRouter::queueOutgoing          (.dynsym; the wrapper calls it directly)
SYM_REGISTER='_ZN11GalReceiver15registerServiceEP20ProtocolEndpointBase'
SYM_NAV_VTABLE='_ZTV24NavigationStatusEndpoint'
SYM_ONCHOPEN='_ZN20ProtocolEndpointBase15onChannelOpenedEh'  # the call displaced from init's tail
SYM_MEDIA_VTABLE='_ZTV27MediaPlaybackStatusEndpoint'
SYM_INIT='_ZN11GalReceiver4initERK10shared_ptrI20IControllerCallbacksE'
SYM_MR_SHUTDOWN='_ZN13MessageRouter8shutdownEv'    # displaced from GalReceiver::shutdown's 1st BL
SYM_GAL_SHUTDOWN='_ZN11GalReceiver8shutdownEv'     # the teardown fn we patch (symmetric to init)
SYM_SENDVERSION='_ZN10Controller18sendVersionRequestEv'  # holds the advertised AA protocol version
SYM_VIDEO_DATA='_ZN9VideoSink19handleDataAvailableEyRK10shared_ptrI8IoBufferEj'
SYM_VIDEO_CONFIG='_ZN9VideoSink17handleCodecConfigEPvj'
SYM_QUEUE_OUT='_ZN13MessageRouter13queueOutgoingEhPvj'
SYM_SEND_SDR='_ZN10Controller28sendServiceDiscoveryResponseEv'
CLUSTER_SDR_HOOK='sdr_queue_hook'
SYM_MR_ROUTE='_ZN13MessageRouter12routeMessageEhRK10shared_ptrI8IoBufferE'  # every incoming message
SYM_MR_QOUT='_ZN13MessageRouter13queueOutgoingEhPvj'                        # every outgoing message

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

def dynsym_sizes(e):
    ds=e.get_section_by_name('.dynsym')
    return {sy.name:sy['st_size'] for sy in ds.iter_symbols() if sy.name}

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

def find_bl_to(e,raw,v2f,syms,func_sym,target,last):
    """Scan the function `func_sym` for the BL whose target == `target` (a PLT stub).
    `last=True` keeps the last match (init's tail call); `last=False` the first (shutdown's 1st BL).
    Returns (file_offset, vaddr) of that BL, or (None,None). Function end = the symbol's own size;
    only a symbol without one falls back to the next dynsym symbol (which may lie beyond local
    functions that follow, so a "last" match could then come from one of those)."""
    start=syms[func_sym]
    size=dynsym_sizes(e).get(func_sym,0)
    if size:
        end=start+size
    else:
        higher=[v for v in syms.values() if v>start]
        end=min(higher) if higher else start+0x400
    hit=(None,None)
    for va in range(start,end,4):
        w=g32(raw,v2f(va))
        if (w&0x0F000000)==0x0B000000 and (w>>28)&0xf!=0xf:   # BL, cond != 0xF
            imm=w&0xffffff
            if imm&0x800000: imm-=0x1000000
            if va+8+(imm<<2)==target:
                hit=(v2f(va),va)
                if not last:
                    return hit    # first match
    return hit

def resolve_galrefs(e,raw,n_slots):
    """Resolve the n_slots .galrefs values (in g_ref enum order) and BOTH BL patch sites.
    Returns (resolved_list, init_bl(foff,va), shutdown_bl(foff,va))."""
    v2f=vaddr_to_foff(e)
    syms=dynsym_map(e); js=jump_slot_map(e)
    def sym(name):
        if name not in syms: sys.exit("ERROR: symbol not found in libgal: %s"%name)
        return syms[name]
    if 'memset' not in js: sys.exit("ERROR: no memset JUMP_SLOT relocation in libgal")
    if SYM_ONCHOPEN not in js: sys.exit("ERROR: no onChannelOpened JUMP_SLOT relocation in libgal")
    if SYM_MR_SHUTDOWN not in js: sys.exit("ERROR: no MessageRouter::shutdown JUMP_SLOT relocation in libgal")
    onch_stub=plt_stub_for_got(e,raw,v2f,js[SYM_ONCHOPEN])
    if onch_stub is None: sys.exit("ERROR: could not locate onChannelOpened PLT stub")
    mrsd_stub=plt_stub_for_got(e,raw,v2f,js[SYM_MR_SHUTDOWN])
    if mrsd_stub is None: sys.exit("ERROR: could not locate MessageRouter::shutdown PLT stub")
    # R_MR_SHUTDOWN is the REAL function addr (dynsym), so gal_shutdown_hook calls it directly,
    # bypassing the PLT stub — the same convention used for R_REGISTER.
    # Video symbols are optional in the normal build: the extra slots remain zero and no target
    # entry is patched. --video-probe checks them explicitly before enabling the hooks.
    resolved=[sym(SYM_REGISTER), sym(SYM_NAV_VTABLE), js['memset'], onch_stub,
              sym(SYM_MEDIA_VTABLE), sym(SYM_MR_SHUTDOWN),
              syms.get(SYM_VIDEO_DATA,0), syms.get(SYM_VIDEO_CONFIG,0),
              syms.get(SYM_QUEUE_OUT,0)]
    # MessageRouter hooks (navshim install_router_hooks): GOT slot + real function, in that order.
    for nm in (SYM_MR_ROUTE, SYM_MR_QOUT):
        if nm not in js: sys.exit("ERROR: no JUMP_SLOT relocation for %s in libgal"%nm)
        resolved += [js[nm], sym(nm)]
    if len(resolved)!=n_slots:
        sys.exit("ERROR: g_ref slot count mismatch: navshim has %d, inject.py resolves %d"
                 %(n_slots,len(resolved)))
    init_bl=find_bl_to(e,raw,v2f,syms,SYM_INIT,onch_stub,last=True)        # tail call
    sd_bl  =find_bl_to(e,raw,v2f,syms,SYM_GAL_SHUTDOWN,mrsd_stub,last=False) # 1st BL
    return resolved,init_bl,sd_bl

def patch_protocol_version(e,raw,minor):
    """Rewrite the advertised AA protocol minor version.

    sendVersionRequest builds `msgid=1 | major=1 | minor` (BE uint16 each) and writes the minor with
    `strb rX,[r0,#5]`. Locate that store, take its source register, rewrite the `mov rX,#imm` feeding
    it — no absolute address baked in, so the patch survives a different libgal build.
    """
    if not 0 <= minor <= 255: sys.exit("ERROR: --aa-protocol-minor out of range")
    v2f=vaddr_to_foff(e); syms=dynsym_map(e)
    if SYM_SENDVERSION not in syms: sys.exit("ERROR: %s not found in libgal"%SYM_SENDVERSION)
    base=v2f(syms[SYM_SENDVERSION])
    strb=None; reg=None
    for off in range(base,base+0x100,4):                     # ARM, so 4-byte aligned
        w=g32(raw,off)
        if (w & 0xFFFF0FFF)==0xE5C00005:                     # strb rX,[r0,#5]
            strb=off; reg=(w>>12)&0xF; break
    if strb is None: sys.exit("ERROR: could not find the minor-version store in sendVersionRequest")
    mov=None; cur=None
    for off in range(base,strb,4):
        w=g32(raw,off)
        if (w & 0xFFFFFF00)==(0xE3A00000 | (reg<<12)):       # mov rX,#imm8
            mov=off; cur=w & 0xFF
    if mov is None: sys.exit("ERROR: could not find the `mov r%d,#imm` feeding it"%reg)
    s32(raw,mov,0xE3A00000 | (reg<<12) | minor)
    print("AA protocol: advertise 1.%d instead of 1.%d (mov r%d @file 0x%x)"%(minor,cur,reg,mov))
    return mov,reg,minor

def bl_target(word,pc):
    """Resolve an ARM-mode BL instruction at virtual address pc, or return None."""
    if (word & 0x0f000000) != 0x0b000000 or ((word >> 28) & 0xf) == 0xf:
        return None
    imm=word & 0xffffff
    if imm & 0x800000: imm-=0x1000000
    return (pc+8+(imm<<2)) & 0xffffffff

def b_target(word,pc):
    """Resolve an ARM B or BL instruction at virtual address pc."""
    if (word & 0x0e000000) != 0x0a000000 or ((word >> 28) & 0xf) == 0xf:
        return None
    imm=word&0xffffff
    if imm&0x800000: imm-=0x1000000
    return (pc+8+(imm<<2))&0xffffffff

def make_b(pc,target):
    off=(target-(pc+8))>>2
    if off < -0x800000 or off > 0x7fffff:
        sys.exit("ERROR: B from 0x%x to 0x%x out of ±32MB range"%(pc,target))
    return 0xea000000 | (off&0xffffff)

def verify_patched_output(path, expected):
    """Fail closed if the written ELF is not exactly the patch layout we calculated.

    This is deliberately independent of the in-memory bytearray used to create the output: a
    successful write, an accidental short write, or a future change to placement cannot silently
    produce an artifact whose branches/relocations point somewhere else.
    """
    raw=bytearray(open(path,'rb').read())
    e=ELFFile(io.BytesIO(raw))
    if e.elfclass != 32 or e['e_machine'] != 'EM_ARM' or e['e_type'] != 'ET_DYN':
        raise ValueError("patched output is not an ELF32 ARM shared object")
    loads=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    new=[s for s in loads if s['p_vaddr']==expected['new_vaddr'] and s['p_offset']==expected['new_foff']]
    if len(new)!=1:
        raise ValueError("patched output has no unique injected PT_LOAD segment")
    seg=new[0]
    if (seg['p_filesz'],seg['p_memsz'],seg['p_align']) != (expected['seg_filesz'],expected['seg_memsz'],0x1000):
        raise ValueError("injected PT_LOAD size/alignment differs from the calculated layout")
    if (seg['p_flags'] & 7) != 7 or seg['p_offset'] % seg['p_align'] != seg['p_vaddr'] % seg['p_align']:
        raise ValueError("injected PT_LOAD flags or file/virtual alignment are invalid")
    for other in loads:
        if other is seg:
            continue
        if not (seg['p_vaddr']+seg['p_memsz'] <= other['p_vaddr']
                or other['p_vaddr']+other['p_memsz'] <= seg['p_vaddr']):
            raise ValueError("injected PT_LOAD overlaps an existing load segment")
        if not (seg['p_offset']+seg['p_filesz'] <= other['p_offset']
                or other['p_offset']+other['p_filesz'] <= seg['p_offset']):
            raise ValueError("injected PT_LOAD overlaps an existing segment in the file")

    # Re-read the dynamic tags from the written file. DT_REL contains a virtual address, so use
    # the ELF's load map rather than relying on the common-but-not-guaranteed VA==file-offset layout.
    dyn=next(s for s in e.iter_segments() if s['p_type']=='PT_DYNAMIC')
    tags={tag.entry.d_tag:tag.entry.d_val for tag in dyn.iter_tags()}
    if tags.get('DT_REL') != expected['rel_vaddr'] or tags.get('DT_RELSZ') != expected['rel_sz'] \
            or tags.get('DT_RELCOUNT') != expected['relcount']:
        raise ValueError("DT_REL / DT_RELSZ / DT_RELCOUNT do not describe the injected relocation table")
    v2f=vaddr_to_foff(e)
    rel_off=v2f(expected['rel_vaddr'])
    if rel_off != expected['rel_foff']:
        raise ValueError("relocation table virtual/file mapping differs from the calculated layout")
    for i,offset in enumerate(expected['new_reloc_offsets']):
        roff=rel_off+(expected['old_relcount']+i)*8
        r_offset,r_info=struct.unpack_from('<II',raw,roff)
        if r_offset != offset or (r_info & 0xff) != R_RELATIVE or r_info >> 8:
            raise ValueError("injected relocation %d is not the expected symbol-free R_ARM_RELATIVE"%i)

    for label,foff,pc,target in (
            ('init',expected['init_foff'],expected['init_pc'],expected['init_tramp']),
            ('shutdown',expected['shutdown_foff'],expected['shutdown_pc'],expected['shutdown_tramp'])):
        actual=bl_target(g32(raw,foff),pc)
        if actual != target:
            raise ValueError("%s BL resolves to %s, not injected trampoline 0x%x" %
                             (label, 'non-BL' if actual is None else '0x%x'%actual, target))
    vp=expected.get('video_probe')
    if vp:
        for label,foff,pc,target,word in (
                ('video-data',vp['data_foff'],vp['data_pc'],vp['data_tramp'],vp['data_branch']),
                ('video-config',vp['config_foff'],vp['config_pc'],vp['config_tramp'],vp['config_branch'])):
            actual=b_target(g32(raw,foff),pc)
            if actual != target or g32(raw,foff) != word:
                raise ValueError("%s B does not resolve to injected trampoline 0x%x"%(label,target))
    cs=expected.get('cluster_sdr')
    if cs:
        foff,pc,target=cs
        if bl_target(g32(raw,foff),pc) != target:
            raise ValueError("cluster SDR BL does not resolve to sdr_queue_hook 0x%x"%target)
    minor_patch=expected.get('minor_patch')
    if minor_patch is not None:
        mov,reg,minor=minor_patch
        if g32(raw,mov) != (0xE3A00000 | (reg<<12) | minor):
            raise ValueError("advertised AA protocol minor patch was not written")
    print("VERIFIED: ELF layout, both trampolines, %d injected relocations%s" %
          (len(expected['new_reloc_offsets']), ', AA protocol 1.%d'%minor_patch[2] if minor_patch else ''))


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
    ent={sy.name:sy['st_value'] for sy in ds.iter_symbols()
         if sy.name in (TRAMPOLINE_SYM,TRAMPOLINE_SYM2,TRAMPOLINE_VIDEO_DATA,TRAMPOLINE_VIDEO_CONFIG,
                        CLUSTER_SDR_HOOK)}
    return img,hi,relocs,galrefs,ent

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('gal'); ap.add_argument('blob'); ap.add_argument('-o',default=None)
    ap.add_argument('--write',action='store_true')
    ap.add_argument('--bl-offset',type=lambda x:int(x,0),default=None,
                    help='override the auto-located init BL patch file offset (e.g. 0x7226c)')
    ap.add_argument('--bl-offset-shutdown',type=lambda x:int(x,0),default=None,
                    help='override the auto-located GalReceiver::shutdown BL patch file offset')
    ap.add_argument('--aa-protocol-minor',type=int,default=None,
                    help='advertise AA protocol 1.<N> instead of the firmware value. 1.7 makes the '
                         'phone send NavigationState (lanes, ETA) and STOP sending the legacy turn '
                         'events, which the shim no longer parses — so a session that negotiates '
                         'below 1.7 has no nav at all. Default: leave the firmware alone.')
    ap.add_argument('--video-probe',action='store_true',
                    help='also hook target VideoSink codec/data callbacks into the capture-only probe')
    ap.add_argument('--cluster-sink',action='store_true',
                    help='redirect the ServiceDiscoveryResponse send through sdr_queue_hook so the '
                         'blob can advertise its experimental cluster video sink')
    a=ap.parse_args()
    raw=bytearray(open(a.gal,'rb').read())
    e=ELFFile(io.BytesIO(raw))
    # The input must be the STOCK library. A stock libgal has no writable+executable segment; ours
    # adds one, so finding it means this file went through inject.py before (e.g. it was dumped
    # from a unit that already runs the mod).
    if any(s['p_type']=='PT_LOAD' and (s['p_flags']&7)==7 for s in e.iter_segments()):
        sys.exit("ERROR: %s is already patched (it has an injected RWX segment).\n"
                 "       Use the STOCK libext.google.gal.receiver.so of the unit (the backup made at install)."%a.gal)
    digest=hashlib.sha256(bytes(raw)).hexdigest()
    if digest in KNOWN_LIBGAL:
        print("libgal sha256 %s: known build, %s"%(digest,KNOWN_LIBGAL[digest]))
    else:
        print("WARNING: libgal sha256 %s is NOT a build this shim has run on."%digest)
        print("         Addresses are resolved from this file, but the object layouts are assumed to be")
        print("         those of the known builds. Test with care and keep the stock file for rollback.")
    minor_patch=None
    if a.aa_protocol_minor is not None:
        minor_patch=patch_protocol_version(e,raw,a.aa_protocol_minor)
    # --- ELF header ---
    e_phoff=g32(raw,0x1c); e_phentsize=g16(raw,0x2a); e_phnum=g16(raw,0x2c)
    loads=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    top=max(s['p_vaddr']+s['p_memsz'] for s in loads)
    dynseg=next(s for s in e.iter_segments() if s['p_type']=='PT_DYNAMIC')
    dyn_foff=dynseg['p_offset']
    # find DT_REL/RELSZ/RELCOUNT entries (offset within .dynamic)
    DT={17:'REL',18:'RELSZ',0x6ffffffa:'RELCOUNT'}
    dt={}
    o=dyn_foff
    while True:
        tag=g32(raw,o); val=g32(raw,o+4)
        if tag in DT: dt[DT[tag]]=(o,val)
        if tag==0: break
        o+=8
    # DT_REL is a virtual address: go through the load map for the file offset.
    rel_foff=vaddr_to_foff(e)(dt['REL'][1]); rel_sz=dt['RELSZ'][1]; relcount=dt['RELCOUNT'][1]
    n_old=rel_sz//8
    print("libgal: DT_REL@0x%x size=0x%x (%d entries) RELCOUNT=%d phnum=%d"%(rel_foff,rel_sz,n_old,relcount,e_phnum))

    img,hi,brelocs,galrefs,ent=blob_image_and_relocs(a.blob)
    print("blob: image=0x%x bytes, %d blob-relocs, %d galrefs, %s"%(hi,len(brelocs),len(galrefs),ent))

    # --- AUTO-RESOLVE the per-firmware addresses from THIS libgal and bake them into .galrefs ---
    resolved,(init_foff,init_va),(sd_foff,sd_va)=resolve_galrefs(e,raw,len(galrefs))
    names=['R_REGISTER','R_NAV_VTABLE','R_GOT_MEMSET','R_ONCHOPEN','R_MEDIA_VTABLE','R_MR_SHUTDOWN',
           'R_VIDEO_DATA','R_VIDEO_CONFIG','R_QUEUE_OUT',
           'R_ROUTE_GOT','R_ROUTE_FN','R_QOUT_GOT','R_QOUT_FN']
    print("resolved galrefs from libgal:")
    for nm,val in zip(names,resolved): print("    %-12s = 0x%x"%(nm,val))
    for off,val in zip(galrefs,resolved):     # write resolved VAs into the blob's .galrefs slots
        s32(img,off,val)                       # (relativized below: at load g_ref[i]=base+val)

    # Two BL patch sites: init tail (-> init_trampoline) and shutdown's 1st BL (-> shutdown_trampoline).
    INIT_FOFF = a.bl_offset if a.bl_offset is not None else init_foff
    if INIT_FOFF is None:
        sys.exit("ERROR: could not auto-locate the init BL; pass --bl-offset 0xNNNN")
    init_pc = init_va if a.bl_offset is None else INIT_FOFF   # vaddr for the branch arithmetic
    SD_FOFF = a.bl_offset_shutdown if a.bl_offset_shutdown is not None else sd_foff
    if SD_FOFF is None:
        sys.exit("ERROR: could not auto-locate the shutdown BL; pass --bl-offset-shutdown 0xNNNN")
    sd_pc = sd_va if a.bl_offset_shutdown is None else SD_FOFF
    print("init BL     @ file offset 0x%x (vaddr 0x%x)%s"%(
        INIT_FOFF, init_pc, " [overridden]" if a.bl_offset is not None else " [auto]"))
    print("shutdown BL @ file offset 0x%x (vaddr 0x%x)%s"%(
        SD_FOFF, sd_pc, " [overridden]" if a.bl_offset_shutdown is not None else " [auto]"))

    video_sites=None
    if a.video_probe:
        syms=dynsym_map(e); v2f=vaddr_to_foff(e)
        for name in (SYM_VIDEO_DATA,SYM_VIDEO_CONFIG):
            if name not in syms:
                sys.exit("ERROR: --video-probe requires symbol %s"%name)
        for name in (TRAMPOLINE_VIDEO_DATA,TRAMPOLINE_VIDEO_CONFIG):
            if name not in ent:
                sys.exit("ERROR: probe blob is missing %s"%name)
        video_sites=(syms[SYM_VIDEO_DATA],syms[SYM_VIDEO_CONFIG],
                     v2f(syms[SYM_VIDEO_DATA]),v2f(syms[SYM_VIDEO_CONFIG]))
        expected_video=((0xE1A0C00D,0xE92DD870,0xE24CB004,0xE24DD014),
                        (0xE5903034,0xE1A0C00D,0xE92DD800,0xE1A00003))
        for i,(va,foff) in enumerate(zip(video_sites[:2],video_sites[2:])):
            got=tuple(g32(raw,foff+4*j) for j in range(4))
            if got != expected_video[i]:
                sys.exit("ERROR: VideoSink probe prologue changed at 0x%x: %s"%(va,got))
        print("video probe sites: data @0x%x, config @0x%x (prologue verified)"%(video_sites[0],video_sites[1]))

    sdr_site=None
    if a.cluster_sink:
        syms=dynsym_map(e); v2f=vaddr_to_foff(e); js=jump_slot_map(e)
        for name in (SYM_QUEUE_OUT,SYM_SEND_SDR):
            if name not in syms: sys.exit("ERROR: --cluster-sink requires symbol %s"%name)
        if SYM_QUEUE_OUT not in js: sys.exit("ERROR: no queueOutgoing JUMP_SLOT relocation in libgal")
        if CLUSTER_SDR_HOOK not in ent: sys.exit("ERROR: blob lacks %s (build with CLUSTER_SINK=1)"%CLUSTER_SDR_HOOK)
        qo_stub=plt_stub_for_got(e,raw,v2f,js[SYM_QUEUE_OUT])
        if qo_stub is None: sys.exit("ERROR: could not locate queueOutgoing PLT stub")
        first=find_bl_to(e,raw,v2f,syms,SYM_SEND_SDR,qo_stub,last=False)
        last=find_bl_to(e,raw,v2f,syms,SYM_SEND_SDR,qo_stub,last=True)
        if first[0] is None or first!=last:
            sys.exit("ERROR: expected exactly one queueOutgoing BL in sendServiceDiscoveryResponse")
        sdr_site=first
        print("cluster sink: SDR queueOutgoing BL @0x%x (stub 0x%x)"%(sdr_site[1],qo_stub))

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

    # --- BL patches (ARM BL @ pc(vaddr): imm24=((target-(pc+8))>>2)&0xffffff, cond=E) ---
    def make_bl(pc, tramp_vaddr):
        off=(tramp_vaddr-(pc+8))>>2
        if off < -0x800000 or off > 0x7fffff:
            sys.exit("ERROR: BL from 0x%x to 0x%x out of ±32MB range"%(pc,tramp_vaddr))
        return 0xEB000000 | (off & 0xffffff)
    init_tramp=new_vaddr+ent[TRAMPOLINE_SYM]
    sd_tramp  =new_vaddr+ent[TRAMPOLINE_SYM2]
    init_bl=make_bl(init_pc, init_tramp)
    sd_bl  =make_bl(sd_pc,   sd_tramp)
    print("init BL patch     @0x%x: 0x%08x -> 0x%08x (trampoline @0x%x)"%(
        INIT_FOFF, g32(raw,INIT_FOFF), init_bl, init_tramp))
    print("shutdown BL patch @0x%x: 0x%08x -> 0x%08x (trampoline @0x%x)"%(
        SD_FOFF, g32(raw,SD_FOFF), sd_bl, sd_tramp))
    video_b=None
    if a.video_probe:
        vd_tramp=new_vaddr+ent[TRAMPOLINE_VIDEO_DATA]
        vc_tramp=new_vaddr+ent[TRAMPOLINE_VIDEO_CONFIG]
        video_b=(make_b(video_sites[0],vd_tramp),make_b(video_sites[1],vc_tramp))
        print("video B patch data @0x%x: 0x%08x -> 0x%08x (trampoline @0x%x)"%(
            video_sites[2],g32(raw,video_sites[2]),video_b[0],vd_tramp))
        print("video B patch config @0x%x: 0x%08x -> 0x%08x (trampoline @0x%x)"%(
            video_sites[3],g32(raw,video_sites[3]),video_b[1],vc_tramp))
    sdr_bl=None
    if sdr_site:
        sdr_hook=new_vaddr+ent[CLUSTER_SDR_HOOK]
        sdr_bl=make_bl(sdr_site[1],sdr_hook)
        print("cluster SDR BL patch @0x%x: 0x%08x -> 0x%08x (hook @0x%x)"%(
            sdr_site[0],g32(raw,sdr_site[0]),sdr_bl,sdr_hook))
    print("new segment: vaddr=0x%x foff=0x%x filesz=0x%x ; rel.dyn@vaddr0x%x (%d entries)"%(
        new_vaddr,new_foff,seg_filesz,rel_vaddr,n_old+n_new))

    if not a.write:
        print("\n[dry-run] pass --write to actually patch the file.")
        return

    # ====== WRITE ======
    # 1) BLs
    s32(raw,INIT_FOFF,init_bl)
    s32(raw,SD_FOFF,sd_bl)
    if a.video_probe:
        s32(raw,video_sites[2],video_b[0])
        s32(raw,video_sites[3],video_b[1])
    if sdr_bl is not None:
        s32(raw,sdr_site[0],sdr_bl)
    # 2) add one more phdr right after the table. What lies there is checked, not assumed: the
    #    entry may only cover zero padding or note sections (on the known builds it takes the place
    #    of .note.gnu.build-id, which nothing on the unit reads) and must end before the first other
    #    section (.hash on the known builds, at 0x118).
    ph_new_off=e_phoff+e_phnum*e_phentsize
    ph_new_end=ph_new_off+e_phentsize
    for sec in e.iter_sections():
        if sec['sh_type'] in ('SHT_NULL','SHT_NOBITS') or sec['sh_size']==0: continue
        lo=sec['sh_offset']; hi=lo+sec['sh_size']
        if lo<ph_new_end and hi>ph_new_off and sec['sh_type']!='SHT_NOTE':
            sys.exit("ERROR: no room for one more program header: section %s is at 0x%x"%(sec.name,lo))
    notes=[(sec['sh_offset'],sec['sh_offset']+sec['sh_size']) for sec in e.iter_sections()
           if sec['sh_type']=='SHT_NOTE']
    for off in range(ph_new_off,ph_new_end):
        if raw[off] and not any(lo<=off<hi for lo,hi in notes):
            sys.exit("ERROR: no room for one more program header: data at file offset 0x%x"%off)
    PT_LOAD=1; PF_R=4;PF_W=2;PF_X=1
    struct.pack_into('<8I',raw,ph_new_off, PT_LOAD,new_foff,new_vaddr,new_vaddr,
                     seg_filesz,seg_memsz, PF_R|PF_W|PF_X, 0x1000)
    s16(raw,0x2c,e_phnum+1)  # e_phnum++
    # 3) DT_REL -> new table, DT_RELSZ, DT_RELCOUNT
    s32(raw,dt['REL'][0]+4, rel_vaddr)
    s32(raw,dt['RELSZ'][0]+4, len(newrel))
    s32(raw,dt['RELCOUNT'][0]+4, relcount+n_new)
    # 4) append to the file: [pad][blob image][pad][newrel]
    if len(raw)<blob_foff: raw+=b'\x00'*(blob_foff-len(raw))
    raw[blob_foff:blob_foff+len(img)]=img
    # rel table
    if len(raw)<rel_new_foff: raw+=b'\x00'*(rel_new_foff-len(raw))
    raw[rel_new_foff:rel_new_foff+len(newrel)]=newrel
    out=a.o or (a.gal+'.patched')
    open(out,'wb').write(raw)
    print("WROTE",out,"size=0x%x"%len(raw))
    verify_patched_output(out, {
        'new_vaddr':new_vaddr, 'new_foff':new_foff,
        'seg_filesz':seg_filesz, 'seg_memsz':seg_memsz,
        'rel_vaddr':rel_vaddr, 'rel_foff':rel_new_foff, 'rel_sz':len(newrel),
        'relcount':relcount+n_new, 'old_relcount':relcount,
        'new_reloc_offsets':[new_vaddr+ro for ro in brelocs]+[new_vaddr+ro for ro in galrefs],
        'init_foff':INIT_FOFF, 'init_pc':init_pc, 'init_tramp':init_tramp,
        'shutdown_foff':SD_FOFF, 'shutdown_pc':sd_pc, 'shutdown_tramp':sd_tramp,
        'video_probe': ({'data_foff':video_sites[2], 'data_pc':video_sites[0],
                         'data_tramp':new_vaddr+ent[TRAMPOLINE_VIDEO_DATA], 'data_branch':video_b[0],
                         'config_foff':video_sites[3], 'config_pc':video_sites[1],
                         'config_tramp':new_vaddr+ent[TRAMPOLINE_VIDEO_CONFIG], 'config_branch':video_b[1]}
                        if a.video_probe else None),
        'minor_patch':minor_patch,
        'cluster_sdr': ((sdr_site[0],sdr_site[1],new_vaddr+ent[CLUSTER_SDR_HOOK]) if sdr_site else None),
    })

if __name__=='__main__': main()
