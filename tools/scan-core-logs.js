#!/usr/bin/env node
// Scan compressed QNX core snapshots for retained SAL/MMR log text.  Core files
// are treated as untrusted binary evidence; no code is executed and no files
// are written.
const fs=require('fs'), path=require('path'), zlib=require('zlib');
const root=process.argv[2]||'/path/to/unit-dump/tsd/var/core';
function walk(dir,out=[]){for(const ent of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,ent.name);if(ent.isDirectory())walk(p,out);else if(/\.core(?:\.gz)?$/.test(ent.name))out.push(p)}return out}
const re=/(?:H264VideoPlayer|MMR |mdevpush:|video\.frame_rate|screen:\/?|cluster instrument|FPK video)/i; const seen=new Set();
for(const file of walk(root)){let b;try{const raw=fs.readFileSync(file);b=file.endsWith('.gz')?zlib.gunzipSync(raw):raw}catch(e){continue}let hit=0;for(const m of b.toString('latin1').split('\0')){if(!re.test(m))continue;const text=m.replace(/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\xff]/g,'.');if(seen.has(text))continue;seen.add(text);console.log(`${file}\n  ${JSON.stringify(text)}`);if(++hit>=8)break}}
