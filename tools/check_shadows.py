#!/usr/bin/env python3
"""check_shadows.py - are the shadowed HMI classes still what the shadows were made from?

AAtoKombi.jar replaces ("shadows") a number of stock classes of the HMI: every .java under jar/src
whose class also exists in MIBHMI.jar. A shadow is a copy of the stock class of ONE firmware with
the mod's changes in it, so on another firmware it is only right if the stock class is the same
there. javac cannot tell: it compiles the shadow against the rest of MIBHMI.jar, not against the
class it replaces.

Two checks, both against the MIBHMI.jar the build is given:

  stock   before compiling: the stock class (and its nested classes) has the fingerprint recorded
          in jar/shadows.lock. The fingerprint is symbolic - superclass, interfaces, fields with
          their constant values, and every method's code with the constant-pool references
          resolved to names - so it does not depend on how the class file happens to be laid out,
          only on what the class is and does. A difference names the members that differ.

  api     after compiling: the shadow offers everything the stock class offers to the rest of the
          HMI - same superclass, all its interfaces, every non-private field, method and
          constructor with the same descriptor and static-ness. Other stock classes are compiled
          against the stock one and would fail at run time (NoSuchMethodError) on a gap.

  update  rewrite jar/shadows.lock from this MIBHMI.jar. Only after the shadows themselves were
          brought in line with that firmware's classes.

Exit code 1 on a failed check. Python 3, standard library only.
"""
import sys, os, json, struct, zipfile, hashlib

# ----- class file reader ------------------------------------------------------------------------
class ClassFile(object):
    def __init__(self,data):
        self.d=data; self.p=0
        if self.u4()!=0xCAFEBABE: raise ValueError("not a class file")
        self.u2(); self.u2()
        n=self.u2(); self.cp=[None]*n; i=1
        while i<n:
            t=self.u1()
            if t==1: l=self.u2(); self.cp[i]=('utf8',self.d[self.p:self.p+l].decode('utf-8','replace')); self.p+=l
            elif t==3: self.cp[i]=('int',struct.unpack('>i',self.take(4))[0])
            elif t==4: self.cp[i]=('float',self.take(4).hex())
            elif t==5: self.cp[i]=('long',struct.unpack('>q',self.take(8))[0]); i+=1
            elif t==6: self.cp[i]=('double',self.take(8).hex()); i+=1
            elif t==7: self.cp[i]=('class',self.u2())
            elif t==8: self.cp[i]=('string',self.u2())
            elif t in (9,10,11): self.cp[i]=(('field','method','imethod')[t-9],self.u2(),self.u2())
            elif t==12: self.cp[i]=('nat',self.u2(),self.u2())
            elif t==15: self.cp[i]=('handle',self.u1(),self.u2())
            elif t==16: self.cp[i]=('mtype',self.u2())
            elif t==18: self.cp[i]=('indy',self.u2(),self.u2())
            else: raise ValueError("constant pool tag %d"%t)
            i+=1
        self.flags=self.u2()
        self.name=self.cls(self.u2()); sup=self.u2(); self.super=self.cls(sup) if sup else ''
        self.interfaces=sorted(self.cls(self.u2()) for _ in range(self.u2()))
        self.fields=[self.member(False) for _ in range(self.u2())]
        self.methods=[self.member(True) for _ in range(self.u2())]
    def take(self,n): b=self.d[self.p:self.p+n]; self.p+=n; return b
    def u1(self): v=self.d[self.p]; self.p+=1; return v
    def u2(self): v=struct.unpack_from('>H',self.d,self.p)[0]; self.p+=2; return v
    def u4(self): v=struct.unpack_from('>I',self.d,self.p)[0]; self.p+=4; return v
    def utf(self,i): return self.cp[i][1]
    def cls(self,i): return self.utf(self.cp[i][1])
    def const(self,i):
        """A constant-pool entry as text that does not depend on its index."""
        c=self.cp[i]; t=c[0]
        if t=='utf8': return 'utf8:'+c[1]
        if t in ('int','long','float','double'): return '%s:%s'%(t,c[1])
        if t=='class': return 'class:'+self.utf(c[1])
        if t=='string': return 'string:'+self.utf(c[1])
        if t in ('field','method','imethod'):
            nat=self.cp[c[2]]
            return '%s:%s.%s:%s'%(t,self.cls(c[1]),self.utf(nat[1]),self.utf(nat[2]))
        if t=='nat': return 'nat:%s:%s'%(self.utf(c[1]),self.utf(c[2]))
        return '%s:%s'%(t,','.join(str(x) for x in c[1:]))
    def member(self,is_method):
        flags=self.u2(); name=self.utf(self.u2()); desc=self.utf(self.u2())
        m={'flags':flags,'name':name,'desc':desc,'code':None,'value':None,'throws':[]}
        for _ in range(self.u2()):
            an=self.utf(self.u2()); ln=self.u4(); end=self.p+ln
            if an=='ConstantValue': m['value']=self.const(self.u2())
            elif an=='Exceptions': m['throws']=sorted(self.cls(self.u2()) for _ in range(self.u2()))
            elif an=='Code' and is_method:
                self.u2(); self.u2(); cl=self.u4(); code=self.take(cl)
                handlers=[]
                for _ in range(self.u2()):
                    a,b,c,t=self.u2(),self.u2(),self.u2(),self.u2()
                    handlers.append('%d-%d>%d:%s'%(a,b,c,self.cls(t) if t else '*'))
                m['code']=self.normalize(code)+'|'+';'.join(handlers)
            self.p=end
        return m

    # operand bytes after the opcode; -1 = variable (switches, wide)
    OPLEN=[0]*256
    for _o,_l in ((16,1),(17,2),(18,1),(19,2),(20,2)): OPLEN[_o]=_l
    for _o in list(range(21,26))+list(range(54,59))+[169,188]: OPLEN[_o]=1
    for _o in [132]+list(range(153,169))+[198,199]: OPLEN[_o]=2
    OPLEN[132]=2
    for _o in range(178,185): OPLEN[_o]=2
    for _o in (187,189,192,193): OPLEN[_o]=2
    OPLEN[185]=4; OPLEN[186]=4; OPLEN[197]=3; OPLEN[200]=4; OPLEN[201]=4
    OPLEN[170]=-1; OPLEN[171]=-1; OPLEN[196]=-1
    CP1=(18,); CP2=(19,20,178,179,180,181,182,183,184,185,186,187,189,192,193,197)

    def normalize(self,code):
        out=[]; i=0; n=len(code)
        while i<n:
            op=code[i]; l=self.OPLEN[op]
            if op in self.CP1:
                out.append('%d %s'%(op,self.const(code[i+1]))); i+=2
            elif op in self.CP2:
                idx=struct.unpack_from('>H',code,i+1)[0]
                out.append('%d %s %s'%(op,self.const(idx),code[i+3:i+1+l].hex())); i+=1+l
            elif op==170:                                   # tableswitch
                j=(i+4)&~3; lo,hi=struct.unpack_from('>ii',code,j+4); end=j+12+4*(hi-lo+1)
                out.append('170 '+code[j:end].hex()); i=end
            elif op==171:                                   # lookupswitch
                j=(i+4)&~3; np=struct.unpack_from('>i',code,j+4)[0]; end=j+8+8*np
                out.append('171 '+code[j:end].hex()); i=end
            elif op==196:                                   # wide
                l=5 if code[i+1]==132 else 3
                out.append('196 '+code[i+1:i+1+l].hex()); i+=1+l
            else:
                out.append('%d %s'%(op,code[i+1:i+1+l].hex())); i+=1+l
        return '\n'.join(out)

ACC_PRIVATE=0x0002; ACC_STATIC=0x0008; ACC_SYNTHETIC=0x1000
# flags that are part of what a member IS for its users (visibility, static, final, abstract ...)
MEMBER_FLAGS=0x0001|0x0002|0x0004|0x0008|0x0010|0x0020|0x0100|0x0400

def h(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()[:20]

def fingerprint(cf):
    """{'class': hash of the whole, 'members': {signature: hash}} - symbolic, layout-independent."""
    members={}
    for f in cf.fields:
        members['field %s:%s'%(f['name'],f['desc'])]=h('%x|%s'%(f['flags']&MEMBER_FLAGS,f['value']))
    for m in cf.methods:
        members['method %s%s'%(m['name'],m['desc'])]=h('%x|%s|%s'%(m['flags']&MEMBER_FLAGS,','.join(m['throws']),m['code']))
    head='%s|%s|%s|%x'%(cf.name,cf.super,','.join(cf.interfaces),cf.flags&0x0611)
    whole=h(head+'||'+'||'.join('%s=%s'%(k,members[k]) for k in sorted(members)))
    return {'class':whole,'head':h(head),'members':members}

# ----- which classes are shadows -----------------------------------------------------------------
def source_classes(src):
    out=[]
    for root,_,files in os.walk(src):
        for f in files:
            if f.endswith('.java'):
                rel=os.path.relpath(os.path.join(root,f),src).replace(os.sep,'/')
                out.append(rel[:-5])
    return sorted(out)

def shadow_entries(z,src):
    """stock class-file names in MIBHMI.jar that the sources replace: Foo and Foo$..."""
    names=set(z.namelist()); tops=[c for c in source_classes(src) if c+'.class' in names]
    out={}
    for c in tops:
        out[c]=sorted(n[:-6] for n in names if n.endswith('.class') and (n==c+'.class' or n.startswith(c+'$')))
    return out

def stock_fingerprints(jar,src):
    z=zipfile.ZipFile(jar); out={}
    for top,entries in sorted(shadow_entries(z,src).items()):
        for e in entries:
            out[e]=fingerprint(ClassFile(z.read(e+'.class')))
    return out

# ----- the checks --------------------------------------------------------------------------------
def cmd_update(jar,src,lock):
    fp=stock_fingerprints(jar,src)
    data={'comment':'Fingerprints of the stock HMI classes the sources under jar/src shadow, taken from the '
                    'MIBHMI.jar the shadows were made from. Written by tools/check_shadows.py update.',
          'mibhmi':hashlib.sha256(open(jar,'rb').read()).hexdigest() if os.path.getsize(jar)<512*1024*1024 else '',
          'classes':fp}
    with open(lock,'w') as f: json.dump(data,f,indent=1,sort_keys=True); f.write('\n')
    print("shadows.lock: %d stock classes recorded"%len(fp)); return 0

def cmd_stock(jar,src,lock):
    if not os.path.isfile(lock):
        print("ERROR: %s not found (run: tools/check_shadows.py update ...)"%lock); return 1
    want=json.load(open(lock))['classes']; got=stock_fingerprints(jar,src)
    bad=0
    for c in sorted(set(want)|set(got)):
        if c not in got:
            # recorded, but this firmware has no such class: the shadow would ADD a class
            print("  DIFFERENT  %s: not in this MIBHMI.jar (the shadow was made for a class this firmware lacks)"%c); bad+=1; continue
        if c not in want:
            print("  NOT RECORDED  %s: shadowed here but not in shadows.lock"%c); bad+=1; continue
        if got[c]['class']==want[c]['class']: continue
        bad+=1; gm,wm=got[c]['members'],want[c]['members']
        diff=sorted(k for k in set(gm)|set(wm) if gm.get(k)!=wm.get(k))
        what=[]
        if got[c]['head']!=want[c]['head']: what.append('superclass / interfaces / class flags')
        for k in diff[:12]:
            what.append(('added ' if k not in wm else 'removed ' if k not in gm else 'changed ')+k)
        if len(diff)>12: what.append('... and %d more members'%(len(diff)-12))
        print("  DIFFERENT  %s"%c)
        for w in what: print("               "+w)
    if bad:
        print("ERROR: %d stock class%s of this MIBHMI.jar differ from the ones the shadows were made from."
              %(bad,'' if bad==1 else 'es'))
        print("       The shadows in jar/src replace these classes wholesale; on this firmware they may be")
        print("       wrong in ways the compiler cannot see. Compare each named class with its shadow and")
        print("       port the difference, then record the new state: tools/check_shadows.py update.")
        return 1
    print("shadows: %d stock classes are the ones the shadows were made from"%len(got)); return 0

def cmd_api(jar,src,classes):
    z=zipfile.ZipFile(jar); bad=0; n=0
    for top,entries in sorted(shadow_entries(z,src).items()):
        for e in entries:
            stock=ClassFile(z.read(e+'.class')); path=os.path.join(classes,e+'.class')
            if not os.path.isfile(path):
                if e==top:
                    print("  MISSING  %s: no compiled class"%e); bad+=1
                else:
                    # a nested class of the stock one that the shadow does not have: anything in the
                    # HMI that names it would now find the STOCK nested class next to OUR outer one
                    if not (stock.flags&ACC_SYNTHETIC) and not e.rsplit('$',1)[1].isdigit():
                        print("  NOTE     %s: nested class of the stock one, not in the shadow (the stock one stays)"%e)
                continue
            n+=1; mine=ClassFile(open(path,'rb').read()); gaps=[]
            if mine.super!=stock.super: gaps.append('superclass %s, stock %s'%(mine.super,stock.super))
            for i in stock.interfaces:
                if i not in mine.interfaces: gaps.append('does not implement '+i)
            for kind,sm,mm in (('field',stock.fields,mine.fields),('method',stock.methods,mine.methods)):
                have={(m['name'],m['desc']):m for m in mm}
                for m in sm:
                    if m['flags']&ACC_PRIVATE: continue
                    if m['name']=='<clinit>': continue
                    if (m['flags']&ACC_SYNTHETIC) or m['name'].startswith('class$'): continue   # compiler helpers
                    o=have.get((m['name'],m['desc']))
                    if o is None: gaps.append('no %s %s %s'%(kind,m['name'],m['desc']))
                    elif (o['flags']^m['flags'])&ACC_STATIC: gaps.append('%s %s: static-ness differs'%(kind,m['name']))
                    elif o['flags']&ACC_PRIVATE: gaps.append('%s %s is private in the shadow'%(kind,m['name']))
                    elif kind=='field' and m['value'] is not None and o['value']!=m['value']:
                        gaps.append('constant %s = %s, stock %s'%(m['name'],o['value'],m['value']))
            if gaps:
                bad+=1; print("  GAP  %s"%e)
                for g in gaps[:20]: print("         "+g)
                if len(gaps)>20: print("         ... and %d more"%(len(gaps)-20))
    if bad:
        print("ERROR: %d shadow%s lack something the stock class of this MIBHMI.jar offers to the rest of the HMI."
              %(bad,'' if bad==1 else 's'))
        return 1
    print("shadows: %d compiled shadows offer everything their stock classes do"%n); return 0

def main(a):
    if len(a)==5 and a[1]=='update': return cmd_update(a[2],a[3],a[4])
    if len(a)==5 and a[1]=='stock':  return cmd_stock(a[2],a[3],a[4])
    if len(a)==5 and a[1]=='api':    return cmd_api(a[2],a[3],a[4])
    print(__doc__)
    print("usage: check_shadows.py stock|update <MIBHMI.jar> <jar/src> <jar/shadows.lock>")
    print("       check_shadows.py api <MIBHMI.jar> <jar/src> <jar/build/classes>")
    return 2

if __name__=='__main__':
    sys.exit(main(sys.argv))
