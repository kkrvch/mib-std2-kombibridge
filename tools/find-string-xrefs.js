#!/usr/bin/env node
// Offline ARM literal-pool xref finder.  Useful when stripped C++ methods
// retain diagnostic strings but no usable symbol names.
const fs=require('fs');
function u16(b,o){return b.readUInt16LE(o)} function u32(b,o){return b.readUInt32LE(o)}
const path=process.argv[2], re=new RegExp(process.argv[3]||'Video|cluster|FPK','i'); const b=fs.readFileSync(path);
const shoff=u32(b,0x20), shents=u16(b,0x2e), shnum=u16(b,0x30), shstr=u16(b,0x32), so=shoff+shstr*shents;
const names=b.subarray(u32(b,so+16),u32(b,so+16)+u32(b,so+20)); const n=o=>{let e=o;while(e<names.length&&names[e])e++;return names.toString('utf8',o,e)};
const sec=[]; for(let i=0;i<shnum;i++){const o=shoff+i*shents;sec.push({name:n(u32(b,o)),addr:u32(b,o+12),off:u32(b,o+16),size:u32(b,o+20)})}
const phoff=u32(b,0x1c), phents=u16(b,0x2a), phnum=u16(b,0x2c), loads=[]; for(let i=0;i<phnum;i++){const o=phoff+i*phents;if(u32(b,o)===1)loads.push({off:u32(b,o+4),addr:u32(b,o+8),size:u32(b,o+16)})}
const v2f=v=>{const s=loads.find(x=>v>=x.addr&&v<x.addr+x.size);return s?s.off+v-s.addr:null};
const strings=[]; for(const s of sec.filter(x=>x.name==='.rodata'||x.name==='.data')){for(let i=0;i<s.size;){let j=i;while(j<s.size&&b[s.off+j])j++;if(j-i>=4){const text=b.toString('utf8',s.off+i,s.off+j);if(re.test(text))strings.push({va:s.addr+i,text})}i=j+1}}
const wanted=new Map(strings.map(x=>[x.va,x])); console.log(`ELF ${path}`); for(const x of strings)console.log(`STRING 0x${x.va.toString(16)} ${JSON.stringify(x.text)}`);
console.log('LDR-literal xrefs:');
for(const s of loads){for(let va=(s.addr+3)&~3;va<s.addr+s.size-4;va+=4){const o=v2f(va);if(o==null)continue;const w=u32(b,o); if((w&0xffff0000)!==0xe59f0000)continue; const up=(w&0x00800000)!==0, imm=w&0xfff, t=(va+8+(up?imm:-imm))>>>0, po=v2f(t); if(po==null)continue; const literal=u32(b,po), hit=wanted.get(literal); if(hit) console.log(`  0x${va.toString(16)} -> pool 0x${t.toString(16)} -> 0x${literal.toString(16)} ${JSON.stringify(hit.text)}`)}}
