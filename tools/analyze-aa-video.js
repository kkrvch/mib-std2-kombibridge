#!/usr/bin/env node
// Offline parser for the one-shot aa_video_probe forensic package.
// It never talks to the HU.  The candidate collector stores only the first 512 bytes of each
// range, so this tool reports NAL evidence and can export that bounded prefix for inspection; it
// does not pretend that the prefix is a complete movie.

const fs = require('fs');

function usage() {
  console.error('usage: node tools/analyze-aa-video.js --candidates aa_video_probe_candidates.bin [--config aa_video_probe_config] [--out prefix.h264]');
  process.exit(2);
}
const args = process.argv.slice(2);
function arg(name) { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; }
const candidatePath = arg('--candidates');
const configPath = arg('--config');
const outPath = arg('--out');
if (!candidatePath) usage();

function startCode(buf, i) {
  if (i + 3 <= buf.length && buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1) return 3;
  if (i + 4 <= buf.length && buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 0 && buf[i + 3] === 1) return 4;
  return 0;
}
function nalTypes(buf) {
  const types = []; let idr = false, sps = false, pps = false;
  for (let i = 0; i + 3 < buf.length;) {
    const sc = startCode(buf, i);
    if (!sc) { i++; continue; }
    const n = i + sc;
    if (n < buf.length) {
      const t = buf[n] & 0x1f; types.push(t);
      if (t === 5) idr = true; if (t === 7) sps = true; if (t === 8) pps = true;
    }
    i = n + 1;
  }
  return { types, idr, sps, pps, valid: types.filter(t => t >= 1 && t <= 12).length };
}
// Minimal SPS parser (baseline/main, no scaling lists): coded size after cropping.
function spsSize(buf) {
  let i = 0, sc;
  while (i + 3 < buf.length && !(sc = startCode(buf, i))) i++;
  if (!sc || (buf[i + sc] & 0x1f) !== 7) return null;
  const rbsp = [];
  for (let k = i + sc + 1; k < buf.length; k++) {
    if (k + 2 < buf.length && buf[k] === 0 && buf[k + 1] === 0 && buf[k + 2] === 3) { rbsp.push(0, 0); k += 2; continue; }
    if (k + 3 <= buf.length && startCode(buf, k)) break;
    rbsp.push(buf[k]);
  }
  let p = 0;
  const bit = () => (rbsp[p >> 3] >> (7 - (p++ & 7))) & 1;
  const u = n => { let v = 0; while (n--) v = v * 2 + bit(); return v; };
  const ue = () => { let z = 0; while (!bit()) z++; return (1 << z) - 1 + u(z); };
  const profile = u(8); u(8); const level = u(8); ue();
  if ([100, 110, 122, 244, 44, 83, 86, 118, 128].includes(profile)) return { profile, level, width: 0, height: 0, note: 'high-profile SPS not parsed' };
  ue(); const poc = ue(); if (poc === 0) ue(); else if (poc === 1) return null;
  ue(); u(1);
  const w = ue() + 1, h = ue() + 1, frameMbsOnly = u(1);
  if (!frameMbsOnly) u(1);
  u(1);
  let c = [0, 0, 0, 0]; if (u(1)) c = [ue(), ue(), ue(), ue()];
  return { profile, level, width: w * 16 - 2 * (c[0] + c[1]), height: h * 16 * (2 - frameMbsOnly) - 2 * (c[2] + c[3]) };
}
function hex(n) { return '0x' + n.toString(16).padStart(8, '0'); }

const b = fs.readFileSync(candidatePath);
if (b.length < 12 || b.toString('ascii', 0, 4) !== '1ACV') {
  throw new Error('not a VCA1 candidate file (expected little-endian magic 1ACV)');
}
const version = b.readUInt32LE(4), snapshots = b.readUInt32LE(8);
const candidateSize = 6 * 4 + 512, snapshotSize = 2 * 4 + 8 * candidateSize;
console.log(`candidate file: version=${version} snapshots=${snapshots} bytes=${b.length}`);
let best = null;
for (let s = 0; s < Math.min(snapshots, 8); s++) {
  const so = 12 + s * snapshotSize;
  if (so + snapshotSize > b.length) break;
  const n = b.readUInt32LE(so), count = b.readUInt32LE(so + 4);
  for (let c = 0; c < Math.min(count, 8); c++) {
    const o = so + 8 + c * candidateSize;
    const index = b.readUInt32LE(o), base = b.readUInt32LE(o + 4);
    const off = b.readUInt32LE(o + 8), end = b.readUInt32LE(o + 12);
    const len = b.readUInt32LE(o + 16), copied = b.readUInt32LE(o + 20);
    // The collector copied from data+start+2, i.e. after the 2-byte AAP msg id. For a media DATA
    // message the next 8 bytes are a big-endian timestamp (usec); the access unit starts after
    // them (callback hdr argument = 10). Reading those 8 bytes as a start code gave the bogus
    // "filler NAL 12" of the first analysis.
    const raw = b.subarray(o + 24, o + 24 + Math.min(copied, 512));
    const ts = raw.length >= 8 ? raw.readBigUInt64BE(0) : null;
    const bytes = raw.length >= 8 ? raw.subarray(8) : raw;
    const info = nalTypes(bytes);
    // Service/object memory often contains accidental 00 00 01 byte patterns. H.264 NAL types
    // 1..12 are valid; invalid type-0 runs must not outrank a real Annex-B access unit.
    const score = (info.idr ? 8 : 0) + (info.sps ? 4 : 0) + (info.pps ? 4 : 0) + info.valid * 2;
    console.log(`snapshot=${s} n=${n} candidate=${c} index=${index} base=${hex(base)} off=${hex(off)} end=${hex(end)} len=${len} copied=${copied} ts=${ts === null ? '-' : ts + 'us'} AU=${Math.max(0, len - 8)} NAL=[${info.types.join(',')}]`);
    if (!best || score > best.score) best = { score, bytes, info, ts, s, c, n, base, off, end, len, copied };
  }
}
if (configPath) {
  const cfg = fs.readFileSync(configPath);
  const info = nalTypes(cfg);
  console.log(`config: bytes=${cfg.length} NAL=[${info.types.join(',')}] SPS=${info.sps} PPS=${info.pps}`);
  const sps = spsSize(cfg);
  if (sps) console.log(`SPS: profile_idc=${sps.profile} level_idc=${sps.level} coded=${sps.width}x${sps.height}${sps.note ? ' (' + sps.note + ')' : ''}`);
}
if (!best) throw new Error('no candidates in file');
console.log(`selected: snapshot=${best.s} candidate=${best.c} ts=${best.ts}us AU=${best.len - 8} prefix=${best.copied} NAL=[${best.info.types.join(',')}]`);
if (outPath) { fs.writeFileSync(outPath, best.bytes); console.log(`wrote bounded candidate prefix: ${outPath}`); }
