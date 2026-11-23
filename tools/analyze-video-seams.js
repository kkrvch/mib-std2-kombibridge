#!/usr/bin/env node
// Offline inventory of the stock MST2 video seams.  It intentionally does not
// disassemble or execute anything: the output is evidence for choosing the
// existing SAL VideoPlayer/DisplayManager path before changing the shim.

const fs = require('fs');

function u16(b, o) { return b.readUInt16LE(o); }
function u32(b, o) { return b.readUInt32LE(o); }

function elf(path) {
  const b = fs.readFileSync(path);
  if (b.readUInt8(0) !== 0x7f || b.toString('ascii', 1, 4) !== 'ELF')
    throw new Error(`${path}: not ELF`);
  const shoff = u32(b, 0x20), shents = u16(b, 0x2e), shnum = u16(b, 0x30), shstr = u16(b, 0x32);
  const shstrOff = shoff + shstr * shents;
  const names = b.subarray(u32(b, shstrOff + 16), u32(b, shstrOff + 16) + u32(b, shstrOff + 20));
  const name = (off) => {
    let end = off;
    while (end < names.length && names[end]) end++;
    return names.toString('utf8', off, end);
  };
  const sections = [];
  for (let i = 0; i < shnum; i++) {
    const o = shoff + i * shents;
    sections.push({
      name: name(u32(b, o)), type: u32(b, o + 4), addr: u32(b, o + 12),
      off: u32(b, o + 16), size: u32(b, o + 20), link: u32(b, o + 24), entsize: u32(b, o + 36)
    });
  }
  const loads = [];
  const phoff = u32(b, 0x1c), phents = u16(b, 0x2a), phnum = u16(b, 0x2c);
  for (let i = 0; i < phnum; i++) {
    const o = phoff + i * phents;
    if (u32(b, o) === 1) loads.push({ off: u32(b, o + 4), addr: u32(b, o + 8), size: u32(b, o + 16) });
  }
  const v2f = (v) => {
    const s = loads.find(x => v >= x.addr && v < x.addr + x.size);
    return s ? s.off + v - s.addr : null;
  };
  const dyn = sections.find(x => x.name === '.dynsym');
  const symbols = [];
  if (dyn) {
    const st = sections[dyn.link];
    const strings = b.subarray(st.off, st.off + st.size);
    const sn = (off) => {
      let end = off;
      while (end < strings.length && strings[end]) end++;
      return strings.toString('utf8', off, end);
    };
    const ent = dyn.entsize || 16;
    for (let o = 0; o + ent <= dyn.size; o += ent) {
      const no = u32(b, dyn.off + o);
      const n = sn(no);
      if (n) symbols.push({ name: n, addr: u32(b, dyn.off + o + 4), size: u32(b, dyn.off + o + 8) });
    }
  }
  return { path, b, sections, loads, symbols, v2f };
}

function printSymbols(e, re) {
  const rows = e.symbols.filter(s => re.test(s.name)).sort((a, b) => a.addr - b.addr);
  for (const s of rows)
    console.log(`  0x${s.addr.toString(16).padStart(8, '0')} size=0x${s.size.toString(16).padStart(5, '0')} ${s.name}`);
}

function printVtable(e, name, words = 16) {
  const s = e.symbols.find(x => x.name === name);
  if (!s) { console.log(`  ${name}: not exported`); return; }
  const off = e.v2f(s.addr);
  if (off == null || off + words * 4 > e.b.length) { console.log(`  ${name}: VA 0x${s.addr.toString(16)} not file-backed`); return; }
  console.log(`  ${name} @ VA 0x${s.addr.toString(16)}:`);
  for (let i = 0; i < words; i++) console.log(`    +0x${(i * 4).toString(16).padStart(2, '0')} 0x${u32(e.b, off + i * 4).toString(16).padStart(8, '0')}`);
}

function findStrings(e, re) {
  const out = [];
  for (const sec of e.sections.filter(s => s.name === '.rodata' || s.name === '.data')) {
    const raw = e.b.subarray(sec.off, sec.off + sec.size);
    let start = 0;
    for (let i = 0; i <= raw.length; i++) {
      if (i === raw.length || raw[i] === 0) {
        if (i - start >= 4) {
          const s = raw.toString('utf8', start, i);
          if (re.test(s)) out.push({ va: sec.addr + start, text: s });
        }
        start = i + 1;
      }
    }
  }
  return out;
}

// Usage: node analyze-video-seams.js <unit-dump-root> [sal-binary] [displaymanager-binary]
// <unit-dump-root> is a local copy of the head unit's file system (with tsd/bin/...).
const root = process.argv[2];
if (!root) {
  console.error('usage: node analyze-video-seams.js <unit-dump-root> [sal-binary] [displaymanager-binary]');
  process.exit(2);
}
const salPath = process.argv[3] || `${root}/tsd/bin/sal/tsd.mibstd2.sal.main`;
const dmPath = process.argv[4] || `${root}/tsd/bin/displaymanager/tsd.mibstd2.system.displaymanager`;
const sal = elf(salPath);
console.log(`SAL: ${salPath}`);
console.log('Video-related exported symbols:');
printSymbols(sal, /VideoSink|GalVideo|H264Video|VideoPlayer|VideoChannel|IVideoSinkCallbacks/);
console.log('Callback vtables (raw words, including ABI header/address-point area):');
printVtable(sal, '_ZTV19IVideoSinkCallbacks', 12);
printVtable(sal, '_ZTVN3tsd3sal3gal5video17GalVideoCallbacksE', 12);
console.log('SAL decoder/player vtables (raw words):');
printVtable(sal, '_ZTVN3tsd3sal10appconnect5video12H264VideoGALE', 16);
printVtable(sal, '_ZTVN3tsd3sal10appconnect5video15H264VideoPlayerE', 16);
printVtable(sal, '_ZTVN3tsd3sal3gal5video8GalVideoE', 12);
console.log('SAL video strings:');
for (const x of findStrings(sal, /screen:\/wingrp|mdevpush:|configureCodec|processVideoData|attachOutput|cluster|VideoChannel/i))
  console.log(`  0x${x.va.toString(16)} ${x.text}`);

if (fs.existsSync(dmPath)) {
  const dm = elf(dmPath);
  console.log(`\nDisplayManager: ${dmPath}`);
  console.log('Display/video vtables:');
  printSymbols(dm, /MostSink|OpenMAXEncoderScreenWriter|OpenMAXEncoder|DisplayableQNXScreen/);
  printVtable(dm, '_ZTVN3tsd7mibstd26system14displaymanager27DSIDisplayManagementAdapterE', 36);
  printVtable(dm, '_ZTVN3tsd7mibstd26system14displaymanager8MostSinkE', 12);
  printVtable(dm, '_ZTVN3tsd7mibstd26system14displaymanager9TsEncoderE', 12);
  console.log('Cluster/FPK strings:');
  for (const x of findStrings(dm, /cluster instrument|cluster encoder|FPK video|video connection/i))
    console.log(`  0x${x.va.toString(16)} ${x.text}`);
}
