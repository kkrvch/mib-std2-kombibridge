#!/usr/bin/env node
// Print dynamic relocations associated with selected exported symbols.  This
// helps locate singleton/global uses when the implementation is stripped.
const fs=require('fs'); function u16(b,o){return b.readUInt16LE(o)} function u32(b,o){return b.readUInt32LE(o)}
const path=process.argv[2], needle=new RegExp(process.argv[3]||'Video|Gal|H264','i'), b=fs.readFileSync(path);
const shoff=u32(b,0x20), es=u16(b,0x2e), n=u16(b,0x30), ss=u16(b,0x32), so=shoff+ss*es, no=u32(b,so+16), ns=u32(b,so+20), names=b.subarray(no,no+ns); const sec=[];
const sname=o=>{let e=o;while(e<names.length&&names[e])e++;return names.toString('utf8',o,e)};
for(let i=0;i<n;i++){const o=shoff+i*es;sec.push({name:sname(u32(b,o)),type:u32(b,o+4),addr:u32(b,o+12),off:u32(b,o+16),size:u32(b,o+20),link:u32(b,o+24),entsize:u32(b,o+36)})}
const dyn=sec.find(x=>x.name==='.dynsym'), str=sec[dyn.link], sb=b.subarray(str.off,str.off+str.size), sym=[]; for(let o=0;o<dyn.size;o+=dyn.entsize||16){const n0=u32(b,dyn.off+o),e=n0;let z=e;while(z<sb.length&&sb[z])z++;sym.push({name:sb.toString('utf8',e,z),value:u32(b,dyn.off+o+4),index:o/(dyn.entsize||16)})}
const wanted=new Set(sym.filter(x=>needle.test(x.name)).map(x=>x.index)); console.log(`ELF ${path}, symbols /${needle.source}/`); for(const s of sym.filter(x=>wanted.has(x.index)))console.log(`SYMBOL ${s.index} 0x${s.value.toString(16)} ${s.name}`);
for(const r of sec.filter(x=>x.name==='.rel.dyn'||x.name==='.rel.plt')){console.log(r.name); for(let o=0;o<r.size;o+=r.entsize||8){const ro=r.off+o, off=u32(b,ro), info=u32(b,ro+4), si=info>>>8; if(wanted.has(si))console.log(`  reloc VA 0x${off.toString(16)} type ${info&255} symbol ${sym[si].name}`)}}
