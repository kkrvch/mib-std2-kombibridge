#!/usr/bin/env node
// Small offline ARM/ELF helper for the MST2 static-analysis notes.
// It intentionally only prints bytes and branch/xref relationships; it never
// executes code from the head-unit dump.
const fs = require('fs');
function u16(b,o){return b.readUInt16LE(o)}
function u32(b,o){return b.readUInt32LE(o)}
function elf(path){
  const b=fs.readFileSync(path); if(b.toString('ascii',1,4)!=='ELF') throw Error('not ELF');
  const shoff=u32(b,0x20), shents=u16(b,0x2e), shnum=u16(b,0x30), shstr=u16(b,0x32);
  const so=shoff+shstr*shents, names=b.subarray(u32(b,so+16),u32(b,so+16)+u32(b,so+20));
  const sn=o=>{let e=o;while(e<names.length&&names[e])e++;return names.toString('utf8',o,e)};
  const sec=[]; for(let i=0;i<shnum;i++){const o=shoff+i*shents;sec.push({name:sn(u32(b,o)),addr:u32(b,o+12),off:u32(b,o+16),size:u32(b,o+20)})}
  const phoff=u32(b,0x1c), phents=u16(b,0x2a), phnum=u16(b,0x2c), loads=[];
  for(let i=0;i<phnum;i++){const o=phoff+i*phents;if(u32(b,o)===1)loads.push({off:u32(b,o+4),addr:u32(b,o+8),size:u32(b,o+16)})}
  const v2f=v=>{const s=loads.find(x=>v>=x.addr&&v<x.addr+x.size);return s?s.off+v-s.addr:null};
  return {b,sec,loads,v2f};
}
function arm(e, va){const o=e.v2f(va); return o==null?null:u32(e.b,o)}
function reg(n){return 'r'+n}
function decode(w,va){
  const c=[]; const rn=(w>>>16)&15, rd=(w>>>12)&15;
  if((w&0x0ffffff0)===0x012fff10) return `bx ${reg(w&15)}`;
  if((w&0x0ffffff0)===0x012fff30) return `blx ${reg(w&15)}`;
  if((w&0x0e000000)===0x0a000000){let imm=w&0xffffff;if(imm&0x800000)imm|=~0xffffff;const t=(va+8+((imm<<2)|0))>>>0;return `${(w&0x01000000)?'bl':'b'} 0x${t.toString(16)}`}
  if((w&0x0c000000)===0x04000000){const load=(w>>>20)&1, pre=(w>>>24)&1, up=(w>>>23)&1, imm=w&0xfff; return `${load?'ldr':'str'} ${reg(rd)}, [${reg(rn)}${pre?(up?'+':'-')+`0x${imm.toString(16)}`:''}]${(w>>>21)&1?'!':''}`}
  if((w&0x0c000000)===0x00000000){const op=(w>>>21)&15; if(op===13)return `mov ${reg(rd)}, #/reg`; if(op===4)return `add ${reg(rd)}, ${reg(rn)}, #/reg`; if(op===2)return `sub ${reg(rd)}, ${reg(rn)}, #/reg`; if(op===10)return `cmp ${reg(rn)}, #/reg`;}
  return '';
}
const path=process.argv[2]; const addrs=(process.argv.slice(3).length?process.argv.slice(3):['0x29ad08','0x29ad10','0x29ad18','0x29ad20','0x29ad28','0x29ad30','0x29ad38','0x29ad78','0x29ad80','0x29ad94','0x29ade0','0x29ae14']).map(x=>parseInt(x));
const e=elf(path); console.log(`SAL ${path}`);
for(const va of addrs){console.log(`\n0x${va.toString(16)}:`);for(let i=0;i<12;i++){const a=va+i*4,w=arm(e,a);if(w==null)break;console.log(`  0x${a.toString(16)} 0x${w.toString(16).padStart(8,'0')} ${decode(w,a)}`)}}
const targets=new Set(addrs); console.log('\nBL xrefs to selected addresses:');
for(const s of e.loads){for(let va=(s.addr+3)&~3; va<s.addr+s.size-4; va+=4){const w=arm(e,va);if(w==null||(w&0x0e000000)!==0x0a000000)continue;let imm=w&0xffffff;if(imm&0x800000)imm|=~0xffffff;const t=(va+8+((imm<<2)|0))>>>0;if(targets.has(t))console.log(`  0x${va.toString(16)} -> 0x${t.toString(16)} ${(w&0x01000000)?'BL':'B'}`)}}
