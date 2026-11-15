#!/usr/bin/env python3
# layout.py - reads the object layouts the shim depends on out of the TARGET libgal itself.
#
# inject.py resolves function ADDRESSES from the target's symbols. This module does the same for
# the LAYOUTS: where the MessageRouter sits inside GalReceiver, how its service table is indexed,
# the fields of a protocol endpoint, the vtable slots, the IoBuffer words, the listener pointers.
# Each value is decoded from the few instructions of a small exported function that uses it (or,
# for the vtable, from its relocations), so a build compiled with other offsets yields other
# values - and a build whose code does not look as expected yields an error instead of a library
# that would crash the SAL.
#
# What cannot be read as a single number (the argument conventions of the media listener
# callbacks, the endpoint fields the stock addDiscoveryInfo serialises) is checked by a
# fingerprint: a hash of the function's code with everything position-dependent masked out. Same
# fingerprint = same code = same conventions.
#
# No disassembler needed: ARM (A32) words are matched with masks. Pure functions of (ELFFile, raw
# bytes); tests/inject/test_layout.py runs them on the real libgal and on altered copies.
import struct, hashlib
from elftools.elf.relocation import RelocationSection

SYM_GR_REGISTER ='_ZN11GalReceiver15registerServiceEP20ProtocolEndpointBase'
SYM_MR_REGISTER ='_ZN13MessageRouter15registerServiceEP20ProtocolEndpointBase'
SYM_EP_OPENED   ='_ZN20ProtocolEndpointBase15onChannelOpenedEh'
SYM_NAV_START   ='_ZN24NavigationStatusEndpoint5startEv'
SYM_NAV_ROUTE   ='_ZN24NavigationStatusEndpoint12routeMessageEhtRK10shared_ptrI8IoBufferE'
SYM_NAV_DISC    ='_ZN24NavigationStatusEndpoint16addDiscoveryInfoEP24ServiceDiscoveryResponse'
SYM_NAV_STATUS  ='_ZN24NavigationStatusEndpoint22handleNavigationStatusERK16NavigationStatus'
SYM_NAV_VTABLE  ='_ZTV24NavigationStatusEndpoint'
SYM_MEDIA_STATUS='_ZN27MediaPlaybackStatusEndpoint25handleMediaPlaybackStatusERK19MediaPlaybackStatus'
SYM_MEDIA_META  ='_ZN27MediaPlaybackStatusEndpoint27handleMediaPlaybackMetadataERK21MediaPlaybackMetadata'
SYM_MEDIA_DISC  ='_ZN27MediaPlaybackStatusEndpoint16addDiscoveryInfoEP24ServiceDiscoveryResponse'
SYM_MEDIA_VTABLE='_ZTV27MediaPlaybackStatusEndpoint'
SYM_MEDIA_ROUTE ='_ZN27MediaPlaybackStatusEndpoint12routeMessageEhtRK10shared_ptrI8IoBufferE'

# The slots of the blob's .gallayout section (navshim_fs.cpp g_lay[]), in this exact order, with
# the values of the build the shim was written on. They are what the blob carries until inject.py
# overwrites them, and what --assume-reference-layout falls back to.
LAYOUT=[
    ('L_ROUTER_OFF',     4),     # MessageRouter inside GalReceiver
    ('L_TABLE_BIAS',     0x40),  # service table: word index = service id + bias
    ('L_EP_OPEN',        4),     # endpoint: u8 "channel is open"
    ('L_EP_CHANNEL',     5),     # endpoint: u8 channel number
    ('L_EP_ROUTER',      8),     # endpoint: MessageRouter*
    ('L_EP_SVCID',       0xc),   # endpoint: u8 service id
    ('L_VT_WORDS',       10),    # endpoint vtable: words in the symbol (2 header words included)
    ('L_VT_ROUTE',       7),     # ... word index of routeMessage
    ('L_VT_DISCOVERY',   9),     # ... word index of addDiscoveryInfo
    ('L_IO_REF',         4),     # shared_ptr<IoBuffer>: offset of the IoBuffer*
    ('L_IO_DATA',        0),     # IoBuffer: data pointer
    ('L_IO_START',       8),     # IoBuffer: offset of the first valid byte
    ('L_IO_END',         0xc),   # IoBuffer: offset past the last valid byte
    ('L_NAV_LISTENER',   0x2c),  # NavigationStatusEndpoint: listener pointer
    ('L_MEDIA_LISTENER', 0x18),  # MediaPlaybackStatusEndpoint: listener pointer
    ('L_MEDIA_OK',       1),     # 1 = the media listener conventions are the known ones
]
LAYOUT_NAMES=[n for n,_ in LAYOUT]
REFERENCE=dict(LAYOUT)
EP_OBJECT_BYTES=0x40             # the shim's endpoint objects (g_navEp, g_mediaEp, ...)

# Fingerprints (see fingerprint()) of the code whose conventions the shim relies on.
KNOWN_FINGERPRINTS={
    'nav_discovery':  ('6134d8f6aec76bb296bb953e2630c610c57a0099c7df53615cc2d32474f62138',),
    'media_status':   ('befeb62f019ec5748c5cba9a63a56a7e2d567b0aa9928964788a9e5fa2fc5c21',),
    'media_metadata': ('848274fa0fcb22248339f79631f12acddbc88ff569a5899ccda84d3c460fad64',),
    'media_discovery':('0279753a78efaf15f5b0d4975ef172be5bb91f3e56c82a1abb026020ba9b8592',),
}

class LayoutError(Exception):
    pass

# ----- ELF access ------------------------------------------------------------------------------
class Target(object):
    def __init__(self,e,raw):
        self.e=e; self.raw=raw
        ds=e.get_section_by_name('.dynsym')
        self.sym={}; self.size={}
        for sy in ds.iter_symbols():
            if sy.name:
                self.sym[sy.name]=sy['st_value']; self.size[sy.name]=sy['st_size']
        self.loads=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
    def v2f(self,va):
        for s in self.loads:
            if s['p_vaddr']<=va<s['p_vaddr']+s['p_filesz']:
                return s['p_offset']+(va-s['p_vaddr'])
        raise LayoutError("address 0x%x is not in the file"%va)
    def words(self,name,limit=None):
        """The function's code as (address, word) pairs. Needs the symbol AND its size."""
        if name not in self.sym or not self.sym[name]:
            raise LayoutError("symbol not found: %s"%name)
        n=self.size.get(name,0)
        if not n or n%4:
            raise LayoutError("symbol has no usable size: %s"%name)
        if limit: n=min(n,limit*4)
        va=self.sym[name]; off=self.v2f(va)
        return [(va+4*i,struct.unpack_from('<I',self.raw,off+4*i)[0]) for i in range(n//4)]
    def reloc_symbols(self,lo,hi):
        """address -> symbol name, for the symbol-bearing relocations in [lo,hi)."""
        out={}
        for s in self.e.iter_sections():
            if isinstance(s,RelocationSection):
                symtab=self.e.get_section(s['sh_link'])
                for r in s.iter_relocations():
                    if lo<=r['r_offset']<hi and r['r_info_sym']:
                        out[r['r_offset']]=symtab.get_symbol(r['r_info_sym']).name
        return out

# ----- A32 instruction matchers (condition AL only, unless said otherwise) -----------------------
def _rot(w):
    r=((w>>8)&0xf)*2; v=w&0xff
    return ((v>>r)|((v<<(32-r))&0xffffffff)) if r else v
def m_add_imm(w):
    """add rd, rn, #imm -> (rd, rn, imm)"""
    if (w&0xfff00000)==0xe2800000: return ((w>>12)&15,(w>>16)&15,_rot(w))
def m_sub_imm(w):
    if (w&0xfff00000)==0xe2400000: return ((w>>12)&15,(w>>16)&15,_rot(w))
def m_mov_imm(w):
    """mov rd, #imm -> (rd, imm)"""
    if (w&0xffef0000)==0xe3a00000: return ((w>>12)&15,_rot(w))
def _mem(w,base):
    # [rn, #+imm12], offset addressing (P=1, U=1, W=0)
    if (w&0xfff00000)==base: return ((w>>12)&15,(w>>16)&15,w&0xfff)
def m_ldr(w):  return _mem(w,0xe5900000)     # ldr  rt, [rn, #imm] -> (rt, rn, imm)
def m_ldrb(w): return _mem(w,0xe5d00000)     # ldrb rt, [rn, #imm]
def m_strb(w): return _mem(w,0xe5c00000)     # strb rt, [rn, #imm]
def m_ldr_idx4(w):
    """ldr rt, [rn, rm, lsl #2] -> (rt, rn, rm)"""
    if (w&0xfff00ff0)==0xe7900100: return ((w>>12)&15,(w>>16)&15,w&15)
def m_add_reg(w):
    """add rd, rn, rm (no shift) -> (rd, rn, rm)"""
    if (w&0xfff00ff0)==0xe0800000: return ((w>>12)&15,(w>>16)&15,w&15)
def m_rsb_reg(w):
    """rsb rd, rn, rm (rd = rm - rn) -> (rd, rn, rm)"""
    if (w&0xfff00ff0)==0xe0600000: return ((w>>12)&15,(w>>16)&15,w&15)
def m_sub_reg(w):
    """sub rd, rn, rm (rd = rn - rm) -> (rd, rn, rm)"""
    if (w&0xfff00ff0)==0xe0400000: return ((w>>12)&15,(w>>16)&15,w&15)
def m_cmp_imm(w):
    if (w&0xfff0f000)==0xe3500000: return ((w>>16)&15,_rot(w))
def is_branch(w):  return (w&0x0e000000)==0x0a000000 and (w>>28)!=0xf
def is_b_al(w):    return (w&0xff000000)==0xea000000

# ----- the single values -----------------------------------------------------------------------
def router_offset(t):
    """GalReceiver::registerService is `add r0, r0, #off ; b MessageRouter::registerService`."""
    c=t.words(SYM_GR_REGISTER)
    a=m_add_imm(c[0][1]) if c else None
    if not a or a[0]!=0 or a[1]!=0 or len(c)<2 or not is_b_al(c[1][1]):
        raise LayoutError("GalReceiver::registerService is not `add r0,r0,#off; b ...`")
    return a[2]

def service_table(t):
    """MessageRouter::registerService: `ldrb rA,[r1,#svcid] ... add rA,rA,#bias ; ldr rX,[r0,rA,lsl #2]`
    -> (service id offset in the endpoint, table bias)."""
    c=t.words(SYM_MR_REGISTER)
    svc=None; reg=None
    for i,(va,w) in enumerate(c):
        if svc is None:
            l=m_ldrb(w)
            if l and l[1]==1: svc=l[2]; reg=l[0]
            continue
        a=m_add_imm(w)
        if a and a[0]==reg and a[1]==reg and i+1<len(c):
            x=m_ldr_idx4(c[i+1][1])
            if x and x[1]==0 and x[2]==reg:
                return svc,a[2]
    raise LayoutError("MessageRouter::registerService: service id / table index not recognised")

def endpoint_open_channel(t):
    """onChannelOpened(this, channel): `strb r1,[r0,#channel]` and `mov rX,#1 ; strb rX,[r0,#open]`."""
    c=t.words(SYM_EP_OPENED)
    one=None; chan=None; opened=None
    for va,w in c:
        m=m_mov_imm(w)
        if m and m[1]==1: one=m[0]
        s=m_strb(w)
        if s and s[1]==0:
            if s[0]==1: chan=s[2]
            elif one is not None and s[0]==one: opened=s[2]
    if chan is None or opened is None or chan==opened:
        raise LayoutError("ProtocolEndpointBase::onChannelOpened: open flag / channel not recognised")
    return opened,chan

def endpoint_router(t,chan_off):
    """An endpoint sends with queueOutgoing(this->router, this->channel, ...): in
    NavigationStatusEndpoint::start the `ldrb r1,[rN,#channel]` has a `ldr r0,[rN,#router]` next to it."""
    c=t.words(SYM_NAV_START)
    for i,(va,w) in enumerate(c):
        l=m_ldrb(w)
        if l and l[0]==1 and l[2]==chan_off:
            for j in range(max(0,i-3),min(len(c),i+4)):
                r=m_ldr(c[j][1])
                if r and r[0]==0 and r[1]==l[1]:
                    return r[2]
    raise LayoutError("NavigationStatusEndpoint::start: the endpoint's MessageRouter* not recognised")

def vtable_slots(t,vtable,route,discovery):
    """Word indices (from the vtable symbol) of routeMessage and addDiscoveryInfo, and its length."""
    if vtable not in t.sym: raise LayoutError("symbol not found: %s"%vtable)
    va=t.sym[vtable]; n=t.size.get(vtable,0)//4
    rs=t.reloc_symbols(va,va+4*n)
    idx={name:(a-va)//4 for a,name in rs.items()}
    if route not in idx or discovery not in idx:
        raise LayoutError("%s: routeMessage / addDiscoveryInfo slot not found in its relocations"%vtable)
    return n,idx[route],idx[discovery]

def iobuffer(t):
    """routeMessage(this, channel, id, const shared_ptr<IoBuffer>& in r3) starts by loading the
    IoBuffer* and three of its words: data, start, end (payload = data+start .. data+end).
    -> (offset of the pointer in the shared_ptr, data, start, end)"""
    c=t.words(SYM_NAV_ROUTE,limit=24)
    ref=None; preg=None; got={}; start=None; data=None; end=None
    for va,w in c:
        l=m_ldr(w)
        if l:
            if preg is None and l[1]==3:                    # r3 still holds the reference
                ref=l[2]; preg=l[0]; continue
            if preg is not None and l[1]==preg and len(got)<3:
                got[l[0]]=l[2]; continue
        if preg is None: continue
        a=m_add_reg(w)
        if a and a[0] in got and {a[1],a[2]}<=set(got) and a[1]!=a[2] and data is None:
            data=a[0]; other=a[2] if a[1]==a[0] else a[1]
            start=other if start is None else start
            if start!=other: raise LayoutError("IoBuffer: inconsistent start word")
        r=m_rsb_reg(w)                                       # rd = rm - rn : end - start
        if r and r[1] in got and r[2] in got and end is None:
            end=r[2]
            if start is None: start=r[1]
            elif start!=r[1]: raise LayoutError("IoBuffer: inconsistent start word")
        s=m_sub_reg(w)                                       # rd = rn - rm : end - start
        if s and s[1] in got and s[2] in got and end is None:
            end=s[1]
            if start is None: start=s[2]
            elif start!=s[2]: raise LayoutError("IoBuffer: inconsistent start word")
        if data is not None and end is not None: break
    if ref is None or data is None or end is None or len({data,start,end})!=3:
        raise LayoutError("NavigationStatusEndpoint::routeMessage: IoBuffer words not recognised")
    return ref,got[data],got[start],got[end]

def listener_offset(t,func,what,need_null_check):
    """The handler starts with `ldr rX,[r0,#listener]` (this still in r0)."""
    c=t.words(func,limit=12)
    for i,(va,w) in enumerate(c):
        if is_branch(w): break
        l=m_ldr(w)
        if l and l[1]==0 and l[0]!=0:
            if need_null_check:
                k=m_cmp_imm(c[i+1][1]) if i+1<len(c) else None
                if not k or k[0]!=l[0] or k[1]!=0: continue
            return l[2]
        m=w&0x0fffffff
        if (m&0x0fe0f00f)==0x01a00000 and ((w>>12)&15)!=0:   # mov rd, r0: `this` moves, r0 may be reused
            continue
    raise LayoutError("%s: listener pointer not recognised"%what)

# ----- fingerprints ----------------------------------------------------------------------------
def fingerprint(t,name):
    """sha256 of a function's code with the position-dependent parts masked: branch displacements,
    the offsets of pc-relative literal loads, and the literal words themselves."""
    c=t.words(name)
    lo=c[0][0]; hi=c[-1][0]+4
    lit=set(); out=[]
    for va,w in c:
        if (w&0x0f7f0000)==0x051f0000:                       # ldr rX, [pc, #+/-imm]
            off=w&0xfff
            tgt=va+8+(off if w&0x00800000 else -off)
            if lo<=tgt<hi: lit.add(tgt)
    for va,w in c:
        if va in lit: w=0
        elif (w&0x0e000000)==0x0a000000 and (w>>28)!=0xf: w&=0xff000000
        elif (w&0x0f7f0000)==0x051f0000: w&=0xfffff000
        out.append(w)
    return hashlib.sha256(struct.pack('<%dI'%len(out),*out)).hexdigest()

def fingerprints(t):
    return {'nav_discovery':fingerprint(t,SYM_NAV_DISC),
            'media_status':fingerprint(t,SYM_MEDIA_STATUS),
            'media_metadata':fingerprint(t,SYM_MEDIA_META),
            'media_discovery':fingerprint(t,SYM_MEDIA_DISC)}

# ----- all of it -------------------------------------------------------------------------------
def resolve(e,raw):
    """-> (values: dict name -> int, errors: [str], warnings: [str]).
    errors: the navigation part cannot be trusted on this build (do not patch).
    warnings: an optional part is left out (media: L_MEDIA_OK = 0)."""
    t=Target(e,raw)
    v=dict(REFERENCE); errors=[]; warnings=[]
    def step(fn):
        try: return fn()
        except LayoutError as x: errors.append(str(x))
        except (struct.error,IndexError,KeyError) as x: errors.append("%s: %r"%(fn.__name__,x))
    r=step(lambda: router_offset(t))
    if r is not None: v['L_ROUTER_OFF']=r
    r=step(lambda: service_table(t))
    if r: v['L_EP_SVCID'],v['L_TABLE_BIAS']=r
    r=step(lambda: endpoint_open_channel(t))
    if r:
        v['L_EP_OPEN'],v['L_EP_CHANNEL']=r
        q=step(lambda: endpoint_router(t,v['L_EP_CHANNEL']))
        if q is not None: v['L_EP_ROUTER']=q
    r=step(lambda: vtable_slots(t,SYM_NAV_VTABLE,SYM_NAV_ROUTE,SYM_NAV_DISC))
    if r: v['L_VT_WORDS'],v['L_VT_ROUTE'],v['L_VT_DISCOVERY']=r
    r=step(lambda: iobuffer(t))
    if r: v['L_IO_REF'],v['L_IO_DATA'],v['L_IO_START'],v['L_IO_END']=r
    r=step(lambda: listener_offset(t,SYM_NAV_STATUS,"NavigationStatusEndpoint::handleNavigationStatus",False))
    if r is not None: v['L_NAV_LISTENER']=r

    # sanity: everything the shim writes must lie inside its endpoint objects and vtable copies
    if not errors:
        for k in ('L_EP_OPEN','L_EP_CHANNEL','L_EP_SVCID'):
            if v[k]>=EP_OBJECT_BYTES: errors.append("%s = 0x%x is outside the shim's endpoint object"%(k,v[k]))
        for k in ('L_EP_ROUTER','L_NAV_LISTENER'):
            if v[k]%4 or v[k]+4>EP_OBJECT_BYTES: errors.append("%s = 0x%x is outside the shim's endpoint object"%(k,v[k]))
        if len({v['L_EP_OPEN'],v['L_EP_CHANNEL'],v['L_EP_SVCID']})!=3 or \
           any(v['L_EP_ROUTER']<=v[k]<v['L_EP_ROUTER']+4 for k in ('L_EP_OPEN','L_EP_CHANNEL','L_EP_SVCID')):
            errors.append("endpoint fields overlap")
        if not (2<v['L_VT_ROUTE']<v['L_VT_WORDS']<=16 and 2<v['L_VT_DISCOVERY']<v['L_VT_WORDS']):
            errors.append("endpoint vtable: %d words, routeMessage %d, addDiscoveryInfo %d - not usable"
                          %(v['L_VT_WORDS'],v['L_VT_ROUTE'],v['L_VT_DISCOVERY']))
        if v['L_ROUTER_OFF']%4 or v['L_TABLE_BIAS']>0x400:
            errors.append("MessageRouter offset / table bias implausible")

    fp={}
    try: fp=fingerprints(t)
    except LayoutError as x: errors.append(str(x))
    if fp:
        # the nav endpoint is advertised by the stock addDiscoveryInfo, from fields the shim fills in
        if fp['nav_discovery'] not in KNOWN_FINGERPRINTS['nav_discovery']:
            errors.append("NavigationStatusEndpoint::addDiscoveryInfo differs from the known code "
                          "(the endpoint fields it serialises cannot be verified)")
        media=[k for k in ('media_status','media_metadata','media_discovery')
               if fp[k] not in KNOWN_FINGERPRINTS[k]]
        m=None
        try:
            m=listener_offset(t,SYM_MEDIA_STATUS,"MediaPlaybackStatusEndpoint::handleMediaPlaybackStatus",True)
            n2,r2,d2=vtable_slots(t,SYM_MEDIA_VTABLE,SYM_MEDIA_ROUTE,SYM_MEDIA_DISC)
            if (n2,r2,d2)!=(v['L_VT_WORDS'],v['L_VT_ROUTE'],v['L_VT_DISCOVERY']): media.append('media vtable')
            if m%4 or m+4>EP_OBJECT_BYTES: media.append('media listener offset')
        except LayoutError as x:
            media.append(str(x))
        if m is not None: v['L_MEDIA_LISTENER']=m
        if media:
            v['L_MEDIA_OK']=0
            warnings.append("media now-playing is left out on this build: the media listener code "
                            "differs from the known one (%s)"%', '.join(media))
    return v,errors,warnings,fp

if __name__=='__main__':
    import sys, io
    from elftools.elf.elffile import ELFFile
    raw=open(sys.argv[1],'rb').read()
    v,errors,warnings,fp=resolve(ELFFile(io.BytesIO(raw)),raw)
    for n in LAYOUT_NAMES:
        print("  %-17s = 0x%-4x%s"%(n,v[n],'' if v[n]==REFERENCE[n] else '   (reference build: 0x%x)'%REFERENCE[n]))
    for k in sorted(fp): print("  fingerprint %-16s %s%s"%(k,fp[k],'' if fp[k] in KNOWN_FINGERPRINTS[k] else '   UNKNOWN'))
    for w in warnings: print("WARNING:",w)
    for x in errors: print("ERROR:",x)
    sys.exit(1 if errors else 0)
