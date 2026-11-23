#!/usr/bin/env python3
"""Host test of tools/check_shadows.py on small classes compiled here (needs javac: $JAVAC or PATH).

Shows that the fingerprint follows what a class is and does, not how its file is laid out, and
that both checks (stock, api) report what they are there for. With jar/lib/MIBHMI.jar and
jar/build/classes present (after a jar build) it also runs the two checks on the real thing.
"""
import os, sys, json, shutil, subprocess, tempfile, zipfile
HERE=os.path.dirname(os.path.abspath(__file__)); ROOT=os.path.normpath(os.path.join(HERE,'..','..'))
sys.path.insert(0,os.path.join(ROOT,'tools'))
import check_shadows as cs

failed=0
def check(what,cond,detail=''):
    global failed
    print("%-66s %s"%(what,'ok' if cond else 'FAILED '+str(detail)))
    if not cond: failed+=1

def javac(tmp,name,files):
    """Compile {relative path: source} into tmp/name/classes; returns that directory."""
    src=os.path.join(tmp,name,'src'); out=os.path.join(tmp,name,'classes'); os.makedirs(out)
    paths=[]
    for rel,text in files.items():
        p=os.path.join(src,rel); os.makedirs(os.path.dirname(p),exist_ok=True)
        open(p,'w').write(text); paths.append(p)
    cmd=os.environ.get('JAVAC','javac').split()+['-nowarn','-d',out]+paths
    r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if r.returncode: raise RuntimeError(r.stdout.decode('utf-8','replace'))
    return out

def jar_of(tmp,name,classes):
    j=os.path.join(tmp,name+'.jar')
    with zipfile.ZipFile(j,'w') as z:
        for root,_,fs in os.walk(classes):
            for f in fs:
                p=os.path.join(root,f); z.write(p,os.path.relpath(p,classes).replace(os.sep,'/'))
    return j

def run(*args):
    r=subprocess.run([sys.executable,'-B',os.path.join(ROOT,'tools','check_shadows.py')]+list(args),
                     stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    return r.returncode,r.stdout.decode('utf-8','replace')

BASE='''package p;
public class Foo implements Runnable {
    public static final int LIMIT = 7;
    protected int count;
    public Foo() {}
    public void run() { count = count + LIMIT; }
    protected String name(int n) { return n > 0 ? "foo" : "none"; }
    private void hidden() {}
}
'''
def fp(classes): return cs.fingerprint(cs.ClassFile(open(os.path.join(classes,'p','Foo.class'),'rb').read()))

def main():
    tmp=tempfile.mkdtemp()
    try:
        try: base=javac(tmp,'base',{'p/Foo.java':BASE})
        except (OSError,RuntimeError) as x:
            print("SKIPPED: javac not usable (%s)"%str(x)[:80]); return 0
        f0=fp(base)
        # same class, other constant-pool order (an unrelated member first moves every index)
        same=javac(tmp,'same',{'p/Foo.java':BASE.replace('    public static final int LIMIT = 7;',
                   '    public static final int LIMIT = 7;\n    static final String EXTRA = "zzz";')})
        f1=fp(same)
        check("unrelated member added: the other members keep their fingerprints",
              all(f1['members'][k]==v for k,v in f0['members'].items()))
        check("... and the class as a whole differs",f1['class']!=f0['class'])
        body=fp(javac(tmp,'body',{'p/Foo.java':BASE.replace('count + LIMIT','count - LIMIT')}))
        diff=[k for k in f0['members'] if body['members'].get(k)!=f0['members'][k]]
        check("changed method body: exactly that method differs",diff==['method run()V'],diff)
        const=fp(javac(tmp,'const',{'p/Foo.java':BASE.replace('LIMIT = 7','LIMIT = 8')}))
        check("changed constant value: the field differs",const['members']['field LIMIT:I']!=f0['members']['field LIMIT:I'])
        iface=fp(javac(tmp,'iface',{'p/Foo.java':BASE.replace('implements Runnable','implements Runnable, java.io.Serializable')}))
        check("added interface: the class head differs, members do not",
              iface['head']!=f0['head'] and iface['members']==f0['members'])

        # the two commands, with base as "MIBHMI.jar" and a shadow source tree
        stock=jar_of(tmp,'stock',base); src=os.path.join(tmp,'base','src'); lock=os.path.join(tmp,'shadows.lock')
        rc,out=run('update',stock,src,lock); check("update writes the lock",rc==0 and os.path.isfile(lock),out)
        rc,out=run('stock',stock,src,lock);  check("stock: same firmware passes",rc==0,out)
        other=jar_of(tmp,'other',javac(tmp,'fw2',{'p/Foo.java':BASE.replace('"foo"','"bar"')}))
        rc,out=run('stock',other,src,lock)
        check("stock: another firmware's class is named with the member that differs",
              rc==1 and 'p/Foo' in out and 'name(I)Ljava/lang/String;' in out,out)
        rc,out=run('api',stock,src,base);    check("api: a faithful shadow passes",rc==0,out)
        gap=javac(tmp,'gap',{'p/Foo.java':BASE.replace('    protected String name(int n) { return n > 0 ? "foo" : "none"; }\n','')})
        rc,out=run('api',stock,src,gap)
        check("api: a shadow without a protected stock method is a gap",rc==1 and 'no method name' in out,out)
        priv=javac(tmp,'priv',{'p/Foo.java':BASE.replace('    private void hidden() {}\n','')})
        rc,out=run('api',stock,src,priv);    check("api: a private stock method may be dropped",rc==0,out)
        more=javac(tmp,'more',{'p/Foo.java':BASE.replace('    private void hidden() {}','    public static void poke() {}')})
        rc,out=run('api',stock,src,more);    check("api: a shadow may add members",rc==0,out)
        val=javac(tmp,'val',{'p/Foo.java':BASE.replace('LIMIT = 7','LIMIT = 0')})
        rc,out=run('api',stock,src,val);     check("api: a constant with another value is a gap",rc==1 and 'constant LIMIT' in out,out)

        real=os.path.join(ROOT,'jar','lib','MIBHMI.jar'); built=os.path.join(ROOT,'jar','build','classes')
        if os.path.isfile(real):
            rc,out=run('stock',real,os.path.join(ROOT,'jar','src'),os.path.join(ROOT,'jar','shadows.lock'))
            check("real MIBHMI.jar: stock classes match jar/shadows.lock",rc==0,out[-400:])
            if os.path.isfile(os.path.join(built,'de','aatokombi','Config.class')) and \
               os.path.isfile(os.path.join(built,'org','dsi','ifc','androidauto2','Constants.class')):
                rc,out=run('api',real,os.path.join(ROOT,'jar','src'),built)
                check("real MIBHMI.jar: every shadow offers what its stock class does",rc==0,out[-600:])
        else:
            print("(real MIBHMI.jar part skipped: no jar/lib/MIBHMI.jar)")
    finally:
        shutil.rmtree(tmp,ignore_errors=True)
    print("all tests passed" if not failed else "TESTS FAILED")
    return 1 if failed else 0

if __name__=='__main__':
    sys.exit(main())
