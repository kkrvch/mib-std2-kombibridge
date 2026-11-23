#!/usr/bin/env python3
"""Host test of shim/layout.py and of inject.py's refusals.

Needs the stock libgal of a unit (firmware-derived, never in the repository): the path is argv[1],
default shim/lib/libext.google.gal.receiver.so (where build.sh puts it). Without it the test is
skipped, not failed.

What it shows:
  1. on the real libgal every layout value is found and the fingerprints are known;
  2. the values are READ, not assumed: altering the offset in the instruction that carries it
     changes the value that comes out (a build compiled with other offsets would be followed);
  3. code that does not look as expected is an error (inject.py then writes nothing), and a
     changed media listener only switches the media endpoint off;
  4. code that merely moved (other branch targets) keeps its fingerprint;
  5. inject.py refuses an already patched library and an unverifiable one.
The altered copies are synthetic: they stand in for "another firmware build", which is not
available to test against.
"""
import io, os, struct, subprocess, sys, tempfile
HERE=os.path.dirname(os.path.abspath(__file__))
SHIM=os.path.normpath(os.path.join(HERE,'..','..','shim'))
sys.path.insert(0,SHIM)
import layout
from elftools.elf.elffile import ELFFile

failed=0
def check(what,cond,detail=''):
    global failed
    print("%-64s %s"%(what,'ok' if cond else 'FAILED '+str(detail)))
    if not cond: failed+=1

def main():
    path=sys.argv[1] if len(sys.argv)>1 else os.path.join(SHIM,'lib','libext.google.gal.receiver.so')
    if not os.path.isfile(path):
        print("SKIPPED: no stock libgal at %s"%path); return 0
    raw0=open(path,'rb').read()
    def resolve(raw): return layout.resolve(ELFFile(io.BytesIO(bytes(raw))),bytes(raw))
    t=layout.Target(ELFFile(io.BytesIO(raw0)),raw0)
    v,errors,warnings,fp=resolve(raw0)

    # 1. the real library
    check("real libgal: no errors, no warnings",not errors and not warnings,(errors,warnings))
    check("real libgal: every slot has a value",all(n in v for n in layout.LAYOUT_NAMES))
    check("real libgal: fingerprints known",all(fp[k] in layout.KNOWN_FINGERPRINTS[k] for k in fp),fp)
    check("real libgal: media endpoint on",v['L_MEDIA_OK']==1)
    for k in ('L_EP_OPEN','L_EP_CHANNEL','L_EP_SVCID','L_EP_ROUTER','L_NAV_LISTENER','L_MEDIA_LISTENER'):
        check("real libgal: %s inside the endpoint object"%k,v[k]<layout.EP_OBJECT_BYTES)

    # helpers to alter one instruction: the 12-bit immediate of the word at a function's index
    def word_at(sym,pred,start=0):
        for i,(va,w) in enumerate(t.words(sym)):
            if i>=start and pred(w): return t.v2f(va),w,i
        raise AssertionError("instruction not found in "+sym)
    def altered(edits):
        raw=bytearray(raw0)
        for off,w in edits: struct.pack_into('<I',raw,off,w)
        return raw
    def imm(w,new): return (w&~0xfff)|new

    # 2. values follow the code
    off,w,_=word_at(layout.SYM_GR_REGISTER,lambda w: layout.m_add_imm(w))
    r=resolve(altered([(off,imm(w,8))]))
    check("router offset follows `add r0,r0,#off`",r[0]['L_ROUTER_OFF']==8 and not r[1],r[1])

    off,w,_=word_at(layout.SYM_MR_REGISTER,lambda w: layout.m_ldrb(w))
    r=resolve(altered([(off,imm(w,0x10))]))
    check("service id offset follows MessageRouter::registerService",r[0]['L_EP_SVCID']==0x10 and not r[1],r[1])

    off,w,_=word_at(layout.SYM_MR_REGISTER,lambda w: layout.m_add_imm(w))
    r=resolve(altered([(off,imm(w,0x44))]))
    check("table bias follows MessageRouter::registerService",r[0]['L_TABLE_BIAS']==0x44 and not r[1],r[1])

    chan=v['L_EP_CHANNEL']
    off,w,_=word_at(layout.SYM_NAV_START,lambda w: layout.m_ldr(w) and layout.m_ldr(w)[0]==0 and layout.m_ldr(w)[2]==v['L_EP_ROUTER'],
                    start=[i for i,(va,x) in enumerate(t.words(layout.SYM_NAV_START))
                           if layout.m_ldrb(x) and layout.m_ldrb(x)[2]==chan][0]-3)
    r=resolve(altered([(off,imm(w,0x10))]))
    check("endpoint router offset follows NavigationStatusEndpoint::start",r[0]['L_EP_ROUTER']==0x10 and not r[1],r[1])

    off,w,_=word_at(layout.SYM_NAV_STATUS,lambda w: layout.m_ldr(w) and layout.m_ldr(w)[1]==0)
    r=resolve(altered([(off,imm(w,0x30))]))
    check("nav listener offset follows handleNavigationStatus",r[0]['L_NAV_LISTENER']==0x30 and not r[1],r[1])

    # IoBuffer: pointer in the shared_ptr, then start and end words
    ws=t.words(layout.SYM_NAV_ROUTE,limit=24); edits=[]; seen=0
    for va,w in ws:
        l=layout.m_ldr(w)
        if not l: continue
        if seen==0 and l[1]==3: edits.append((t.v2f(va),imm(w,8))); seen=1; preg=l[0]
        elif seen and l[1]==preg and l[2]==v['L_IO_START']: edits.append((t.v2f(va),imm(w,0x10)))
        elif seen and l[1]==preg and l[2]==v['L_IO_END']: edits.append((t.v2f(va),imm(w,0x14)))
    r=resolve(altered(edits))
    check("IoBuffer words follow routeMessage's prologue",
          (r[0]['L_IO_REF'],r[0]['L_IO_DATA'],r[0]['L_IO_START'],r[0]['L_IO_END'])==(8,0,0x10,0x14) and not r[1],
          (r[0],r[1]))

    # 3. unrecognisable / changed code
    off,w,_=word_at(layout.SYM_GR_REGISTER,lambda w: True)
    r=resolve(altered([(off,0xe1a00000)]))                       # nop instead of the add
    check("unrecognisable GalReceiver::registerService is an error",len(r[1])==1,r[1])

    off,w,_=word_at(layout.SYM_NAV_DISC,lambda w: layout.m_ldr(w) and layout.m_ldr(w)[2]==0x24)
    r=resolve(altered([(off,imm(w,0x28))]))
    check("changed nav addDiscoveryInfo is an error",len(r[1])==1 and 'addDiscoveryInfo' in r[1][0],r[1])

    off,w,_=word_at(layout.SYM_MEDIA_STATUS,lambda w: layout.m_ldr(w) and layout.m_ldr(w)[1]==0)
    r=resolve(altered([(off,imm(w,0x1c))]))
    check("changed media listener: media off, navigation still fine",
          r[0]['L_MEDIA_OK']==0 and not r[1] and len(r[2])==1,(r[1],r[2]))

    # 4. relocated code keeps its fingerprint
    off,w,_=word_at(layout.SYM_NAV_DISC,lambda w: (w&0xff000000)==0xeb000000)
    r=resolve(altered([(off,w+1)]))                              # the bl goes somewhere else
    check("moved branch target: same fingerprint, no error",not r[1] and r[3]['nav_discovery']==fp['nav_discovery'],r[1])

    # 5. inject.py end to end (needs a built blob: shim/build/navshim.so)
    blob=os.path.join(SHIM,'build','navshim.so')
    if os.path.isfile(blob):
        tmp=tempfile.mkdtemp()
        def inject(src,out):
            p=subprocess.run([sys.executable,os.path.join(SHIM,'inject.py'),src,blob,'-o',out,'--write',
                              '--aa-protocol-minor','7'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            return p.returncode,p.stdout.decode('utf-8','replace')
        good=os.path.join(tmp,'good.so'); rc,out=inject(path,good)
        check("inject.py patches the real libgal",rc==0 and 'VERIFIED' in out and os.path.isfile(good),out[-300:])
        again=os.path.join(tmp,'again.so'); rc,out=inject(good,again)
        check("inject.py refuses an already patched libgal",rc!=0 and 'already patched' in out and not os.path.exists(again),out[-200:])
        off,w,_=word_at(layout.SYM_GR_REGISTER,lambda w: True)
        bad=os.path.join(tmp,'bad_in.so'); open(bad,'wb').write(altered([(off,0xe1a00000)]))
        badout=os.path.join(tmp,'bad_out.so'); rc,out=inject(bad,badout)
        check("inject.py writes nothing when a layout check fails",rc!=0 and not os.path.exists(badout) and 'LAYOUT:' in out,out[-200:])
        off,w,_=word_at(layout.SYM_MEDIA_STATUS,lambda w: layout.m_ldr(w) and layout.m_ldr(w)[1]==0)
        med=os.path.join(tmp,'med_in.so'); open(med,'wb').write(altered([(off,imm(w,0x1c))]))
        medout=os.path.join(tmp,'med_out.so'); rc,out=inject(med,medout)
        check("inject.py patches without media when only media differs",
              rc==0 and os.path.isfile(medout) and 'media now-playing is left out' in out,out[-300:])
    else:
        print("(inject.py end-to-end part skipped: build the shim first, shim/build/navshim.so)")

    print("all tests passed" if not failed else "TESTS FAILED")
    return 1 if failed else 0

if __name__=='__main__':
    sys.exit(main())
